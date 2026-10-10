package com.ssytdlp.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.FilePrivacy
import com.ssytdlp.app.core.Job
import com.ssytdlp.app.core.Library
import com.ssytdlp.app.core.LibraryEntry
import com.ssytdlp.app.core.LibraryPlaylist
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.User
import com.ssytdlp.app.core.UserResponse
import java.security.Provider
import java.security.Security
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
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
class PrivacyActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val provider = object : Provider("PrivacyTestKeyStore", 1.0, "Empty test session keystore") {}
    private lateinit var application: MusicApplication
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private val owner = User("owner", "Owner")
    private val contributor = User("contributor", "Contributor")
    private val job = Job("job /?#", status = "completed", initiatedBy = owner,
        contributors = listOf(contributor), privateFiles = emptyList())
    private val track = Track(job.id, "folder/live #1?+.mp3", title = "Song", sourceJob = job)
    private val companion = Track(job.id, "[NoVocals]/folder/live #1?+.mp3", sourceJob = job)
    private val entry = LibraryEntry("playlist /?#", "playlist", "Playlist")
    private val playlist = LibraryPlaylist(entry.id, "Playlist", jobId = job.id, initiatedBy = owner)
    private val jobFilesPath = "/api/jobs/job%20%2F%3F%23/files"
    private val songPrivacyPath = "$jobFilesPath/folder%2Flive%20%231%3F%2B.mp3/privacy"
    private val playlistPrivacyPath = "/api/library/playlists/playlist%20%2F%3F%23/privacy"
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
    }

    @After fun cleanup() {
        compose.runOnUiThread { models.clear() }
        Security.removeProvider(provider.name)
    }

    @Test fun songToggleEncodesNestedFilenameAndRefreshesQueuedOriginalAndNoVocalsAcrossPages() {
        val savedJob = AtomicReference(job)
        val queued = track.copy(noVocalsVersion = companion)
        val otherPage = TrackPage(files = listOf(Track("other", "other.mp3")), page = 2)
        val model = startModel(files = otherPage) { request ->
            when (request.url.encodedPath) {
                songPrivacyPath -> 200 to ApiJson.encodeToString(savedJob.get())
                jobFilesPath -> 200 to ApiJson.encodeToString(TrackPage(files = listOf(
                    Track(name = track.name, isPrivate = savedJob.get().privateFiles!!.isNotEmpty(),
                        noVocalsVersion = Track(name = companion.name,
                            isPrivate = savedJob.get().privateFiles!!.isNotEmpty())))))
                "/api/library" -> 200 to ApiJson.encodeToString(library(savedJob.get()))
                else -> null
            }
        }
        compose.runOnIdle {
            state(model).value = model.library.copy(selectedId = entry.id, search = "other")
        }
        compose.setContent { MusicTheme { TrackMenu(model, queued, 0) { _, _ -> } } }

        for (isPrivate in listOf(true, false)) {
            val updated = job.copy(privateFiles = if (isPrivate) listOf(track.name) else emptyList())
            savedJob.set(updated)
            openMenu("Options for Song")
            compose.onNodeWithText(if (isPrivate) "Make song private" else "Make song public")
                .assertIsEnabled().performClick()
            waitFor { !model.songBusy(track) && !model.library.loading && model.notice != null }

            val sent = drainRequests()
            assertEquals(listOf(songPrivacyPath, jobFilesPath, "/api/library"),
                sent.map { it.url.encodedPath })
            assertPatch(sent.first(), songPrivacyPath, isPrivate)
            assertTrue(sent.drop(1).all { it.method == "GET" })
            assertEquals(2, model.library.page)
            assertEquals(entry.id, model.library.selectedId)
            assertEquals("other", model.library.search)
            assertEquals(isPrivate, model.library.filePrivacy[queued.key])
            assertEquals(isPrivate, model.library.filePrivacy[companion.key])
            assertEquals(updated, model.library.privacyJobs[job.id])
            assertEquals(updated, model.library.library.jobs.single())
            assertEquals(FilePrivacy(isPrivate, false), model.songPrivacy(queued.copy(isPrivate = !isPrivate)))
            assertEquals(FilePrivacy(isPrivate, isPrivate), model.songPrivacy(companion.copy(isPrivate = !isPrivate)))
            assertEquals(otherPage, model.library.tracks)

            openMenu("Options for Song")
            compose.onNodeWithText(if (isPrivate) "Make song public" else "Make song private").assertIsEnabled()
            if (isPrivate) compose.onNodeWithText("Share Media").assertIsNotEnabled()
            else compose.onNodeWithText("Share Media").assertIsEnabled()
        }
        assertFalse(queued.isPrivate)
        assertFalse(queued.noVocalsVersion!!.isPrivate)
    }

    @Test fun sourcePlaylistToggleRefreshesInheritedQueuePrivacyButPreservesExplicitPrivateSongs() {
        val explicit = track.copy(name = "explicit.mp3")
        val savedJob = AtomicReference(job.copy(privateFiles = listOf(explicit.name)))
        val savedPlaylist = AtomicReference(playlist)
        val otherPage = TrackPage(files = listOf(Track("other", "other.mp3")), page = 2)
        val model = startModel(library(savedJob.get()), otherPage) { request ->
            when (request.url.encodedPath) {
                playlistPrivacyPath -> 200 to ApiJson.encodeToString(library(savedJob.get(), savedPlaylist.get()))
                jobFilesPath -> 200 to ApiJson.encodeToString(TrackPage(files = listOf(
                    Track(name = track.name, isPrivate = savedJob.get().isPrivate,
                        noVocalsVersion = Track(name = companion.name, isPrivate = savedJob.get().isPrivate)),
                    Track(name = explicit.name, isPrivate = true))))
                else -> null
            }
        }
        compose.setContent {
            MusicTheme {
                NowPlayingScreen(model, PlaybackState(track = track, queue = listOf(track, companion, explicit))) { _, _ -> }
            }
        }
        for (isPrivate in listOf(true, false)) {
            val updated = savedJob.get().copy(isPrivate = isPrivate)
            savedJob.set(updated)
            savedPlaylist.set(playlist.copy(isPrivate = isPrivate))
            perform(model) { model.setPlaylistPrivate(playlist, isPrivate) }

            val sent = drainRequests()
            assertEquals(listOf(playlistPrivacyPath, jobFilesPath, "/api/library/tracks"),
                sent.map { it.url.encodedPath })
            assertPatch(sent.first(), playlistPrivacyPath, isPrivate)
            assertTrue(sent.drop(1).all { it.method == "GET" })
            assertEquals(isPrivate, model.library.library.playlists.single().isPrivate)
            assertEquals(isPrivate, model.library.library.entries.single().isPrivate)
            assertEquals(updated, model.library.privacyJobs[job.id])
            assertEquals(isPrivate, model.library.filePrivacy[track.key])
            assertEquals(isPrivate, model.library.filePrivacy[companion.key])
            assertEquals(true, model.library.filePrivacy[explicit.key])
            assertEquals(FilePrivacy(isPrivate, isPrivate), model.songPrivacy(track.copy(isPrivate = !isPrivate)))
            assertEquals(FilePrivacy(isPrivate, isPrivate), model.songPrivacy(companion.copy(isPrivate = !isPrivate)))
            assertEquals(FilePrivacy(true, isPrivate), model.songPrivacy(explicit))
            assertEquals(otherPage, model.library.tracks)
            assertEquals(if (isPrivate) "Playlist is private. Only the owner can access it."
                else "Playlist is no longer private.", model.notice)
            openMenu("More song actions")
            if (isPrivate) {
                compose.onNodeWithText("Private — inherited from source").assertIsNotEnabled()
                compose.onNodeWithText("Make song public").assertDoesNotExist()
                compose.onNodeWithText("Share Media").assertIsNotEnabled()
            } else {
                compose.onNodeWithText("Make song private").assertIsEnabled()
                compose.onNodeWithText("Share Media").assertIsEnabled()
            }
        }
    }

    @Test fun protectedSyntheticPlaylistOwnerCanToggleWithoutPrivatizingLinkedSongsOrFetchingAJob() {
        val synthetic = playlist.copy(jobId = null)
        val protectedEntry = entry.copy(protected = true)
        val linked = track.copy(playlistId = synthetic.id)
        val saved = AtomicReference(library(playlist = synthetic).copy(entries = listOf(protectedEntry)))
        val model = startModel(saved.get(), TrackPage(files = listOf(linked))) { request ->
            if (request.url.encodedPath == playlistPrivacyPath) 200 to ApiJson.encodeToString(saved.get()) else null
        }
        compose.setContent { MusicTheme { EntryMenu(model, protectedEntry) } }
        for (isPrivate in listOf(true, false)) {
            saved.set(saved.get().copy(
                playlists = listOf(synthetic.copy(isPrivate = isPrivate)),
                entries = listOf(protectedEntry.copy(isPrivate = isPrivate))))
            openMenu("Options for Playlist")
            compose.onNodeWithText(if (isPrivate) "Make playlist private" else "Make playlist public")
                .assertIsEnabled().performClick()
            waitFor { !model.busy && !model.library.loading && model.notice != null }

            val sent = drainRequests()
            assertEquals(listOf(playlistPrivacyPath, "/api/library/tracks"), sent.map { it.url.encodedPath })
            assertPatch(sent.first(), playlistPrivacyPath, isPrivate)
            assertEquals("GET", sent.last().method)
            assertEquals(isPrivate, model.library.library.playlists.single().isPrivate)
            assertTrue(model.library.library.entries.single().protected)
            assertEquals(FilePrivacy(false, false), model.songPrivacy(linked))
            assertEquals(false, model.library.filePrivacy[linked.key])
            assertEquals(job, model.library.privacyJobs[job.id])
        }
    }

    @Test fun rejectedSongAndPlaylistPatchesDoNotFlipFlagsOrRefreshLibrary() {
        val model = startModel { request ->
            if (request.method == "PATCH") 403 to """{"error":"Privacy denied"}""" else null
        }
        for (isPrivate in listOf(false, true)) {
            val currentJob = job.copy(privateFiles = if (isPrivate) listOf(track.name) else emptyList())
            val currentPlaylist = playlist.copy(isPrivate = isPrivate)
            val before = LibraryState().withLibrary(library(currentJob, currentPlaylist))
                .withTrackPage(TrackPage(files = listOf(track.copy(isPrivate = isPrivate, sourceJob = currentJob))))
            compose.runOnIdle { state(model).value = before }
            perform(model) { model.setSongPrivate(track, !isPrivate) }
            assertEquals("Privacy denied", model.notice)
            assertEquals(before, model.library)
            assertPatch(drainRequests().single(), songPrivacyPath, !isPrivate)

            perform(model) { model.setPlaylistPrivate(currentPlaylist, !isPrivate) }
            assertEquals("Privacy denied", model.notice)
            assertEquals(before, model.library)
            assertPatch(drainRequests().single(), playlistPrivacyPath, !isPrivate)
        }
    }

    @Test fun inheritedSongCannotBeMadePublicEvenWhenActionIsCalledDirectly() {
        val source = job.copy(isPrivate = true)
        val model = startModel(library(source), TrackPage(files = listOf(track.copy(sourceJob = source))))
        val before = model.library
        perform(model) { model.setSongPrivate(track, false) }
        assertEquals("Change privacy on the source playlist or original song.", model.notice)
        assertEquals(before, model.library)
        assertTrue(drainRequests().isEmpty())
    }

    @Test fun pollingRestoredOffPageQueueClearsStalePrivacyAndObservesLaterPrivateChanges() {
        val oldJob = job.copy(privateFiles = listOf(track.name), updatedAt = "old")
        val staleCompanion = companion.copy(isPrivate = true, sourceJob = oldJob)
        val restored = track.copy(isPrivate = true, sourceJob = oldJob, noVocalsVersion = staleCompanion)
        val savedJob = AtomicReference(job.copy(updatedAt = "current"))
        val page = AtomicReference(TrackPage())
        val jobPath = jobFilesPath.removeSuffix("/files")
        val model = startModel(library(savedJob.get()), page.get()) { request ->
            when (request.url.encodedPath) {
                "/api/library/tracks" -> 200 to ApiJson.encodeToString(page.get())
                jobPath -> 200 to ApiJson.encodeToString(savedJob.get())
                jobFilesPath -> {
                    val isPrivate = savedJob.get().privateFiles!!.contains(track.name)
                    200 to ApiJson.encodeToString(TrackPage(files = listOf(
                        Track(name = track.name, isPrivate = isPrivate,
                            noVocalsVersion = Track(name = companion.name, isPrivate = isPrivate)))))
                }
                else -> null
            }
        }
        assertTrue(model.library.filePrivacy.isEmpty())
        assertTrue(model.songPrivacy(restored).isPrivate)

        perform(model) { model.launchAction { model.pollTranscriptions(extraTracks = listOf(restored)) } }
        val initialRequests = drainRequests()
        assertEquals(listOf("/api/library/tracks", jobPath, jobFilesPath),
            initialRequests.map { it.url.encodedPath })
        assertTrue(initialRequests.all { it.method == "GET" })
        assertEquals(setOf(track.key, companion.key), model.library.filePrivacy.keys)
        assertEquals(false, model.library.filePrivacy[restored.key])
        assertEquals(false, model.library.filePrivacy[staleCompanion.key])
        assertEquals(savedJob.get(), model.library.privacyJobs[job.id])
        assertEquals(FilePrivacy(false, false), model.songPrivacy(restored))
        assertEquals(FilePrivacy(false, false), model.songPrivacy(staleCompanion))

        page.set(TrackPage(files = listOf(Track("other", "other.mp3")), page = 2))
        perform(model) { model.refreshTracks() }
        assertEquals("/api/library/tracks", drainRequests().single().url.encodedPath)
        assertEquals(page.get(), model.library.tracks)
        assertEquals(FilePrivacy(false, false), model.songPrivacy(restored))
        assertEquals(FilePrivacy(false, false), model.songPrivacy(staleCompanion))

        perform(model) { model.launchAction { model.pollTranscriptions(extraTracks = listOf(restored)) } }
        assertEquals(listOf("/api/library/tracks", jobPath), drainRequests().map { it.url.encodedPath })
        assertEquals(FilePrivacy(false, false), model.songPrivacy(restored))

        savedJob.set(savedJob.get().copy(privateFiles = listOf(track.name)))
        perform(model) { model.launchAction { model.pollTranscriptions(extraTracks = listOf(track)) } }
        val changedRequests = drainRequests()
        assertEquals(listOf("/api/library/tracks", jobPath, jobFilesPath),
            changedRequests.map { it.url.encodedPath })
        assertTrue(changedRequests.all { it.method == "GET" })
        assertEquals(true, model.library.filePrivacy[track.key])
        assertEquals(true, model.library.filePrivacy[companion.key])
        assertEquals(savedJob.get(), model.library.privacyJobs[job.id])
        assertEquals(FilePrivacy(true, false), model.songPrivacy(track))
        assertEquals(FilePrivacy(true, true), model.songPrivacy(companion))
        assertEquals(page.get(), model.library.tracks)
        assertNull(model.notice)
    }

    @Test fun allSongMenusShowPrivacyOnlyToOwnerNotOtherAdminsContributorsOrSharedUsers() {
        val model = startModel()
        compose.runOnIdle { model.viewModelScope.cancel() }
        val surface = mutableStateOf(SongSurface.LIBRARY)
        compose.setContent { MusicTheme { SongMenu(surface.value, model, track) } }
        val users = listOf(owner, owner.copy(role = "admin"), User("admin", role = "admin"),
            contributor, owner.copy(role = "shared"), User("stranger"), null)
        for (location in SongSurface.entries) {
            compose.runOnIdle { surface.value = location }
            for (user in users) {
                compose.runOnIdle { account.value = user?.let(::testAccount) }
                openMenu(location.description)
                if (user == owner || user == owner.copy(role = "admin")) {
                    compose.onNodeWithText("Make song private").assertIsDisplayed().assertIsEnabled()
                } else {
                    compose.onNodeWithText("Make song private").assertDoesNotExist()
                }
                compose.onNodeWithText("Make song public").assertDoesNotExist()
                compose.onNodeWithText("Private — inherited from source").assertDoesNotExist()
                compose.onNodeWithText("Save file").assertIsEnabled()
            }
        }
        assertTrue(drainRequests().isEmpty())
    }

    @Test fun libraryDockAndNowPlayingUseRefreshedPrivacyAndDisableInheritedPublicAndShareActions() {
        val model = startModel()
        compose.runOnIdle { model.viewModelScope.cancel() }
        val surface = mutableStateOf(SongSurface.LIBRARY)
        compose.setContent { MusicTheme { SongMenu(surface.value, model, track) } }
        val cases = listOf(
            Triple(job, false, "Make song private"),
            Triple(job.copy(privateFiles = listOf(track.name)), true, "Make song public"),
            Triple(job.copy(isPrivate = true), false, "Private — inherited from source"),
            Triple(job.copy(privateFiles = listOf("original.mp3")), true, "Private — inherited from source")
        )
        for (location in SongSurface.entries) {
            compose.runOnIdle { surface.value = location }
            for ((source, isPrivate, label) in cases) {
                compose.runOnIdle {
                    state(model).value = LibraryState().withLibrary(library(source))
                        .withTrackPage(TrackPage(files = listOf(track.copy(isPrivate = isPrivate, sourceJob = source))))
                }
                openMenu(location.description)
                val action = compose.onNodeWithText(label).assertIsDisplayed()
                if (label.startsWith("Private")) {
                    action.assertIsNotEnabled()
                    compose.onNodeWithText("Make song public").assertDoesNotExist()
                } else action.assertIsEnabled()
                if (isPrivate || source.isPrivate) compose.onNodeWithText("Share Media").assertIsNotEnabled()
                else compose.onNodeWithText("Share Media").assertIsEnabled()
            }
            for (status in listOf("queued", "running")) {
                compose.runOnIdle {
                    state(model).value = LibraryState().withLibrary(library(job.copy(status = status)))
                }
                compose.onNodeWithText("Make song private").assertIsNotEnabled()
            }
        }
        assertTrue(drainRequests().isEmpty())
    }

    @Test fun protectedPlaylistMenuUsesOwnershipAndIdleStatusRatherThanAdminOrContributorPermissions() {
        val model = startModel(library(playlist = playlist.copy(jobId = null))
            .copy(entries = listOf(entry.copy(protected = true))))
        compose.runOnIdle { model.viewModelScope.cancel() }
        compose.setContent { MusicTheme { EntryMenu(model, entry.copy(protected = true)) } }
        for (user in listOf(owner, owner.copy(role = "admin"), User("admin", role = "admin"),
            contributor, owner.copy(role = "shared"), User("stranger"), null)) {
            compose.runOnIdle { account.value = user?.let(::testAccount) }
            openMenu("Options for Playlist")
            if (user == owner || user == owner.copy(role = "admin")) {
                compose.onNodeWithText("Make playlist private").assertIsEnabled()
            } else compose.onNodeWithText("Make playlist private").assertDoesNotExist()
            compose.onNodeWithText("Make playlist public").assertDoesNotExist()
        }
        compose.runOnIdle { account.value = testAccount(owner) }
        for (status in listOf("queued", "running", "completed")) {
            compose.runOnIdle {
                state(model).value = model.library.withLibrary(library(playlist = playlist.copy(
                    jobId = null, isPrivate = true, status = status)))
            }
            val action = compose.onNodeWithText("Make playlist public").assertIsDisplayed()
            if (status == "completed") action.assertIsEnabled() else action.assertIsNotEnabled()
        }
        assertTrue(drainRequests().isEmpty())
    }

    private fun library(source: Job = job, playlist: LibraryPlaylist = this.playlist) = Library(
        entries = listOf(entry.copy(isPrivate = playlist.isPrivate)), playlists = listOf(playlist), jobs = listOf(source))

    private fun startModel(
        library: Library = library(),
        files: TrackPage = TrackPage(files = listOf(track)),
        response: (Request) -> Pair<Int, String>? = { null }
    ): MusicViewModel {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val (status, body) = response(request) ?: if (request.method != "GET") 404 to "{}"
            else when (request.url.encodedPath) {
                "/api/auth/me" -> 200 to ApiJson.encodeToString(UserResponse(owner))
                "/api/library" -> 200 to ApiJson.encodeToString(library)
                "/api/library/tracks" -> 200 to ApiJson.encodeToString(files)
                "/api/preferences", "/api/health" -> 200 to "{}"
                else -> 404 to "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Test response")
                .body(body.toResponseBody()).build()
        }.build()
        lateinit var model: MusicViewModel
        compose.runOnUiThread {
            account.value = testAccount(owner)
            ReflectionHelpers.setField(application, "api", ServerApi({ account.value }, {}, client))
            model = MusicViewModel(application, connectPlayback = false)
            models.put("privacy", model)
        }
        waitFor { !model.library.loading && model.library.tracks == files && model.health != null }
        assertNull(model.notice)
        drainRequests()
        return model
    }

    private fun testAccount(user: User) =
        Account("https://music.example", user, Session("test", "2099-01-01T00:00:00Z"))

    private fun state(model: MusicViewModel) =
        ReflectionHelpers.getField<MutableState<LibraryState>>(model, "library\$delegate")

    private fun perform(model: MusicViewModel, action: () -> Unit) {
        compose.runOnUiThread { model.message(null); action() }
        waitFor { !model.busy && model.pendingSongs.isEmpty() && !model.library.loading }
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private fun drainRequests(): List<Request> = synchronized(requests) {
        requests.toList().also { requests.clear() }
    }

    private fun assertPatch(request: Request, path: String, isPrivate: Boolean) {
        assertEquals("PATCH", request.method)
        assertEquals(path, request.url.encodedPath)
        assertNull(request.url.encodedQuery)
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertEquals(ApiJson.parseToJsonElement("""{"private":$isPrivate}"""), ApiJson.parseToJsonElement(body))
    }

    private fun openMenu(description: String) {
        if (compose.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription(description).performClick()
        }
        compose.onNode(isPopup()).assertExists()
    }

    private enum class SongSurface(val description: String) {
        LIBRARY("Options for Song"), DOCK("More song actions"), NOW_PLAYING("More song actions")
    }

    @Composable private fun SongMenu(surface: SongSurface, model: MusicViewModel, queued: Track) {
        val playback = PlaybackState(track = queued, queue = listOf(queued))
        when (surface) {
            SongSurface.LIBRARY -> TrackMenu(model, queued, 0) { _, _ -> }
            SongSurface.DOCK -> PlayerDock(playback, model, {}, {}) { _, _ -> }
            SongSurface.NOW_PLAYING -> NowPlayingScreen(model, playback) { _, _ -> }
        }
    }
}
