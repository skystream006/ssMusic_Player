package com.ssytdlp.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.User
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackPersistenceTest {
    private lateinit var context: Context
    private lateinit var store: PlaybackStore
    private lateinit var player: ExoPlayer
    private var persistence: PlaybackPersistence? = null
    private val account = Account("https://music.example", User(id = "listener"),
        Session("T".repeat(43), "2100-01-01T00:00:00Z"))
    private val api = ServerApi({ account }, {})
    private val first = Track(jobId = "job", name = "first.mp3", streamUrl = "/api/stream/first.mp3")
    private val second = Track(jobId = "job", name = "second.mp4", streamUrl = "/api/stream/second.mp4",
        mediaType = "video")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
        store = PlaybackStore(context).apply { setAccount(account) }
        player = ExoPlayer.Builder(context).build()
    }

    @After fun teardown() {
        persistence?.close()
        player.release()
    }

    @Test fun `restores the exact queue item and timestamp paused without requesting media`() {
        val saved = SavedPlayback(listOf(first, second, first), 2, 87_654, true, Player.REPEAT_MODE_ONE)
        store.savePlayback(account, saved)
        attach()

        assertEquals(saved.queue, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).asTrack() })
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals(87_654L, player.currentPosition)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertFalse(player.playWhenReady)
        assertTrue(player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
    }

    @Test fun `seek queue edits transitions and playback modes are saved by player events`() {
        attach()
        player.setMediaItems(listOf(first, second).map { it.toMediaItem(api) }, 1, 32_100)
        player.shuffleModeEnabled = true
        player.repeatMode = Player.REPEAT_MODE_ALL
        flushEvents()
        assertEquals(SavedPlayback(listOf(first, second), 1, 32_100, true, Player.REPEAT_MODE_ALL),
            store.playback(account))

        player.seekTo(45_678)
        player.addMediaItem(first.toMediaItem(api))
        player.moveMediaItem(2, 0)
        player.removeMediaItem(1)
        flushEvents()
        assertEquals(SavedPlayback(listOf(first, second), 1, 45_678, true, Player.REPEAT_MODE_ALL),
            store.playback(account))

        player.seekTo(0, 12_345)
        flushEvents()
        assertEquals(0, store.playback(account)!!.index)
        assertEquals(12_345L, store.playback(account)!!.position)
    }

    @Test fun `shutdown checkpoints before release and survives a new player`() {
        attach()
        player.setMediaItems(listOf(first, second).map { it.toMediaItem(api) }, 1, 76_543)
        persistence!!.close()
        persistence = null
        player.release()

        store = PlaybackStore(context).apply { setAccount(account) }
        player = ExoPlayer.Builder(context).build()
        attach()
        assertEquals(second, player.currentMediaItem!!.asTrack())
        assertEquals(76_543L, player.currentPosition)
        assertFalse(player.playWhenReady)
    }

    @Test fun `reconnection never replaces or rewinds a live queue`() {
        store.savePlayback(account, SavedPlayback(listOf(first), position = 10_000))
        player.setMediaItems(listOf(second.toMediaItem(api)), 0, 55_555)
        player.playWhenReady = true
        attach()
        assertEquals(second, player.currentMediaItem!!.asTrack())
        assertEquals(55_555L, player.currentPosition)
        assertTrue(player.playWhenReady)
    }

    @Test fun `unplayable and foreign resources are skipped while retaining the selected item position`() {
        val foreign = first.copy(streamUrl = "https://other.example/api/stream/first.mp3")
        store.savePlayback(account, SavedPlayback(listOf(first.copy(streamUrl = null), foreign, second),
            2, 9_876))
        attach()
        assertEquals(listOf(second), store.playback(account)!!.queue)
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(9_876L, player.currentPosition)
    }

    @Test fun `invalid current item falls forward without applying its timestamp to another track`() {
        store.savePlayback(account, SavedPlayback(listOf(first.copy(isPlayable = false), second), 0, 9_876))
        attach()
        assertEquals(second, player.currentMediaItem!!.asTrack())
        assertEquals(0L, player.currentPosition)
    }

    @Test fun `invalid indices positions and repeat modes are normalized`() {
        store.savePlayback(account, SavedPlayback(listOf(first, second), 100, -1, repeat = 99))
        attach()
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(0L, player.currentPosition)
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
    }

    @Test fun `empty and wholly invalid queues remain empty`() {
        store.savePlayback(account, SavedPlayback(listOf(first.copy(streamUrl = null)), 100, 9_876))
        attach()
        assertEquals(0, player.mediaItemCount)
        assertTrue(store.playback(account)!!.queue.isEmpty())
    }

    @Test fun `removing all media persists an empty queue`() {
        store.savePlayback(account, SavedPlayback(listOf(first), position = 9_876))
        attach()
        player.clearMediaItems()
        flushEvents()
        assertTrue(store.playback(account)!!.queue.isEmpty())
    }

    @Test fun `sign out prevents late checkpoints from restoring private playback`() {
        store.savePlayback(account, SavedPlayback(listOf(first), position = 9_876))
        attach()
        store.setAccount(null)
        persistence!!.save(synchronous = true)
        player.clearMediaItems()
        flushEvents()
        store.setAccount(account)
        assertNull(store.playback(account))
    }

    @Test fun `pause and shutdown save position without waiting for the coarse checkpoint`() {
        assertTrue(PLAYBACK_CHECKPOINT_INTERVAL_MS >= 30_000L)
        attach()
        player.setMediaItems(listOf(first.toMediaItem(api)), 0, 15_000)
        player.playWhenReady = true
        flushEvents()
        player.seekTo(17_500)
        player.pause()
        flushEvents()
        assertEquals(17_500L, store.playback(account)!!.position)
        persistence!!.close()
        persistence = null
        assertEquals(17_500L, PlaybackStore(context).apply { setAccount(account) }.playback(account)!!.position)
    }

    @Test fun `replacement refreshes every queued copy and persistence without resuming paused playback`() {
        attach()
        player.setMediaItems(listOf(first, second, first).map { it.toMediaItem(api) }, 2, 12_345)
        player.shuffleModeEnabled = true
        player.repeatMode = Player.REPEAT_MODE_ALL
        val replacement = first.copy(title = "Replacement", streamUrl = "/api/stream/first.mp3?v=2")
        player.replaceSongFile(replacement, api)
        flushEvents()
        assertEquals(listOf(replacement, second, replacement),
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).asTrack() })
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals(0L, player.currentPosition)
        assertFalse(player.playWhenReady)
        assertTrue(player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        assertEquals(api.url(replacement.streamUrl!!), player.currentMediaItem!!.localConfiguration!!.uri.toString())
        assertEquals(listOf(replacement, second, replacement), store.playback(account)!!.queue)
    }

    @Test fun `replacing another song preserves current position and playback intent`() {
        player.setMediaItems(listOf(first, second).map { it.toMediaItem(api) }, 1, 12_345)
        player.playWhenReady = true
        player.replaceSongFile(first.copy(streamUrl = "/api/stream/first.mp3?v=2"), api)
        assertEquals(second, player.currentMediaItem!!.asTrack())
        assertEquals(12_345L, player.currentPosition)
        assertTrue(player.playWhenReady)
    }

    @Test fun `replacing current audio preserves play intent and refreshing nested karaoke does not rewind`() {
        val instrumental = first.copy(name = "[NoVocals]/first.mp3", streamUrl = "/api/stream/karaoke?v=1")
        player.setMediaItems(listOf(first.copy(noVocalsVersion = instrumental).toMediaItem(api)), 0, 12_345)
        player.playWhenReady = true
        val karaoke = instrumental.copy(streamUrl = "/api/stream/karaoke?v=2")
        player.replaceSongFile(karaoke, api)
        assertEquals(karaoke, player.currentMediaItem!!.asTrack()!!.noVocalsVersion)
        assertEquals(12_345L, player.currentPosition)
        player.replaceSongFile(first.copy(streamUrl = "/api/stream/first.mp3?v=2"), api)
        assertEquals(0L, player.currentPosition)
        assertTrue(player.playWhenReady)
        assertEquals(karaoke, player.currentMediaItem!!.asTrack()!!.noVocalsVersion)
    }

    private fun attach() {
        persistence = PlaybackPersistence(player, store, account) { it.toMediaItem(api) }
    }

    private fun flushEvents() { shadowOf(Looper.getMainLooper()).idle() }
}
