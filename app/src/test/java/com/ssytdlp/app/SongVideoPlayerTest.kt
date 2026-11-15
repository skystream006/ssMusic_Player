package com.ssytdlp.app

import android.app.Application
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.view.descendants
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.User
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

    @Test fun videoIsMutedAndTracksPauseBufferingSpeedAndSeeksWithinTheDriftThreshold() {
        compose.runOnUiThread {
            val video = createSongVideoPlayer(compose.activity, api, account, stream)
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
            val video = createSongVideoPlayer(compose.activity, api, account, stream)
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
                    if (visible) SongVideoPlayer(PlaybackState(track = track), api, currentAccount, stream, null,
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
        compose.runOnIdle { currentAccount = account.copy(user = User("other")) }
        compose.runOnIdle { assertTrue(resumed.isReleased) }
        val replacement = compose.runOnIdle { playerView().player as ExoPlayer }
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("linked-song-video").assertDoesNotExist()
        compose.runOnIdle { assertTrue(replacement.isReleased) }
    }

    @Test fun videoSourcesCannotSendAccountCredentialsOutsideTheSelectedServer() {
        compose.runOnUiThread {
            listOf("https://other.example/api/video", "http://music.example/api/video", "/app-login").forEach {
                assertThrows(IllegalArgumentException::class.java) {
                    createSongVideoPlayer(compose.activity, api, account, it)
                }
            }
        }
    }

    private fun playerView(): PlayerView =
        compose.activity.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<PlayerView>().single()
}
