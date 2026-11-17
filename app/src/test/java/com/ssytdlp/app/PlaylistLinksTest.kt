package com.ssytdlp.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.*
import java.security.Provider
import java.security.Security
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class PlaylistLinksTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val provider = object : Provider("PlaylistLinksKeyStore", 1.0, "Empty test session keystore") {}
    private val models = ViewModelStore()
    private lateinit var application: MusicApplication
    private lateinit var model: MusicViewModel
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private val response = AtomicReference(200 to """{"playlists":[],"ownerId":"owner"}""")
    private val gate = AtomicReference<CountDownLatch?>(null)
    private val track = Track("job", "song.mp3", title = "Song", libraryOwnerId = "owner")
    private val account get() =
        ReflectionHelpers.getField<MutableStateFlow<Account?>>(application.sessions, "mutableAccount")

    @Before fun setup() {
        provider.put("KeyStore.AndroidKeyStore", Security.getProvider("SUN").getService("KeyStore", "JKS").className)
        Security.addProvider(provider)
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf("private_session", "playback", "settings").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        application = MusicApplication()
        ReflectionHelpers.callInstanceMethod<Unit>(application, "attach", ClassParameter.from(Context::class.java, context))
        application.onCreate()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            val (status, body) = response.get()
            gate.get()?.let { check(it.await(10, TimeUnit.SECONDS)) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("Test response").body(body.toResponseBody()).build()
        }.build()
        ReflectionHelpers.setField(application, "api", ServerApi({ account.value }, {}, client))
        compose.runOnUiThread {
            model = MusicViewModel(application, connectPlayback = false)
            models.put("links", model)
            model.viewModelScope.cancel()
            account.value = Account("https://music.example:8443", User("reader", role = "shared"),
                Session("test", "2099-01-01T00:00:00Z"))
        }
    }

    @After fun cleanup() {
        gate.get()?.countDown()
        compose.runOnUiThread { models.clear() }
        Security.removeProvider(provider.name)
    }

    @Test fun libraryAndQueueMenusOfferAudioAndVideoLinksWithoutEditPermissions() {
        val file = mutableStateOf(track)
        val queue = mutableStateOf(false)
        compose.setContent { MusicTheme {
            TrackMenu(model, file.value, 0, onRemoveFromQueue = if (queue.value) ({}) else null) { _, _ -> }
        } }
        compose.onNodeWithContentDescription("Options for Song").performClick()
        for (inQueue in listOf(false, true)) {
            for (media in listOf("audio", "video")) {
                compose.runOnIdle {
                    queue.value = inQueue
                    file.value = track.copy(mediaType = media, name = if (media == "video") "movie.mp4" else "song.mp3")
                }
                compose.onNodeWithText("Linked Playlists").assertIsEnabled()
            }
        }
        compose.runOnIdle { file.value = track.copy(isPlayable = false, name = "notes.txt", mediaType = "text") }
        compose.onNodeWithText("Linked Playlists").assertDoesNotExist()
        assertTrue(requests.isEmpty())
    }

    @Test fun miniPlayerOpensLinksAndClosesItsMenu() {
        compose.setContent { MusicTheme {
            PlayerDock(PlaybackState(track = track), model, {}, {}) { _, _ -> }
        } }
        compose.onNodeWithContentDescription("More song actions").performClick()
        compose.onNodeWithText("Linked Playlists").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        waitForText("No playlist links found.")
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Playlist Links").assertDoesNotExist()
    }

    @Test fun nowPlayingOpensLinksForTheSelectedSong() {
        compose.setContent { MusicTheme {
            NowPlayingScreen(model, PlaybackState(track = track)) { _, _ -> }
        } }
        compose.onNodeWithContentDescription("More song actions").performClick()
        compose.onNodeWithText("Linked Playlists").performClick()
        waitForText("No playlist links found.")
        assertEquals(track.name, requests.single().url.queryParameter("name"))
    }

    @Test fun replacementPreservesTheLibraryOwner() {
        val replaced = track.withReplacedFile(track.copy(libraryOwnerId = null, title = "Replacement"))
        assertEquals("owner", replaced.libraryOwnerId)
        assertEquals("Replacement", replaced.title)
    }

    @Test fun displaysLoadingThenSortedLinksAndOpensTheReturnedOwnersPlaylist() {
        gate.set(CountDownLatch(1))
        response.set(200 to """{"playlists":[{"id":"z","title":"Zulu"},{"id":"mix /?#","title":"Alpha"}],"ownerId":"owner /&"}""")
        var opened: String? = null
        compose.setContent { MusicTheme {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened = uri }
            }) { PlaylistLinksDialog(model, track) {} }
        } }
        compose.onNodeWithText("Loading playlists...").assertExists()
        gate.get()!!.countDown()
        waitForText("Alpha")
        val alpha = compose.onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot
        val zulu = compose.onNodeWithText("Zulu").fetchSemanticsNode().boundsInRoot
        assertTrue(alpha.top < zulu.top)
        compose.onNodeWithText("Alpha").performClick()
        assertEquals("https://music.example:8443/?playlist=mix%20%2F%3F%23&userId=owner%20%2F%26", opened)
        assertEquals("owner", requests.single().url.queryParameter("userId"))
    }

    @Test fun requestErrorsAreNotEmptyResultsAndCanBeRetried() {
        response.set(404 to """{"error":"Playlist links unavailable"}""")
        compose.setContent { MusicTheme { PlaylistLinksDialog(model, track) {} } }
        waitForText("Playlist links unavailable")
        compose.onNodeWithText("No playlist links found.").assertDoesNotExist()
        response.set(200 to """{"playlists":[],"ownerId":"owner"}""")
        compose.onNodeWithText("Retry").performClick()
        waitForText("No playlist links found.")
        compose.onNodeWithText("Retry").assertDoesNotExist()
        assertEquals(2, requests.size)
    }

    @Test fun unavailableBrowserShowsAnErrorWithoutLosingTheList() {
        response.set(200 to """{"playlists":[{"id":"mix","title":"My mix"}],"ownerId":"owner"}""")
        compose.setContent { MusicTheme {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { throw IllegalArgumentException("No browser available") }
            }) { PlaylistLinksDialog(model, track) {} }
        } }
        waitForText("My mix")
        compose.onNodeWithText("My mix").performClick()
        compose.onNodeWithText("No browser available").assertExists()
        compose.onNodeWithText("My mix").assertExists()
    }

    @Test fun accountChangesDismissThePendingDialogAndDiscardItsResponse() {
        gate.set(CountDownLatch(1))
        response.set(200 to """{"playlists":[{"id":"old","title":"Old account playlist"}],"ownerId":"owner"}""")
        compose.setContent { MusicTheme { TrackMenu(model, track, 0) { _, _ -> } } }
        compose.onNodeWithContentDescription("Options for Song").performClick()
        compose.onNodeWithText("Linked Playlists").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            requests.isNotEmpty()
        }
        compose.runOnIdle { account.value = account.value!!.copy(user = User("another")) }
        gate.get()!!.countDown()
        compose.onNodeWithText("Playlist Links").assertDoesNotExist()
        compose.onNodeWithText("Old account playlist").assertDoesNotExist()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
