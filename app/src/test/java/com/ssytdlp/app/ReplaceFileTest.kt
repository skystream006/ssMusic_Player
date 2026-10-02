package com.ssytdlp.app

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Track
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okio.Buffer
import okio.blackholeSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReplaceFileTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val uri = Uri.parse("content://replacement/song")
    private val track = Track("job", "[NoVocals]/song.mp3")
    private lateinit var provider: ReplacementDocumentProvider

    @Before fun setup() {
        provider = ReplacementDocumentProvider()
        ShadowContentResolver.registerProviderInternal("replacement", provider)
    }

    @Test fun `validates matching formats and known size before sending`() {
        validateReplacement(track, SelectedDocument(uri, "new.MP3", REPLACEMENT_LIMIT))
        validateReplacement(track, SelectedDocument(uri, "new.mp3", null))
        validateReplacement(track.copy(name = "song.flac"), SelectedDocument(uri, "new.FLAC", 10))
        listOf(
            SelectedDocument(uri, "new.wav", 10),
            SelectedDocument(uri, "mp3", 10),
            SelectedDocument(uri, "new.mp3", 0),
            SelectedDocument(uri, "new.mp3", REPLACEMENT_LIMIT + 1)
        ).forEach { document ->
            assertThrows(IllegalArgumentException::class.java) { validateReplacement(track, document) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateReplacement(track.copy(mediaType = "video"), SelectedDocument(uri, "new.mp3", 10))
        }
    }

    @Test fun `document upload streams exactly one file and closes the input`() = runBlocking {
        var closed = false
        val bytes = byteArrayOf(0, 1, 2, -1)
        provider.size = bytes.size.toLong()
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        })
        val body = buildReplacementBody(context, track, uri) as MultipartBody
        assertEquals(1, body.parts.size)
        assertEquals("form-data; name=\"file\"; filename=\"replacement.MP3\"",
            body.parts.single().headers!!["Content-Disposition"])
        assertTrue(body.isOneShot())
        val data = Buffer()
        body.parts.single().body.writeTo(data)
        assertArrayEquals(bytes, data.readByteArray())
        assertTrue(closed)
    }

    @Test fun `streaming rejects empty documents when size is unknown`() = runBlocking {
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf()))
        val body = buildReplacementBody(context, track, uri) as MultipartBody
        assertEquals(-1L, body.parts.single().body.contentLength())
        assertThrows(IOException::class.java) { body.writeTo(Buffer()) }
    }

    @Test fun `streaming rejects oversized documents regardless of reported size and closes the stream`() = runBlocking {
        for (reportedSize in listOf(null, 1L)) {
            provider.size = reportedSize
            var closed = false
            shadowOf(context.contentResolver).registerInputStream(uri, object : InputStream() {
                private var remaining = REPLACEMENT_LIMIT + 1
                override fun read(): Int = if (remaining-- > 0) 0 else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(remaining, length.toLong()).toInt()
                    remaining -= count
                    return count
                }
                override fun close() { closed = true }
            })
            val body = buildReplacementBody(context, track, uri)
            blackholeSink().buffer().use { sink ->
                val failure = assertThrows(IOException::class.java) { body.writeTo(sink) }
                assertTrue(failure.message!!.contains("512 MB"))
            }
            assertTrue(closed)
        }
    }

    @Test fun `rejects non-picker resources and changed document sizes`() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            selectedDocument(context, Uri.parse("file:///tmp/replacement.mp3"))
        }
        provider.size = 2
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1)))
        val body = buildReplacementBody(context, track, uri)
        assertThrows(IOException::class.java) { body.writeTo(Buffer()) }
    }

    @Test fun `confirmation requires selection and explains destructive effects`() {
        var filename: String? by androidx.compose.runtime.mutableStateOf(null)
        var replacements = 0
        compose.setContent {
            MusicTheme {
                ReplaceFileDialog(track, filename, false, null,
                    choose = { filename = "replacement.mp3" }, dismiss = {}, replace = { replacements++ })
            }
        }
        compose.onNode(hasText("Replace File") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("This permanently overwrites", substring = true).assertExists()
        compose.onNodeWithText("Choose replacement file").performClick()
        assertEquals(0, replacements)
        compose.onNode(hasText("Replace File") and hasClickAction()).assertIsEnabled().performClick()
        assertEquals(1, replacements)
    }

    @Test fun `uploading disables duplicate replacement and dismissal controls`() {
        compose.setContent {
            MusicTheme { ReplaceFileDialog(track, "replacement.mp3", true, null, {}, {}, {}) }
        }
        compose.onNode(hasText("Replace File") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Choose replacement file").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithText("Uploading and replacing file…").assertExists()
    }

    @Test fun `server rejection stays visible with retry and cancel available`() {
        var dismissed = false
        compose.setContent {
            MusicTheme {
                ReplaceFileDialog(track, "replacement.mp3", false, "Song is being transcribed",
                    {}, { dismissed = true }, {})
            }
        }
        compose.onNodeWithText("Song is being transcribed").assertExists()
        compose.onNode(hasText("Replace File") and hasClickAction()).assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }
}

class ReplacementDocumentProvider : ContentProvider() {
    var size: Long? = null
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .apply { addRow(arrayOf("replacement.MP3", size)) }
    override fun getType(uri: Uri) = "audio/mpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
