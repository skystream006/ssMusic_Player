package com.ssytdlp.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.*
import java.security.Provider
import java.security.Security
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
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
class PlaylistEditingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val provider = object : Provider("PlaylistEditingKeyStore", 1.0, "Empty test session keystore") {}
    private val models = ViewModelStore()
    private lateinit var application: MusicApplication
    private lateinit var model: MusicViewModel
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private val owner = User("owner")
    private val entry = LibraryEntry("playlist /?#", "playlist", "Playlist")
    private val job = Job("job /?#", playlistTitle = "Playlist", status = "completed",
        initiatedBy = owner, privateFiles = emptyList())
    private val playlist = LibraryPlaylist(entry.id, "Playlist", songCount = 2, jobId = job.id,
        initiatedBy = owner, contributors = listOf(User("contributor")))
    private val folders = listOf(LibraryEntry("parent", "folder", "Collection"),
        LibraryEntry("child", "folder", "Favorites", parentId = "parent"))
    private val catalog = AtomicReference(Library(version = 7, songCount = 2,
        entries = listOf(entry) + folders, playlists = listOf(playlist), jobs = listOf(job)))
    private val titlePath = "/api/jobs/job%20%2F%3F%23/title"
    private val privacyPath = "/api/library/playlists/playlist%20%2F%3F%23/privacy"
    private val filesPath = "/api/jobs/job%20%2F%3F%23/files"
    private val account get() =
        ReflectionHelpers.getField<MutableStateFlow<Account?>>(application.sessions, "mutableAccount")
    private val state get() =
        ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")
    private var contentSet = false

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
    }

    @After fun cleanup() {
        compose.runOnUiThread { models.clear() }
        Security.removeProvider(provider.name)
    }

    @Test fun menuAllowsPersonalLocationEditsButNotFoldersSharedUsersOrActiveJobs() {
        startModel()
        compose.runOnIdle { model.viewModelScope.cancel() }
        showMenu()
        for (user in listOf(owner, User("contributor"), User("admin", role = "admin"), User("other"),
            owner.copy(role = "shared"), null)) {
            compose.runOnIdle { account.value = user?.let(::testAccount) }
            if (user != null && !user.isShared) compose.onNodeWithText("Edit playlist").assertIsEnabled()
            else compose.onNodeWithText("Edit playlist").assertDoesNotExist()
        }
        compose.runOnIdle { account.value = testAccount(owner) }
        for (status in listOf("queued", "running")) {
            compose.runOnIdle {
                state.value = model.library.withLibrary(catalog.get().copy(playlists = listOf(playlist.copy(status = status))))
            }
            compose.onNodeWithText("Edit playlist").assertIsNotEnabled()
        }
        compose.runOnIdle {
            state.value = model.library.withLibrary(catalog.get().copy(entries = listOf(entry.copy(type = "folder"))))
        }
        compose.onNodeWithText("Edit playlist").assertDoesNotExist()
        compose.onNodeWithText("Rename").assertExists()
        assertTrue(requests.isEmpty())
    }

    // Robolectric #8460: text fields in dialogs loop at widths above the default 320dp.
    @Config(qualifiers = "w320dp-h800dp")
    @Test fun dialogRestrictsEachFieldAndKeepsProtectedNamesFixed() {
        startModel()
        compose.runOnIdle { model.viewModelScope.cancel() }
        for (user in listOf(owner, User("admin", role = "admin"), User("contributor"), User("other"))) {
            compose.runOnIdle { account.value = testAccount(user) }
            openEdit()
            val name = compose.onNodeWithText("Playlist name")
            if (user == owner || user.role == "admin") name.assertIsEnabled() else name.assertIsNotEnabled()
            val privacy = compose.onNode(isToggleable())
            if (user == owner) privacy.assertIsEnabled() else privacy.assertIsNotEnabled()
            compose.onNodeWithText("Library").assertIsEnabled()
            compose.onNodeWithText("Cancel").performClick()
        }
        compose.runOnIdle {
            account.value = testAccount(owner)
            state.value = model.library.withLibrary(catalog.get().copy(
                entries = listOf(entry.copy(protected = true)) + folders,
                playlists = listOf(playlist.copy(jobId = null))))
        }
        openEdit()
        compose.onNodeWithText("Playlist name").assertIsNotEnabled()
        compose.onNode(isToggleable()).assertIsEnabled()
        compose.onNodeWithText("Open job details").assertDoesNotExist()
        assertTrue(requests.isEmpty())
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun editsAreLocalUntilSaveAndInvalidNamesOrDirtyFieldsDisableActions() {
        startModel()
        openEdit()
        compose.onNodeWithText("Playlist name").assertTextContains("Playlist")
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Share Playlist").assertIsEnabled()
        compose.onNodeWithText("Playlist name").performTextReplacement("   ")
        compose.onNodeWithText("Save changes").assertIsNotEnabled()
        compose.onNodeWithText("Share Playlist").assertIsNotEnabled()
        compose.onNodeWithText("Playlist name").performTextReplacement("Renamed")
        compose.onNode(isToggleable()).performClick()
        chooseFolder()
        compose.onNodeWithText("Save changes").assertIsEnabled()
        compose.onNodeWithText("Share Playlist").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertTrue(requests.isEmpty())
        assertEquals(playlist, model.library.library.playlists.single())
        openEdit()
        compose.onNodeWithText("Playlist name").assertTextContains("Playlist")
        compose.onNode(isToggleable()).assertIsOff()
        compose.onNodeWithText("Library").assertExists()
        compose.runOnIdle {
            ReflectionHelpers.getField<MutableState<Boolean>>(model, "busy\$delegate").value = true
        }
        compose.onNodeWithText("Playlist name").assertIsNotEnabled()
        compose.onNode(isToggleable()).assertIsNotEnabled()
        for (label in listOf("Library", "Share Playlist", "Save changes", "Cancel")) {
            compose.onNodeWithText(label).assertIsNotEnabled()
        }
        assertTrue(requests.isEmpty())
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun saveEncodesIdsUsesFreshVersionAndMergesMoveStateWithoutLosingCatalog() {
        startModel()
        compose.runOnIdle { state.value = model.library.copy(selectedId = entry.id, search = "song") }
        openEdit()
        compose.onNodeWithText("Playlist name").performTextReplacement("  New playlist  ")
        compose.onNode(isToggleable()).performClick()
        chooseFolder()
        save()
        compose.onNode(isDialog()).assertDoesNotExist()
        val sent = requests.toList()
        assertEquals(listOf(titlePath, privacyPath, filesPath, "/api/library", "/api/library/entries",
            "/api/library/tracks"), sent.map { it.url.encodedPath })
        assertEquals("PATCH", sent[0].method)
        assertEquals(json("playlistTitle" to "New playlist"), body(sent[0]))
        assertEquals("PATCH", sent[1].method)
        assertEquals(json("private" to true), body(sent[1]))
        assertEquals("POST", sent[4].method)
        assertEquals(json("version" to 9, "action" to "move", "id" to entry.id,
            "parentId" to "child", "targetId" to null, "after" to false), body(sent[4]))
        assertEquals(10L, model.library.library.version)
        assertEquals("New playlist", model.library.library.playlists.single().playlistTitle)
        assertTrue(model.library.library.playlists.single().isPrivate)
        assertEquals("child", model.library.library.entries.first().parentId)
        assertEquals(2, model.library.library.songCount)
        assertEquals(entry.id, model.library.selectedId)
        assertEquals(entry.id, sent.last().url.queryParameter("entryId"))
        assertEquals("song", sent.last().url.queryParameter("search"))
        assertTrue(model.library.privacyJobs.getValue(job.id).isPrivate)
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun partialFailureReloadsCatalogKeepsEditsAndRetriesOnlyRemainingChanges() {
        val failPrivacy = AtomicBoolean(true)
        startModel { request ->
            if (request.url.encodedPath == privacyPath && failPrivacy.get())
                403 to """{"error":"Privacy denied"}""" else null
        }
        openEdit()
        compose.onNodeWithText("Playlist name").performTextReplacement("Renamed")
        compose.onNode(isToggleable()).performClick()
        chooseFolder()
        save()
        compose.onNodeWithText("Unable to save all playlist changes: Privacy denied").assertExists()
        compose.onNodeWithText("Playlist name").assertTextContains("Renamed")
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Collection / Favorites").assertExists()
        assertEquals("Renamed", model.library.library.playlists.single().playlistTitle)
        assertFalse(model.library.library.playlists.single().isPrivate)
        assertNull(model.library.library.entries.first().parentId)
        assertEquals(listOf(titlePath, privacyPath, "/api/library", "/api/library/tracks"),
            requests.map { it.url.encodedPath })
        requests.clear()
        failPrivacy.set(false)
        save()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertFalse(requests.any { it.url.encodedPath == titlePath })
        assertEquals("child", model.library.library.entries.first().parentId)
        assertTrue(model.library.library.playlists.single().isPrivate)
    }

    @Test fun contributorMovesToRootWithoutSendingDisallowedTitleOrPrivacyChanges() {
        catalog.set(catalog.get().copy(entries = listOf(entry.copy(parentId = "child")) + folders))
        startModel(User("contributor"))
        var completed = false
        compose.runOnUiThread {
            model.savePlaylist(entry.id, "Not permitted", true, null, { completed = true }, { fail(it) }, move = true)
        }
        waitFor { !model.busy && !model.library.loading }
        assertTrue(completed)
        assertEquals(listOf("/api/library", "/api/library/entries", "/api/library/tracks"),
            requests.map { it.url.encodedPath })
        assertEquals(JsonNull, body(requests[1])["parentId"])
        assertEquals(7, body(requests[1]).getValue("version").jsonPrimitive.int)
        assertEquals(playlist, model.library.library.playlists.single())
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun staleMoveVersionShowsErrorAndRetryUsesReloadedVersion() {
        val conflict = AtomicBoolean(true)
        startModel { request ->
            if (request.method == "POST" && request.url.encodedPath == "/api/library/entries" && conflict.get()) {
                catalog.set(catalog.get().copy(version = 20))
                409 to """{"error":"Your library changed in another tab. Refresh and try again."}"""
            } else null
        }
        openEdit()
        chooseFolder()
        save()
        compose.onNodeWithText("Unable to save all playlist changes:", substring = true).assertExists()
        assertEquals(20L, model.library.library.version)
        conflict.set(false)
        requests.clear()
        save()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(20, body(requests.single { it.method == "POST" }).getValue("version").jsonPrimitive.int)
    }

    @Test fun directSaveRejectsInvalidNamesWithoutMutation() {
        startModel()
        for (name in listOf("", " ", "a".repeat(201), "a\u0001b", "a\u007fb")) {
            var error: String? = null
            compose.runOnUiThread {
                model.savePlaylist(entry.id, name, false, null, { fail("Invalid title saved") }, { error = it })
            }
            waitFor { !model.busy && !model.library.loading }
            assertTrue(error.orEmpty().contains("between 1 and 200 characters without control characters"))
            assertTrue(requests.all { it.method == "GET" })
        }
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun draftAndSaveErrorsSurviveStateRestoration() {
        startModel { request ->
            if (request.url.encodedPath == titlePath) 409 to """{"error":"Playlist busy"}""" else null
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MusicTheme {
            EntryMenu(model, model.library.library.entries.first())
            PlaylistEditor(model)
        } }
        contentSet = true
        openEdit()
        compose.onNodeWithText("Playlist name").performTextReplacement("Unsaved title")
        chooseFolder()
        save()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Playlist name").assertTextContains("Unsaved title")
        compose.onNodeWithText("Collection / Favorites").assertExists()
        compose.onNodeWithText("Unable to save all playlist changes: Playlist busy").assertExists()
        compose.onNodeWithText("Save changes").assertIsEnabled()
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun editorSurvivesSwitchingBetweenModalAndInlineLibraryBrowsers() {
        startModel()
        val landscape = mutableStateOf(false)
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                orientation = if (landscape.value) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MusicTheme {
                    LibraryScreen(model, PlaybackState(), {}) { _, _ -> }
                    if (!landscape.value && model.playlistEditTarget == null) LibraryBrowser(model) {}
                    PlaylistEditor(model)
                }
            }
        }
        contentSet = true
        openEdit()
        compose.onNodeWithText("Playlist name").performTextReplacement("Unsaved title")
        compose.onNode(isToggleable()).performClick()
        chooseFolder()
        compose.runOnIdle { landscape.value = true }
        compose.onNodeWithTag("library-playlists-pane").assertExists()
        compose.onNodeWithText("Playlist name").assertTextContains("Unsaved title")
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Collection / Favorites").assertExists()
        compose.runOnIdle { landscape.value = false }
        compose.onNodeWithText("Playlist name").assertTextContains("Unsaved title")
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Collection / Favorites").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertNull(model.playlistEditTarget)
        assertTrue(requests.isEmpty())
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun savingNameOnlyDoesNotUndoAnotherClientsMove() {
        startModel()
        openEdit()
        compose.onNodeWithText("Playlist name").performTextReplacement("Renamed")
        catalog.set(catalog.get().copy(version = 11,
            entries = listOf(entry.copy(parentId = "child")) + folders))
        save()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertFalse(requests.any { it.url.encodedPath == "/api/library/entries" })
        assertEquals("child", model.library.library.entries.first().parentId)
        assertEquals("Renamed", model.library.library.playlists.single().playlistTitle)
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun retryCanExplicitlyMoveBackToOriginalLocationAfterLostResponse() {
        val loseResponse = AtomicBoolean(true)
        startModel { request ->
            if (request.method == "POST" && request.url.encodedPath == "/api/library/entries" &&
                loseResponse.getAndSet(false)) {
                catalog.set(catalog.get().copy(version = 8,
                    entries = listOf(entry.copy(parentId = "child")) + folders))
                500 to """{"error":"Response lost after moving"}"""
            } else null
        }
        openEdit()
        chooseFolder()
        save()
        assertEquals("child", model.library.library.entries.first().parentId)
        compose.onNodeWithText("Collection / Favorites").performScrollTo().performClick()
        compose.onNodeWithText("Library").performScrollTo().performClick()
        save()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertNull(model.library.library.entries.first().parentId)
        assertEquals(JsonNull, body(requests.last { it.method == "POST" })["parentId"])
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun failedRecoveryRequiresFreshCatalogBeforeRetryingPrivacy() {
        catalog.set(catalog.get().copy(playlists = listOf(playlist.copy(isPrivate = true)),
            jobs = listOf(job.copy(isPrivate = true))))
        val failRecovery = AtomicBoolean(false)
        startModel { request ->
            when {
                request.url.encodedPath == privacyPath && !body(request).getValue("private").jsonPrimitive.boolean -> {
                    catalog.set(catalog.get().copy(playlists = listOf(playlist), jobs = listOf(job)))
                    failRecovery.set(true)
                    500 to """{"error":"Response lost after saving"}"""
                }
                request.url.encodedPath == "/api/library" && failRecovery.get() ->
                    503 to """{"error":"Library unavailable"}"""
                else -> null
            }
        }
        openEdit()
        compose.onNode(isToggleable()).performClick()
        save()
        compose.onNodeWithText("Unable to save all playlist changes:", substring = true).assertExists()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        failRecovery.set(false)
        requests.clear()
        save()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals("/api/library", requests.first().url.encodedPath)
        assertEquals(json("private" to true), body(requests.single { it.method == "PATCH" }))
        assertTrue(model.library.library.playlists.single().isPrivate)
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun sharingClosesEditorWithoutGeneratingLinkAndAccountChangesDiscardEdits() {
        startModel()
        openEdit()
        compose.onNodeWithText("Share Playlist").performScrollTo().performClick()
        compose.onNodeWithText("Save changes").assertDoesNotExist()
        compose.onNodeWithText("Generate public link").assertExists()
        assertTrue(requests.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        openEdit()
        compose.onNodeWithText("Playlist name").performTextReplacement("Unsaved")
        compose.runOnIdle {
            model.viewModelScope.cancel()
            account.value = testAccount(User("admin", role = "admin"))
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        openEdit()
        compose.onNodeWithText("Playlist name").assertTextContains("Playlist")
        assertTrue(requests.isEmpty())
    }

    @Test fun locationsUseFullPathsAndTolerateMissingParentsAndCycles() {
        val locations = playlistLocations(folders + listOf(
            LibraryEntry("orphan", "folder", "Orphan", parentId = "missing"),
            LibraryEntry("cycle", "folder", "Cycle", parentId = "cycle"), entry))
        assertEquals(null to "Library", locations.first())
        assertTrue(locations.contains("child" to "Collection / Favorites"))
        assertTrue(locations.contains("orphan" to "Orphan"))
        assertTrue(locations.contains("cycle" to "Cycle"))
        assertFalse(locations.any { it.first == entry.id })
    }

    private fun startModel(user: User = owner, response: (Request) -> Pair<Int, String>? = { null }) {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val (status, text) = response(request) ?: respond(request, user)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Test response").body(text.toResponseBody()).build()
        }.build()
        compose.runOnUiThread {
            account.value = testAccount(user)
            ReflectionHelpers.setField(application, "api", ServerApi({ account.value }, {}, client))
            model = MusicViewModel(application, connectPlayback = false)
            models.put("editing", model)
        }
        waitFor { model.health != null && !model.busy && !model.library.loading }
        assertNull(model.notice)
        requests.clear()
    }

    private fun respond(request: Request, user: User): Pair<Int, String> {
        val current = catalog.get()
        val path = request.url.encodedPath
        if (request.method == "GET") return when (path) {
            "/api/auth/me" -> 200 to ApiJson.encodeToString(UserResponse(user))
            "/api/library" -> 200 to ApiJson.encodeToString(current)
            "/api/library/tracks", filesPath -> 200 to ApiJson.encodeToString(TrackPage())
            "/api/preferences", "/api/health" -> 200 to "{}"
            else -> 404 to "{}"
        }
        val body = body(request)
        return when (path) {
            titlePath -> {
                val title = body.getValue("playlistTitle").jsonPrimitive.content
                val updated = current.jobs.single().copy(playlistTitle = title)
                catalog.set(current.copy(version = current.version + 1, jobs = listOf(updated),
                    playlists = listOf(current.playlists.single().copy(playlistTitle = title))))
                200 to ApiJson.encodeToString(updated)
            }
            privacyPath -> {
                val value = body.getValue("private").jsonPrimitive.boolean
                val updated = current.copy(version = current.version + 1,
                    playlists = listOf(current.playlists.single().copy(isPrivate = value)),
                    jobs = current.jobs.map { it.copy(isPrivate = value) })
                catalog.set(updated)
                200 to ApiJson.encodeToString(updated)
            }
            "/api/library/entries" -> {
                val parent = body.getValue("parentId").jsonPrimitive.contentOrNull
                val updated = current.copy(version = current.version + 1,
                    entries = current.entries.map { if (it.id == entry.id) it.copy(parentId = parent) else it })
                catalog.set(updated)
                200 to buildJsonObject {
                    put("version", updated.version)
                    put("entries", ApiJson.encodeToJsonElement(updated.entries))
                }.toString()
            }
            else -> 404 to "{}"
        }
    }

    private fun showMenu() {
        if (!contentSet) {
            compose.setContent { MusicTheme {
                EntryMenu(model, model.library.library.entries.first { it.id == entry.id })
                PlaylistEditor(model)
            } }
            contentSet = true
        }
        compose.onNodeWithContentDescription("Options for Playlist").performClick()
    }

    private fun openEdit() {
        showMenu()
        compose.onNodeWithText("Edit playlist").performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithText("Save changes").assertExists()
    }

    private fun chooseFolder() {
        compose.onNode(hasText("Library") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Collection / Favorites").performScrollTo().performClick()
    }

    private fun save() {
        compose.onNodeWithText("Save changes").performClick()
        waitFor { !model.busy && !model.library.loading }
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private fun testAccount(user: User) =
        Account("https://music.example", user, Session("test", "2099-01-01T00:00:00Z"))

    private fun body(request: Request): JsonObject =
        ApiJson.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
}
