package com.ssytdlp.app

import android.app.Application
import android.content.Context
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.User
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaybackStoreTest {
    private lateinit var context: Context
    private lateinit var store: PlaybackStore
    private val account = Account("https://music.example", User(id = "listener"),
        Session("T".repeat(43), "2100-01-01T00:00:00Z"))
    private val track = Track(jobId = "job", name = "song.mp3", playlistId = "playlist",
        streamUrl = "/api/jobs/job/stream/song.mp3")
    private val snapshot = SavedPlayback(listOf(track, track.copy(name = "video.mp4", mediaType = "video"), track),
        index = 2, position = 42_123, shuffle = true, repeat = Player.REPEAT_MODE_ALL)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
        store = PlaybackStore(context).apply { setAccount(account) }
    }

    @Test fun `playlist queue duplicates index and timestamp survive store recreation`() {
        store.saveSelectedLibrary(account, "playlist")
        store.savePlayback(account, snapshot, synchronous = true)

        val reopened = PlaybackStore(context).apply { setAccount(account) }
        assertEquals("playlist", reopened.selectedLibrary(account))
        assertEquals(snapshot, reopened.playback(account))
        assertFalse(context.getSharedPreferences("playback", Context.MODE_PRIVATE).all.toString()
            .contains(account.session.token))
    }

    @Test fun `browsing and queue snapshots do not overwrite each other`() {
        store.savePlayback(account, snapshot)
        store.saveSelectedLibrary(account, "folder")
        store.savePlayback(account, snapshot.copy(position = 60_000))
        assertEquals("folder", store.selectedLibrary(account))
        assertEquals(60_000L, store.playback(account)!!.position)

        store.saveSelectedLibrary(account, null)
        assertNull(PlaybackStore(context).selectedLibrary(account))
        assertEquals(snapshot.queue, store.playback(account)!!.queue)
    }

    @Test fun `same user renewing a session retains saved playback`() {
        store.saveSelectedLibrary(account, "playlist")
        store.savePlayback(account, snapshot)
        val renewed = account.copy(session = account.session.copy(token = "N".repeat(43)))
        store.setAccount(renewed)
        assertEquals("playlist", store.selectedLibrary(renewed))
        assertEquals(snapshot, store.playback(renewed))
    }

    @Test fun `sign out clears selection and blocks late service writes`() {
        store.saveSelectedLibrary(account, "playlist")
        store.savePlayback(account, snapshot)
        store.setAccount(null)
        store.savePlayback(account, snapshot)
        store.saveSelectedLibrary(account, "stale")
        assertTrue(context.getSharedPreferences("playback", Context.MODE_PRIVATE).all.values.all { it == null })

        store.setAccount(account)
        assertNull(store.selectedLibrary(account))
        assertNull(store.playback(account))
    }

    @Test fun `different users and servers cannot restore or overwrite another queue`() {
        for (other in listOf(account.copy(user = User(id = "other")),
            account.copy(origin = "https://other.example"))) {
            store.setAccount(account)
            store.saveSelectedLibrary(account, "playlist")
            store.savePlayback(account, snapshot)
            assertNull(store.selectedLibrary(other))
            assertNull(store.playback(other))

            store.setAccount(other)
            store.savePlayback(account, snapshot)
            store.saveSelectedLibrary(account, "stale")
            assertNull(store.selectedLibrary(other))
            assertNull(store.playback(other))
            store.setAccount(account)
            assertNull(store.playback(account))
        }
    }

    @Test fun `malformed snapshots are discarded without losing playlist selection`() {
        store.saveSelectedLibrary(account, "playlist")
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit()
            .putString("snapshot", "{broken").commit()
        assertNull(store.playback(account))
        assertEquals("playlist", store.selectedLibrary(account))
        assertFalse(context.getSharedPreferences("playback", Context.MODE_PRIVATE).contains("snapshot"))
    }

    @Test fun `clearing the queue replaces rather than resurrects the old snapshot`() {
        store.savePlayback(account, snapshot)
        store.savePlayback(account, SavedPlayback(), synchronous = true)
        assertEquals(SavedPlayback(), PlaybackStore(context).playback(account))
    }
}
