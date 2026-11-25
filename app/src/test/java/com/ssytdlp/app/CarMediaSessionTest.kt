package com.ssytdlp.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionError
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import com.ssytdlp.app.core.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
class CarMediaSessionTest {
    private val owner = Account("https://music.example", User("listener"), Session("test", "2100-01-01T00:00:00Z"))
    private var account: Account? = owner
    private val entry = "mix:/? &"
    private val library = Library(entries = listOf(LibraryEntry("folder", "folder", "Road"),
        LibraryEntry(entry, "playlist", "Original", "folder")), playlists = listOf(LibraryPlaylist(entry, "My Mix")))
    private val song = Track("job/?", "song.mp3", "First song", "Artist", streamUrl = "/api/stream/song.mp3")
    private val files = listOf(song, song.copy(mediaType = "video"), song.copy(isPlayable = false),
        song.copy(name = "[NoVocals]/song.mp3")) + (1..50).map { song.copy(name = "$it.mp3", title = "Song $it") } + song
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val car = controller("com.google.android.projection.gearhead", Process.myUid() + 1, true)
    private lateinit var api: ServerApi
    private lateinit var callback: CarMediaSessionCallback
    private lateinit var session: MediaLibrarySession

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val body = when (url.encodedPath) {
                "/api/library" -> ApiJson.encodeToString(library)
                "/api/library/tracks" -> {
                    val query = url.queryParameter("search").orEmpty()
                    val selected = files.filter { it.displayTitle.contains(query, true) || it.displayArtist.contains(query, true) }
                    ApiJson.encodeToString(TrackPage(files = selected))
                }
                else -> error("Unexpected request: ${url.encodedPath}")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody(ServerApi.JSON)).build()
        }.build()
        api = ServerApi({ account }, {}, client, UiActivityGate())
        callback = CarMediaSessionCallback(context, api, scope).apply { setAccount(account) }
        session = MediaLibrarySession.Builder(context, ExoPlayer.Builder(context).build(), callback).build()
    }

    @After fun teardown() {
        scope.cancel()
        session.player.release()
        session.release()
    }

    @Test fun `browse and cold play a duplicate on the last page in its authenticated playlist queue`() {
        val root = callback.onGetLibraryRoot(session, car, null).get().value!!
        val library = children(root).single { it.mediaMetadata.title == "Folders and playlists" }
        val playlist = children(children(library).single()).single { it.mediaMetadata.title == "My Mix" }
        val groups = children(playlist)
        assertEquals(listOf("1 - 50", "51 - 53"), groups.map { it.mediaMetadata.title })
        val last = children(groups.last()).last()
        assertNull(last.localConfiguration)
        assertNull(last.mediaMetadata.extras)
        assertEquals(true, last.mediaMetadata.isPlayable)
        assertEquals(false, last.mediaMetadata.isBrowsable)
        assertEquals("Artist", last.mediaMetadata.artist)
        assertNotEquals(children(groups.first()).first().mediaId, last.mediaId)

        callback.setAccount(owner) // Drop the in-memory browse snapshot.
        assertEquals(last, callback.onGetItem(session, car, last.mediaId).awaitResult().value)
        val foreign = song.copy(streamUrl = "/api/private/not-a-song").toMediaItem(api).buildUpon()
            .setMediaId(last.mediaId).setUri("https://other.example/untrusted").build()
        val queue = callback.onSetMediaItems(session, car, listOf(foreign), C.INDEX_UNSET, 5000).awaitResult()
        assertEquals(52, queue.mediaItems.size)
        assertEquals(51, queue.startIndex)
        assertEquals(5000L, queue.startPositionMs)
        assertEquals(song, queue.mediaItems.last().asTrack())
        assertEquals(owner.origin + song.streamUrl, queue.mediaItems.last().localConfiguration!!.uri.toString())
        assertEquals(listOf(queue.mediaItems.last()), callback.onAddMediaItems(session, car, listOf(foreign)).awaitResult())
        assertThrows(ExecutionException::class.java) {
            callback.onAddMediaItems(session, car, listOf(song.toMediaItem(api))).awaitResult()
        }
    }

    @Test fun `search supports paged results voice titles playlists and resume without a phone activity`() {
        val result = callback.onGetSearchResult(session, car, "Song 5", 1, 1, null).awaitResult().value!!
        assertEquals("Song 50", result.single().mediaMetadata.title)
        assertTrue(callback.onGetSearchResult(session, car, "Missing", Int.MAX_VALUE, Int.MAX_VALUE, null).awaitResult().value!!.isEmpty())
        fun voice(query: String, hints: Bundle? = null) = callback.onSetMediaItems(session, car, listOf(MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).setExtras(hints).build()).build()),
            C.INDEX_UNSET, C.TIME_UNSET).awaitResult()
        assertEquals("Song 5", voice("Song 5").mediaItems.first().mediaMetadata.title)
        val playlist = voice("my mix")
        assertEquals(52, playlist.mediaItems.size)
        assertThrows(ExecutionException::class.java) {
            voice("my mix", Bundle().apply { putString(MediaStore.EXTRA_MEDIA_TITLE, "my mix") })
        }
        assertThrows(ExecutionException::class.java) { voice("Missing") }
        session.player.setMediaItems(playlist.mediaItems, 7, 12_345)
        val resume = voice("")
        assertEquals(7, resume.startIndex)
        assertEquals(12_345L, resume.startPositionMs)
        assertFalse(session.player.playWhenReady)
    }

    @Test fun `signed out hosts get a sign-in error and untrusted callers cannot connect`() {
        assertTrue(callback.onConnect(session, car).isAccepted)
        assertFalse(callback.onConnect(session, controller("unknown.browser", Process.myUid() + 1, false)).isAccepted)
        account = null
        callback.setAccount(null)
        val root = callback.onGetLibraryRoot(session, car, null).get().value!!
        assertEquals(root, callback.onGetItem(session, car, root.mediaId).awaitResult().value)
        val result = callback.onGetChildren(session, car, root.mediaId, 0, Int.MAX_VALUE, null).awaitResult()
        assertEquals(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, result.resultCode)
        assertTrue(result.sessionError!!.message.contains("phone"))
        assertNull(result.value)
        account = owner
        callback.setAccount(owner)
        assertEquals(2, children(root).size)
    }

    private fun children(item: MediaItem) =
        callback.onGetChildren(session, car, item.mediaId, 0, Int.MAX_VALUE, null).awaitResult().value!!

    private fun controller(name: String, uid: Int, trusted: Boolean) =
        ControllerInfo.createTestOnlyControllerInfo(name, 123, uid, 0, 0, trusted, Bundle.EMPTY)

    private fun <T> ListenableFuture<T>.awaitResult(): T {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!isDone && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return get(1, TimeUnit.SECONDS)
    }
}
