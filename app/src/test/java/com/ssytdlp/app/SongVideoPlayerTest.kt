package com.ssytdlp.app

import android.app.Application
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.view.descendants
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.User
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class SongVideoPlayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account("https://music.example", User("owner"),
        Session("test", "2099-01-01T00:00:00Z"))
    private val api = ServerApi({ account }, {}, OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Test response")
            .body("Video unavailable".toResponseBody()).build()
    }.build())
    private val track = Track("source", "song.mp3", streamUrl = "/api/jobs/source/stream/song.mp3")
    private val stream = "/api/jobs/source/video/song.mp3?v=2"
    private lateinit var cache: AudioCache
    private val leases = mutableListOf<AudioCache.Handle>()

    @Before fun setup() {
        cache = AudioCache(compose.activity).apply { setAccount(account) }
    }

    @After fun cleanup() {
        leases.forEach { it.close() }
        cache.setAccount(null)
    }

    private fun lease() = cache.acquire(account, api.playbackCalls(account)).also { leases.add(it) }

    @Test fun videoIsMutedAndTracksPauseBufferingSpeedAndSeeksWithinTheDriftThreshold() {
        compose.runOnUiThread {
            val video = createSongVideoPlayer(compose.activity, lease(), account, stream)
            try {
                assertEquals(0f, video.volume)
                assertTrue(video.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
                val playing = PlaybackState(track = track, playing = true, position = 50_000, speed = 1.5f)
                synchronizeSongVideo(video, playing)
                assertEquals(50_000L, video.currentPosition)
                assertEquals(1.5f, video.playbackParameters.speed)
                assertTrue(video.playWhenReady)
                video.seekTo(49_800)
                synchronizeSongVideo(video, playing)
                assertEquals(49_800L, video.currentPosition)
                video.seekTo(49_799)
                synchronizeSongVideo(video, playing)
                assertEquals(50_000L, video.currentPosition)
                video.seekTo(49_950)
                synchronizeSongVideo(video, playing, forceSeek = true)
                assertEquals(50_000L, video.currentPosition)
                synchronizeSongVideo(video, playing.copy(buffering = true))
                assertFalse(video.playWhenReady)
                synchronizeSongVideo(video, playing.copy(playing = false, position = 12_345))
                assertEquals(12_345L, video.currentPosition)
                assertFalse(video.playWhenReady)
                synchronizeSongVideo(video, playing.copy(error = "Audio failed"))
                assertFalse(video.playWhenReady)
            } finally { video.release() }
        }
    }

    @Test fun synchronizationReadsTheLiveAudioClockAndNeverReplacesOrRestartsTheQueue() {
        compose.runOnUiThread {
            val audio = ExoPlayer.Builder(compose.activity).build()
            val video = createSongVideoPlayer(compose.activity, lease(), account, stream)
            val next = track.copy(name = "next.mp3", streamUrl = "/api/jobs/source/stream/next.mp3")
            try {
                audio.setMediaItems(listOf(track.toMediaItem(api), next.toMediaItem(api)))
                audio.seekTo(0, 42_000)
                audio.setPlaybackSpeed(1.25f)
                synchronizeSongVideo(video, PlaybackState(track = track, position = 1_000), audio)
                assertEquals(42_000L, video.currentPosition)
                assertEquals(1.25f, video.playbackParameters.speed)
                assertFalse(video.playWhenReady)
                assertEquals(42_000L, audio.currentPosition)
                assertEquals(0, audio.currentMediaItemIndex)
                assertEquals(listOf(track, next), (0 until audio.mediaItemCount).map { audio.getMediaItemAt(it).asTrack() })
                assertFalse(audio.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
                video.playWhenReady = true
                audio.seekTo(1, 3_000)
                synchronizeSongVideo(video, PlaybackState(track = track), audio)
                assertFalse(video.playWhenReady)
                assertEquals(42_000L, video.currentPosition)
                synchronizeSongVideo(video, PlaybackState(track = next), audio, forceSeek = true)
                assertEquals(3_000L, video.currentPosition)
                assertEquals(next, audio.currentMediaItem!!.asTrack())
                assertEquals(3_000L, audio.currentPosition)
            } finally { video.release(); audio.release() }
        }
    }

    @Test fun videoResourcesAreReleasedOnBackgroundDisplayChangesAndAccountChanges() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle = registry
        }
        var visible by mutableStateOf(true)
        var currentAccount by mutableStateOf(account)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    if (visible) SongVideoPlayer(PlaybackState(track = track), api, currentAccount, stream, null, cache,
                        Modifier.size(300.dp, 180.dp))
                }
            }
        }
        val first = compose.runOnIdle { playerView().player as ExoPlayer }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertNull(playerView().player); assertTrue(first.isReleased) }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        val resumed = compose.runOnIdle { playerView().player as ExoPlayer }
        assertNotSame(first, resumed)
        compose.runOnIdle {
            currentAccount = account.copy(user = User("other"))
            cache.setAccount(currentAccount)
        }
        compose.runOnIdle { assertTrue(resumed.isReleased) }
        val replacement = compose.runOnIdle { playerView().player as ExoPlayer }
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("linked-song-video").assertDoesNotExist()
        compose.runOnIdle { assertTrue(replacement.isReleased) }
    }

    @Test fun linkedVideoFullscreenReusesPlayerAndStillReleasesResourcesInBackground() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle = registry
        }
        var currentAccount by mutableStateOf(account)
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    SongVideoPlayer(PlaybackState(track = track, position = 12_345), api, currentAccount,
                        stream, null, cache, Modifier.size(300.dp, 180.dp))
                }
            }
        }
        val first = compose.runOnIdle { playerView().player as ExoPlayer }
        compose.onNodeWithContentDescription("Expand video to full screen").performClick()
        compose.onNodeWithTag("fullscreen-video").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(first, fullscreenPlayerView().player)
            assertFalse(first.isReleased)
            assertEquals(12_345L, first.currentPosition)
            assertEquals(0f, first.volume)
            assertFalse(compose.activity.findViewById<ViewGroup>(android.R.id.content)
                .descendants.filterIsInstance<PlayerView>().any())
        }
        compose.onNodeWithContentDescription("Exit full screen video").performClick()
        compose.onNodeWithTag("fullscreen-video").assertDoesNotExist()
        compose.runOnIdle { assertSame(first, playerView().player); assertFalse(first.isReleased) }
        compose.onNodeWithContentDescription("Expand video to full screen").performClick()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertTrue(first.isReleased); assertNull(fullscreenPlayerView().player) }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        val resumed = compose.runOnIdle { fullscreenPlayerView().player as ExoPlayer }
        assertNotSame(first, resumed)
        compose.runOnIdle {
            currentAccount = account.copy(user = User("other"))
            cache.setAccount(currentAccount)
        }
        compose.onNodeWithTag("fullscreen-video").assertDoesNotExist()
        compose.runOnIdle { assertTrue(resumed.isReleased) }
        compose.onNodeWithContentDescription("Expand video to full screen").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [26, 34], qualifiers = "w800dp-h360dp-land")
    fun standaloneVideoFullscreenFillsWindowAndBackRestoresTheSameQueueAndPosition() {
        lateinit var video: ExoPlayer
        val videoTrack = track.copy(mediaType = "video")
        compose.runOnUiThread {
            video = ExoPlayer.Builder(compose.activity).build()
            video.setMediaItem(videoTrack.toMediaItem(api))
            video.seekTo(23_456)
        }
        try {
            compose.setContent {
                MaterialTheme {
                    PlayerArtwork(PlaybackState(track = videoTrack), null, Modifier.size(300.dp, 180.dp),
                        controller = video)
                }
            }
            compose.onNodeWithContentDescription("Expand video to full screen").performClick()
            compose.onNodeWithContentDescription("Exit full screen video").assertIsDisplayed()
            val fullscreenBounds = compose.onNodeWithTag("fullscreen-video").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val window = ShadowDialog.getLatestDialog().window!!
                assertSame(video, fullscreenPlayerView().player)
                assertEquals(window.decorView.width.toFloat(), fullscreenBounds.width, 1f)
                assertEquals(window.decorView.height.toFloat(), fullscreenBounds.height, 1f)
                assertTrue(fullscreenBounds.width > 300 * compose.activity.resources.displayMetrics.density)
            }
            compose.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
            compose.onNodeWithTag("fullscreen-video").assertDoesNotExist()
            compose.onNodeWithContentDescription("Expand video to full screen").assertIsDisplayed()
            compose.runOnIdle {
                assertSame(video, playerView().player)
                assertFalse(video.isReleased)
                assertEquals(23_456L, video.currentPosition)
                assertEquals(1, video.mediaItemCount)
                assertEquals(videoTrack, video.currentMediaItem!!.asTrack())
            }
        } finally {
            compose.runOnUiThread { video.release() }
        }
    }

    @Test fun videoSourcesCannotSendAccountCredentialsOutsideTheSelectedServer() {
        compose.runOnUiThread {
            listOf("https://other.example/api/video", "http://music.example/api/video", "/app-login").forEach {
                assertThrows(IllegalArgumentException::class.java) {
                    createSongVideoPlayer(compose.activity, lease(), account, it)
                }
            }
        }
    }

    @Test fun linkedVideoFillsTheSharedCacheAndReopensWithoutNetworkRequests() {
        val samples = 8_000
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples * 2)
        }.array()
        val requests = AtomicInteger()
        val videoApi = ServerApi({ account }, {}, OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            val start = chain.request().header("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (start == 0) 200 else 206).message("Video")
                .header("Content-Type", "audio/wav")
                .apply { if (start > 0) header("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}") }
                .body(bytes.copyOfRange(start, bytes.size).toResponseBody()).build()
        }.build())
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible) SongVideoPlayer(PlaybackState(track = track, playing = true), videoApi, account,
                    stream, null, cache, Modifier.size(300.dp, 180.dp))
            }
        }
        compose.waitUntil(10_000) { runBlocking { cache.usedBytes() } == bytes.size.toLong() }
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("linked-song-video").assertDoesNotExist()
        compose.waitUntil(5_000) {
            File(compose.activity.noBackupFilesDir, "audio").listFiles().orEmpty()
                .all { !SimpleCache.isCacheFolderLocked(it) }
        }
        val downloads = requests.get()
        assertTrue(downloads > 0)
        cache = AudioCache(compose.activity).apply { setAccount(account) }
        val shared = lease()
        val source = shared.factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(account.origin + stream).build())
            val cached = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                cached.write(buffer, 0, count)
            }
            assertArrayEquals(bytes, cached.toByteArray())
        } finally {
            source.close()
            shared.close()
        }
        compose.runOnIdle { visible = true }
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                val video = playerView().player
                video?.playbackState == Player.STATE_READY || video?.playbackState == Player.STATE_ENDED
            }
        }
        compose.runOnIdle { assertNull(playerView().player!!.playerError) }
        assertEquals(downloads, requests.get())
    }

    private fun playerView(): PlayerView =
        compose.activity.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<PlayerView>().single()

    private fun fullscreenPlayerView(): PlayerView =
        (ShadowDialog.getLatestDialog().window!!.decorView as ViewGroup)
            .descendants.filterIsInstance<PlayerView>().single()
}
