package com.ssytdlp.app

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.User
import java.io.ByteArrayOutputStream
import java.util.Collections
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackArtworkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = mutableStateOf<Account?>(Account("https://music.example", User("listener", role = "shared"),
        Session("test", "2099-01-01T00:00:00Z")))
    private val track = Track("source", "song.mp3", title = "Song", rating = 3,
        artworkUrl = "/api/jobs/source/artwork/song.mp3?v=1")
    private val paths = Collections.synchronizedList(mutableListOf<String>())
    private val gate = UiActivityGate()
    private val activity = Any()

    private fun api(): ServerApi {
        val image = imageBytes()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath + "?" + request.url.encodedQuery
            paths.add(path)
            val missing = request.url.queryParameter("v") == "missing"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (missing) 404 else 200).message("Test artwork")
                .body((if (missing) ByteArray(0) else image).toResponseBody()).build()
        }.build()
        return ServerApi({ account.value }, {}, client, gate)
    }

    @Test fun thumbnailDecoderAcceptsWebpAndRejectsInvalidOrUnboundedImages() {
        val bitmap = decodeTrackArtwork(imageBytes())
        assertNotNull(bitmap)
        assertEquals(192, bitmap!!.width)
        assertEquals(192, bitmap.height)
        assertNotNull(decodeTrackArtwork(imageBytes(96)))
        assertNull(decodeTrackArtwork(ByteArray(0)))
        assertNull(decodeTrackArtwork("invalid image".toByteArray()))
        assertNull(decodeTrackArtwork(ByteArray(64 * 1024 + 1)))
        assertNull(decodeTrackArtwork(imageBytes(193, 192)))
        assertNull(decodeTrackArtwork(imageBytes(192, 193)))
    }

    @Test fun artworkDecoderPreservesLegacyFormatsAndAlbumDownsampling() {
        listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP_LOSSLESS).forEach { format ->
            assertNotNull(decodeTrackArtwork(imageBytes(format = format)))
            val album = decodeArtworkBitmap(imageBytes(2048, 1024, format))
            assertNotNull(album)
            assertEquals(1024, album!!.width)
            assertEquals(512, album.height)
            album.recycle()
        }
        assertNull(decodeArtworkBitmap(ByteArray(2 * 1024 * 1024 + 1)))
    }

    @Test fun libraryLoadsArtworkWithoutMetadataReadsAndKeepsPlaybackRatingAndHiddenGroups() {
        val api = api()
        val missing = track.copy(name = "missing.mp3", title = "Missing",
            artworkUrl = "/api/jobs/source/artwork/missing.mp3?v=missing")
        val video = track.copy(name = "video.mp4", title = "Video", mediaType = "video",
            artworkUrl = "/api/jobs/source/artwork/video.mp4?v=1")
        val legacy = track.copy(name = "legacy.mp3", title = "Legacy", artworkUrl = null)
        val instrumental = track.copy(name = "[NoVocals]/song.mp3", title = "Instrumental",
            artworkUrl = "/api/jobs/source/artwork/%5BNoVocals%5D%2Fsong.mp3?v=1")
        val tracks = listOf(track, missing, video, legacy, instrumental)
        var played = -1
        var rated: Track? = null
        gate.activityResumed(activity)
        compose.setContent {
            MusicTheme {
                LibraryContent(LibraryState(tracks = TrackPage(files = tracks)),
                    PlaybackState(connected = true, track = track), { played = it }, {},
                    onRating = { rated = it }, artwork = { rememberTrackArtwork(it, api, account.value) }) { _, _ -> }
            }
        }
        waitForArtwork("Song")
        compose.onNodeWithContentDescription("Album artwork for Song", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Song").performClick()
        assertEquals(0, played)
        compose.onAllNodesWithContentDescription("Rating: 3 out of 5")[0].performClick()
        assertEquals(track, rated)
        waitForArtwork("Video")
        compose.waitUntil(5_000) { paths.size == 3 }
        assertTrue(paths.all { it.contains("/artwork/") })
        compose.onNodeWithContentDescription("Album artwork for Missing", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Album artwork for Video", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand NoVocals").performClick()
        waitForArtwork("Instrumental")
        compose.onNodeWithText("Instrumental").performClick()
        assertEquals(4, played)
        assertTrue(paths.contains(instrumental.artworkUrl))
    }

    @Test fun changingRevisionOrAccountClearsOldArtworkWhileTheNewReadWaits() {
        val api = api()
        val current = mutableStateOf(track)
        gate.activityResumed(activity)
        compose.setContent {
            MusicTheme {
                TrackRow(current.value, artwork = { rememberTrackArtwork(current.value, api, account.value) }, onClick = {})
            }
        }
        waitForArtwork("Song")
        compose.runOnIdle {
            gate.activityPaused(activity)
            current.value = track.copy(artworkUrl = "/api/jobs/source/artwork/song.mp3?v=missing")
        }
        compose.onNodeWithContentDescription("Album artwork for Song", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { gate.activityResumed(activity) }
        compose.waitUntil(5_000) { paths.size == 2 }
        compose.onNodeWithContentDescription("Album artwork for Song", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { current.value = track.copy(artworkUrl = "/api/jobs/source/artwork/song.mp3?v=2") }
        waitForArtwork("Song")
        compose.runOnIdle {
            gate.activityPaused(activity)
            account.value = account.value!!.copy(user = User("different"))
        }
        compose.onNodeWithContentDescription("Album artwork for Song", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { gate.activityResumed(activity) }
        waitForArtwork("Song")
        compose.runOnIdle { account.value = null }
        compose.onNodeWithContentDescription("Album artwork for Song", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun compactRowsDoNotFetchInvisibleArtwork() {
        val api = api()
        gate.activityResumed(activity)
        compose.setContent {
            MusicTheme {
                Box(Modifier.width(220.dp)) {
                    TrackRow(track, artwork = { rememberTrackArtwork(track, api, account.value) }, onClick = {})
                }
            }
        }
        compose.onNodeWithText("Song").assertIsDisplayed()
        compose.runOnIdle { assertTrue(paths.isEmpty()) }
    }

    private fun waitForArtwork(title: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Album artwork for $title", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun imageBytes(width: Int = 192, height: Int = width,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP_LOSSLESS): ByteArray = ByteArrayOutputStream().use { output ->
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(android.graphics.Color.BLUE)
            image.compress(format, 100, output)
            output.toByteArray()
        } finally { image.recycle() }
    }
}
