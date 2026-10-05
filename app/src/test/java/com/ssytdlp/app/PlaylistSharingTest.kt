package com.ssytdlp.app

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.*
import java.security.Provider
import java.security.Security
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
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
class PlaylistSharingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val provider = object : Provider("PlaylistSharingKeyStore", 1.0, "Empty test session keystore") {}
    private val models = ViewModelStore()
    private lateinit var application: MusicApplication
    private lateinit var model: MusicViewModel
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private val owner = User("owner")
    private val entry = LibraryEntry("playlist /?#", "playlist", "Playlist")
    private val playlist = LibraryPlaylist(entry.id, "Playlist", songCount = 2, initiatedBy = owner,
        contributors = listOf(User("contributor")))
    private val sharePath = "/api/library/playlists/playlist%20%2F%3F%23/share"
    private val publicPath = "/share/playlist/" + "p".repeat(43)
    private val origin = "https://music.example:8443"
    private val response = AtomicReference(201 to json("url" to publicPath).toString())
    private val account get() =
        ReflectionHelpers.getField<MutableStateFlow<Account?>>(application.sessions, "mutableAccount")
    private val library get() =
        ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")

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
            val request = chain.request()
            requests.add(request)
            val (status, body) = response.get()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Test response").body(body.toResponseBody()).build()
        }.build()
        ReflectionHelpers.setField(application, "api", ServerApi({ account.value }, {}, client))
        compose.runOnUiThread {
            model = MusicViewModel(application, connectPlayback = false)
            models.put("sharing", model)
            model.viewModelScope.cancel()
            account.value = testAccount(owner)
            setPlaylist(playlist)
        }
    }

    @After fun cleanup() {
        compose.runOnUiThread { models.clear() }
        Security.removeProvider(provider.name)
    }

    @Test fun playlistMenuRespectsPermissionsAndNeverAddsSharingToFolders() {
        val currentEntry = mutableStateOf(entry)
        compose.setContent { MusicTheme { EntryMenu(model, currentEntry.value) } }
        compose.onNodeWithContentDescription("Options for Playlist").performClick()
        for (user in listOf(owner, User("contributor"), User("admin", role = "admin"),
            User("other"), owner.copy(role = "shared"), null)) {
            compose.runOnIdle { account.value = user?.let(::testAccount) }
            if (user != null && user.id in listOf("owner", "contributor", "admin") && !user.isShared) {
                compose.onNodeWithText("Share Playlist").assertIsEnabled()
            } else compose.onNodeWithText("Share Playlist").assertDoesNotExist()
        }
        compose.runOnIdle {
            account.value = testAccount(owner)
            currentEntry.value = entry.copy(type = "folder")
        }
        compose.onNodeWithText("Share Playlist").assertDoesNotExist()
        compose.onNodeWithText("Rename").assertExists()
        assertTrue(requests.isEmpty())
    }

    @Test fun privateEmptyAndBusyPlaylistsCannotGenerateLinksButProtectedAndActiveOnesCan() {
        compose.setContent { MusicTheme { EntryMenu(model, entry.copy(protected = true)) } }
        compose.onNodeWithContentDescription("Options for Playlist").performClick()
        compose.onNodeWithText("Share Playlist").assertIsEnabled()
        for (value in listOf(playlist.copy(isPrivate = true), playlist.copy(songCount = 0))) {
            compose.runOnIdle { setPlaylist(value) }
            compose.onNodeWithText("Share Playlist").assertIsNotEnabled()
        }
        for (status in listOf("completed", "queued", "running")) {
            compose.runOnIdle { setPlaylist(playlist.copy(status = status)) }
            compose.onNodeWithText("Share Playlist").assertIsEnabled()
        }
        compose.runOnIdle {
            ReflectionHelpers.getField<MutableState<Boolean>>(model, "busy\$delegate").value = true
        }
        compose.onNodeWithText("Share Playlist").assertIsNotEnabled()
        assertTrue(requests.isEmpty())
    }

    @Test fun generatesOnlyAfterConsentAndCopiesValidatedServerLinkWithoutRegenerating() {
        openShare()
        generate()
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals(sharePath, request.url.encodedPath)
        assertNull(request.url.encodedQuery)
        assertEquals("{}", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals(origin, "${request.url.scheme}://${request.url.host}:${request.url.port}")
        compose.onNodeWithText(origin + publicPath).assertExists()
        compose.onNodeWithText("Copy link").performClick()
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(origin + publicPath, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals("Public playlist link", clipboard.primaryClipDescription!!.label)
        compose.onNodeWithText("Copied").performClick()
        assertEquals(1, requests.size)
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Public playlist link").assertDoesNotExist()
    }

    @Test fun cancellationBeforeGenerationMakesNoRequest() {
        openShare()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(requests.isEmpty())
        compose.onNodeWithText("Generate public link").assertDoesNotExist()
    }

    @Test fun failedRequestsShowServerErrorsAndAllowRetry() {
        openShare()
        for (status in listOf(403, 404, 500)) {
            response.set(status to json("error" to "Sharing unavailable ($status)").toString())
            generate()
            compose.onNodeWithText("Sharing unavailable ($status)").assertExists()
            compose.onNodeWithText("Generate public link").assertIsEnabled()
            compose.onNodeWithText("Copy link").assertDoesNotExist()
        }
        response.set(201 to json("url" to publicPath).toString())
        generate()
        compose.onNodeWithText("Copy link").assertIsEnabled()
        compose.onNodeWithText("Sharing unavailable", substring = true).assertDoesNotExist()
    }

    @Test fun rejectsMissingMalformedAndOffOriginLinks() {
        openShare()
        response.set(201 to "{}")
        generate()
        compose.onNodeWithText("The server did not return a share link.").assertExists()
        for (url in listOf(origin + publicPath, "https://other.example$publicPath", "//other.example$publicPath",
            "/share/" + "p".repeat(43), publicPath.dropLast(1), "$publicPath?session=test",
            "$publicPath#fragment", "/share/playlist/../" + "p".repeat(43))) {
            response.set(201 to json("url" to url).toString())
            generate()
            compose.onNodeWithText("The server returned an invalid share link.").assertExists()
            compose.onNodeWithText("Copy link").assertDoesNotExist()
        }
    }

    @Test fun privacyChangesHideGeneratedLinksAndDisableCopying() {
        openShare()
        generate()
        compose.runOnIdle { setPlaylist(playlist.copy(isPrivate = true)) }
        compose.onNodeWithText("Private playlists cannot be shared.", substring = true).assertExists()
        compose.onNodeWithText(origin + publicPath).assertDoesNotExist()
        compose.onNodeWithText("Copy link").assertIsNotEnabled()
        assertEquals(1, requests.size)
    }

    @Test fun accountChangesDiscardTheShareDialogAndItsLink() {
        openShare()
        generate()
        compose.runOnIdle { account.value = testAccount(User("admin", role = "admin")) }
        compose.onNodeWithText("Copy link").assertDoesNotExist()
        compose.onNodeWithText(origin + publicPath).assertDoesNotExist()
        openShare()
        compose.onNodeWithText("Generate public link").assertIsEnabled()
        assertEquals(1, requests.size)
    }

    @Test fun songSharingKeepsItsExistingEndpointAndPublicPath() {
        val track = Track("job /?", "folder/song #1.mp3", title = "Song")
        val songPath = "/share/" + "s".repeat(43)
        response.set(201 to json("url" to songPath).toString())
        compose.setContent { MusicTheme { ShareMediaDialog(model, track) {} } }
        assertTrue(requests.isEmpty())
        generate()
        assertEquals("/api/jobs/job%20%2F%3F/files/folder%2Fsong%20%231.mp3/share",
            requests.single().url.encodedPath)
        compose.onNodeWithText("Public media link").assertExists()
        compose.onNodeWithText(origin + songPath).assertExists()
        compose.onNodeWithText("Copy link").assertIsEnabled()
    }

    private fun testAccount(user: User) =
        Account(origin, user, Session("test", "2099-01-01T00:00:00Z"))

    private fun setPlaylist(value: LibraryPlaylist) {
        library.value = LibraryState(library = Library(entries = listOf(entry), playlists = listOf(value)))
    }

    private var contentSet = false

    private fun openShare() {
        if (!contentSet) {
            compose.setContent { MusicTheme { EntryMenu(model, entry) } }
            contentSet = true
        }
        val previousRequests = requests.size
        compose.onNodeWithContentDescription("Options for Playlist").performClick()
        compose.onNodeWithText("Share Playlist").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithText("Anyone with this link can listen to this playlist", substring = true).assertExists()
        compose.onNodeWithText("Generate public link").assertIsEnabled()
        assertEquals(previousRequests, requests.size)
    }

    private fun generate() {
        val expected = requests.size + 1
        compose.onNodeWithText("Generate public link").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            requests.size == expected && compose.onAllNodesWithText("Generating...").fetchSemanticsNodes().isEmpty()
        }
    }
}
