package com.ssytdlp.app

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Track
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okio.Buffer
import okio.blackholeSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReplacementDocumentTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var provider: ReplacementDocumentProvider
    private val uri = Uri.parse("content://replacement-test/song")
    private val track = Track("job", "[NoVocals]/song.mp3")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        provider = ReplacementDocumentProvider(temporary.newFile().apply { writeText("replacement audio") })
        ShadowContentResolver.registerProviderInternal(uri.authority, provider)
    }

    @Test fun `matching formats accept case differences and the inclusive size limit`() {
        listOf(1L, REPLACEMENT_FILE_LIMIT, null).forEach { size ->
            validateReplacementDocument(track, SelectedDocument(uri, "NEW.MP3", size))
        }
        validateReplacementDocument(track.copy(name = "song.flac"), SelectedDocument(uri, "new.FLAC", 20))
    }

    @Test fun `empty oversized mismatched and video documents are rejected`() {
        listOf(0L, REPLACEMENT_FILE_LIMIT + 1).forEach { size ->
            assertThrows(IllegalArgumentException::class.java) {
                validateReplacementDocument(track, SelectedDocument(uri, "song.mp3", size))
            }
        }
        listOf("song.flac", "song.mp3.exe", "song").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                validateReplacementDocument(track, SelectedDocument(uri, name, 20))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateReplacementDocument(track.copy(mediaType = "video"), SelectedDocument(uri, "song.mp3", 20))
        }
        assertThrows(IllegalArgumentException::class.java) {
            selectedDocument(context, Uri.parse("file:///tmp/song.mp3"))
        }
    }

    @Test fun `multipart contains exactly one streamed file and no import fields`() = runBlocking {
        provider.size = null
        val body = buildReplacementBody(context, track, uri) as MultipartBody
        assertEquals(1, body.parts.size)
        val part = body.parts.single()
        assertEquals("form-data; name=\"file\"; filename=\"replacement.MP3\"",
            part.headers!!["Content-Disposition"])
        assertTrue(part.body.isOneShot())
        assertEquals(-1L, part.body.contentLength())
        val buffer = Buffer()
        part.body.writeTo(buffer)
        assertEquals("replacement audio", buffer.readUtf8())
    }

    @Test fun `unknown empty and changed content are rejected while streaming`(): Unit = runBlocking {
        provider.size = null
        provider.file.writeText("")
        val empty = buildReplacementBody(context, track, uri)
        assertThrows(IOException::class.java) { empty.writeTo(Buffer()) }

        provider.size = 10
        provider.file.writeText("changed")
        val changed = buildReplacementBody(context, track, uri)
        assertThrows(IOException::class.java) { changed.writeTo(Buffer()) }
    }

    @Test fun `unknown and understated sizes cannot bypass the streaming limit`() = runBlocking {
        RandomAccessFile(provider.file, "rw").use { it.setLength(REPLACEMENT_FILE_LIMIT + 1) }
        for (size in listOf(null, 1L)) {
            provider.size = size
            val body = buildReplacementBody(context, track, uri)
            blackholeSink().buffer().use { sink ->
                val error = assertThrows(IOException::class.java) { body.writeTo(sink) }
                assertTrue(error.message!!.contains("512 MB"))
            }
        }
    }
}

internal class ReplacementDocumentProvider(val file: File) : ContentProvider() {
    var size: Long? = file.length()
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?) =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf("replacement.MP3", size))
        }
    override fun openFile(uri: Uri, mode: String) =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    override fun getType(uri: Uri) = "audio/mpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
