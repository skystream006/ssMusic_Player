package com.ssytdlp.app

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.LyricLine
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.SongMetadata
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.User
import com.ssytdlp.app.core.UserResponse
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MusicApplication::class)
class AccountRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private lateinit var context: Context
    private lateinit var application: MusicApplication
    private lateinit var server: MockWebServer
    private lateinit var account: Account
    private val sessions get() = application.sessions

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("private_session", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val loopback = InetAddress.getByName("127.0.0.1")
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start(loopback, 0) }
        application = context as MusicApplication
        ReflectionHelpers.setField(application, "api", ServerApi({ sessions.account.value }, { sessions.clear(it) },
            OkHttpClient.Builder()
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        if (hostname == "localhost") listOf(loopback) else okhttp3.Dns.SYSTEM.lookup(hostname)
                })
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()))
        account = Account(server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/"), User("listener", "Listener"),
            Session("T".repeat(43), "2099-01-01T00:00:00Z"))
    }

    @After fun teardown() {
        compose.runOnUiThread { models.clear() }
        server.shutdown()
        context.getSharedPreferences("private_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    companion object {
        private val provider = object : Provider("AccountRefreshKeyStore", 1.0, "In-memory session test key") {}

        @JvmStatic @BeforeClass fun installKeyStore() {
            provider.put("KeyStore.AndroidKeyStore", AccountRefreshKeyStore::class.java.name)
            Security.addProvider(provider)
        }

        @JvmStatic @AfterClass fun removeKeyStore() {
            Security.removeProvider(provider.name)
        }
    }

    @Test fun startupConsumesCurrentUserWithoutRestartingLibraryInitialization() {
        sessions.save(account)
        val shared = account.user.copy(role = "shared", sharedUserIds = listOf("owner"))
        enqueueUser(shared)
        server.enqueue(MockResponse().setBody("""{"theme":"midnight"}"""))
        server.enqueue(MockResponse().setBody("""{"songCount":1}"""))
        server.enqueue(MockResponse().setBody("""{"files":[{"jobId":"job","name":"song.mp3"}]}"""))
        val model = createModel(collectSession = true, connectPlayback = false)
        compose.waitUntil(timeoutMillis = 30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            model.library.tracks.files.size == 1 || model.notice != null
        }
        assertEquals(1, model.library.tracks.files.size)
        assertEquals(shared, sessions.account.value!!.user)
        assertEquals(shared, SessionStore(context).account.value!!.user)
        assertEquals("midnight", model.preferences.theme)
        assertEquals(1, model.library.library.songCount)
        assertEquals(4, server.requestCount)
        assertEquals("/api/auth/me", takePath())
        assertEquals("/api/preferences", takePath())
        assertEquals("/api/library", takePath())
        assertTrue(takePath().startsWith("/api/library/tracks?"))
    }

    @Test fun settingsRefreshRoleAndOnlyPollPermittedEndpoints() = runBlocking {
        val model = createModel()
        sessions.save(account)
        enqueueUser(account.user.copy(role = "admin"))
        server.enqueue(MockResponse().setBody("""{"running":false}"""))
        server.enqueue(MockResponse().setBody("""{"media":{"totalFiles":1}}"""))
        model.pollSettings()
        assertNotNull(model.backup)
        assertNotNull(model.health)
        assertEquals(listOf("/api/auth/me", "/api/library/backup", "/api/health"), List(3) { takePath() })

        val shared = account.user.copy(role = "shared", sharedUserIds = listOf("owner"))
        enqueueUser(shared)
        model.pollSettings()
        assertEquals(shared, sessions.account.value!!.user)
        assertEquals("/api/auth/me", takePath())
        assertEquals(4, server.requestCount)
        assertNull(model.backup)
        assertNull(model.health)
        assertNull(model.notice)

        enqueueUser(account.user)
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("{}"))
        model.pollSettings()
        assertEquals(account.user, sessions.account.value!!.user)
        assertEquals(listOf("/api/auth/me", "/api/library/backup", "/api/health"), List(3) { takePath() })
        assertEquals(7, server.requestCount)
    }

    @Test fun userRefreshPreservesSessionAndPlaybackAndSurvivesReload() {
        sessions.save(account)
        sessions.playback.saveSelectedLibrary(account, "playlist")
        val playback = SavedPlayback(listOf(Track("job", "song.mp3")), position = 12_345)
        sessions.playback.savePlayback(account, playback)
        val user = account.user.copy(name = "Renamed", role = "shared", status = "pending", sharedUserIds = listOf("owner"))
        assertTrue(sessions.updateUser(account, user))
        val updated = account.copy(user = user)
        assertEquals(updated, sessions.account.value)
        assertEquals(updated, SessionStore(context).account.value)
        assertEquals("playlist", sessions.playback.selectedLibrary(updated))
        assertEquals(playback, sessions.playback.playback(updated))
        assertTrue(sessions.updateUser(updated, user))
        assertFalse(sessions.updateUser(account, account.user))
    }

    @Test fun lateUserResponsesCannotRestoreOrReplaceAnotherSession() {
        sessions.save(account)
        val shared = account.user.copy(role = "shared")
        sessions.clear()
        assertFalse(sessions.updateUser(account, shared))
        assertNull(sessions.account.value)
        val replacement = account.copy(session = account.session.copy(token = "N".repeat(43)))
        sessions.save(replacement)
        assertFalse(sessions.updateUser(account, shared))
        assertFalse(sessions.updateUser(replacement, shared.copy(id = "other-user")))
        assertEquals(replacement, sessions.account.value)
    }

    @Test fun startupAndRefreshEnableTranscriptionOnlyForActiveHealth() {
        val response = AtomicReference(200 to """{"transcription":{"status":"active"}}""")
        val model = startModel { request ->
            if (request.requestUrl!!.encodedPath == "/api/health") response.get().let { (code, body) ->
                MockResponse().setResponseCode(code).setBody(body)
            } else null
        }
        assertTrue(model.transcriptionAvailable)
        assertEquals(1, drainRequests().count { it.path == "/api/health" && it.method == "GET" })

        val cases = listOf(
            Triple(200, """{"status":"active"}""", false),
            Triple(200, """{"transcription":{"status":"unknown"}}""", false),
            Triple(200, """{"transcription":{"status":"inactive"}}""", false),
            Triple(200, """{"transcription":{"status":"active"}}""", true),
            Triple(503, """{"error":"Health unavailable"}""", false)
        )
        cases.forEach { (code, body, available) ->
            response.set(code to body)
            perform(model) { model.refresh() }
            assertEquals(body, available, model.transcriptionAvailable)
            assertEquals(1, drainRequests().count { it.path == "/api/health" && it.method == "GET" })
            assertNull(model.notice)
        }
        assertNull(model.health)
    }

    @Test fun sharedStartupAndRefreshNeverRequestHealth() {
        val model = startModel(user = account.user.copy(role = "shared", sharedUserIds = listOf("owner")))
        assertNull(model.health)
        assertFalse(drainRequests().any { it.path == "/api/health" })

        perform(model) { model.refresh() }
        val requests = drainRequests()
        assertTrue(requests.any { it.path == "/api/library" })
        assertTrue(requests.any { it.path!!.startsWith("/api/library/tracks?") })
        assertFalse(requests.any { it.path == "/api/health" })
        assertNull(model.health)
        assertNull(model.notice)
    }

    @Test fun lockingAndUnlockingPatchMetadataWithoutTranscribingOrLosingPlaybackPermission() {
        val track = Track("job", "song.mp3", title = "Song")
        val original = SongMetadata(title = "Song", artist = "Artist", rating = 2,
            sylt = listOf(LyricLine(1.5, "Timed lyrics")), uslt = "Original lyrics", canEdit = true)
        val saved = AtomicReference(original)
        val path = "/api/jobs/job/files/song.mp3/metadata"
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(track, "lyrics") -> MockResponse().setBody(ApiJson.encodeToString(original))
                path -> metadataResponse(saved.get())
                "/api/library/tracks" -> MockResponse().setBody(ApiJson.encodeToString(
                    TrackPage(files = listOf(track.copy(transcriptionLocked = saved.get().transcriptionLocked)))))
                else -> null
            }
        }
        showTrack(model, track)
        assertEquals(original, model.metadata)
        drainRequests()

        val locked = original.copy(transcriptionLocked = true, uslt = "Server lyrics")
        saved.set(locked)
        perform(model) { model.lockTranscription(track, true) }
        val lockRequests = drainRequests()
        assertPatch(lockRequests.single { it.path == path }, path, """{"transcriptionLocked":true}""")
        assertFalse(lockRequests.any { it.path!!.endsWith("/transcribe") })
        assertTrue(model.library.transcriptionLocked(track))
        assertEquals(locked, model.metadata)

        val unlocked = locked.copy(title = "Server title", rating = 4, transcriptionLocked = false)
        saved.set(unlocked)
        perform(model) {
            model.saveMetadata(track, locked.copy(title = "Edited title", rating = 4), transcriptionLocked = false)
        }
        val unlockRequests = drainRequests()
        assertPatch(unlockRequests.single { it.path == path }, path,
            """{"title":"Edited title","artist":"Artist","album":"","genre":"","year":"","rating":4,"transcriptionLocked":false}""")
        assertFalse(unlockRequests.any { it.path!!.endsWith("/transcribe") })
        assertFalse(model.library.transcriptionLocked(track))
        assertEquals(unlocked, model.metadata)
    }

    @Test fun savingLyricsPatchesOnlySuppliedFieldsAndRetainsPlaybackPermission() {
        val track = Track("job", "song.mp3")
        val original = SongMetadata(title = "Song", sylt = listOf(LyricLine(1.0, "Old timed")),
            uslt = "Old plain", canEdit = true)
        val saved = AtomicReference(original)
        val path = "/api/jobs/job/files/song.mp3/metadata"
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(track, "lyrics") -> MockResponse().setBody(ApiJson.encodeToString(original))
                path -> metadataResponse(saved.get())
                else -> null
            }
        }
        showTrack(model, track)
        drainRequests()

        val patches = listOf(
            """{"sylt":[{"time":2.25,"text":"New timed"}]}""" to
                original.copy(sylt = listOf(LyricLine(2.25, "New timed"))),
            """{"uslt":"New plain"}""" to
                original.copy(sylt = listOf(LyricLine(2.25, "New timed")), uslt = "New plain"),
            """{"sylt":[],"uslt":""}""" to original.copy(sylt = emptyList(), uslt = "")
        )
        patches.forEach { (body, result) ->
            saved.set(result)
            var successes = 0
            perform(model) {
                model.saveLyrics(track, ApiJson.parseToJsonElement(body).jsonObject) { successes++ }
            }
            val requests = drainRequests()
            assertPatch(requests.single { it.path == path }, path, body)
            assertTrue(requests.any { it.method == "GET" && it.path!!.startsWith("/api/library/tracks?") })
            assertEquals(1, successes)
            assertEquals(result, model.metadata)
            assertEquals("Lyrics saved.", model.notice)
        }
    }

    @Test fun savingAnotherSongsLyricsDoesNotReplaceCurrentPlaybackMetadata() {
        val current = Track("job", "current.mp3")
        val other = Track("job", "other.mp3")
        val original = SongMetadata(title = "Current song", uslt = "Current lyrics", canEdit = true)
        val path = "/api/jobs/job/files/other.mp3/metadata"
        val model = startModel(current) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(current, "lyrics") -> MockResponse().setBody(ApiJson.encodeToString(original))
                path -> metadataResponse(SongMetadata(title = "Other song", uslt = "Edited lyrics",
                    transcriptionLocked = true))
                else -> null
            }
        }
        showTrack(model, current)
        drainRequests()

        var succeeded = false
        val body = """{"uslt":"Edited lyrics"}"""
        perform(model) {
            model.saveLyrics(other, ApiJson.parseToJsonElement(body).jsonObject) { succeeded = true }
        }
        val requests = drainRequests()
        assertPatch(requests.single { it.path == path }, path, body)
        assertTrue(requests.any { it.method == "GET" && it.path!!.startsWith("/api/library/tracks?") })
        assertTrue(succeeded)
        assertEquals(original, model.metadata)
        assertTrue(model.library.transcriptionLocked(other))
    }

    @Test fun failedLyricsPatchDoesNotChangeMetadataOrReportSuccess() {
        val track = Track("job", "song.mp3")
        val original = SongMetadata(title = "Song", uslt = "Original lyrics", canEdit = true)
        val path = "/api/jobs/job/files/song.mp3/metadata"
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(track, "lyrics") -> MockResponse().setBody(ApiJson.encodeToString(original))
                path -> MockResponse().setResponseCode(403).setBody("""{"error":"Lyrics update denied."}""")
                else -> null
            }
        }
        showTrack(model, track)
        drainRequests()

        var succeeded = false
        val body = """{"uslt":"Unsaved lyrics"}"""
        perform(model) {
            model.saveLyrics(track, ApiJson.parseToJsonElement(body).jsonObject) { succeeded = true }
        }
        assertPatch(drainRequests().single(), path, body)
        assertFalse(succeeded)
        assertEquals(original, model.metadata)
        assertEquals("Lyrics update denied.", model.notice)
    }

    @Test fun removalNoticeDistinguishesDeletedFileFromPlaylistUnlinkAndFailure() {
        val track = Track("job", "song.mp3", title = "Named Song", playlistId = "playlist")
        val response = AtomicReference(200 to """{"fileDeleted":true}""")
        val path = "/api/library/songs/remove"
        val model = startModel(track) { request ->
            if (request.requestUrl!!.encodedPath == path) response.get().let { (code, body) ->
                MockResponse().setResponseCode(code).setBody(body)
            } else null
        }
        drainRequests()

        listOf(true, false).forEach { fileDeleted ->
            response.set(200 to """{"fileDeleted":$fileDeleted}""")
            perform(model) { model.remove(track) }
            val request = drainRequests().single { it.path == path }
            assertEquals("POST", request.method)
            assertEquals(ApiJson.parseToJsonElement(
                """{"jobId":"job","name":"song.mp3","playlistId":"playlist","version":7}"""),
                ApiJson.parseToJsonElement(request.body.readUtf8()))
            val notice = model.notice!!
            assertTrue(notice.contains(track.displayTitle))
            assertEquals(fileDeleted, notice.contains("deleted", ignoreCase = true))
            if (!fileDeleted) assertTrue(notice.contains("from the playlist"))
        }

        response.set(403 to """{"error":"Removal denied."}""")
        perform(model) { model.remove(track) }
        assertEquals(path, drainRequests().single().path)
        assertEquals("Removal denied.", model.notice)
    }

    @Test fun inactiveServiceAndCachedSongLockPreventTranscriptionRequests() {
        val track = Track("job", "song.mp3")
        val status = AtomicReference("inactive")
        val listedTrack = AtomicReference(track)
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                "/api/health" -> MockResponse().setBody("""{"transcription":{"status":"${status.get()}"}}""")
                "/api/library/tracks" -> MockResponse().setBody(ApiJson.encodeToString(
                    TrackPage(files = listOf(listedTrack.get()))))
                else -> null
            }
        }
        drainRequests()

        perform(model) { model.transcribe(track, TranscriptionOptions()) }
        assertEquals(INACTIVE_TRANSCRIPTION_MESSAGE, model.notice)
        assertTrue(drainRequests().isEmpty())
        assertTrue(model.library.pendingTranscriptions.isEmpty())
        perform(model) { model.transcribe(track, TranscriptionOptions(noVocalsOnly = true)) }
        assertEquals(INACTIVE_TRANSCRIPTION_MESSAGE, model.notice)
        assertTrue(drainRequests().isEmpty())

        status.set("active")
        listedTrack.set(track.copy(transcriptionLocked = true))
        perform(model) { model.refresh() }
        assertTrue(model.transcriptionAvailable)
        assertTrue(model.library.transcriptionLocked(track))
        drainRequests()

        perform(model) { model.transcribe(track, TranscriptionOptions()) }
        assertEquals("Transcription is locked for this song.", model.notice)
        assertTrue(drainRequests().isEmpty())
        assertTrue(model.library.pendingTranscriptions.isEmpty())
    }

    @Test fun noVocalsOnlyAllowsLockedSongsWithoutSendingLyricsOrChangingTheirLock() {
        val track = Track("job", "song.mp3", transcriptionLocked = true)
        val path = "/api/jobs/${encode(track.jobId)}/files/${encode(track.name)}/transcribe"
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                path, "/api/jobs/job" -> MockResponse().setBody("""{"id":"job"}""")
                else -> null
            }
        }
        drainRequests()

        perform(model) {
            model.transcribe(track, TranscriptionOptions(noVocalsOnly = true,
                addLyrics = true, lyrics = "Must not send these lyrics", multilingual = true, noVocals = true))
        }
        val requests = drainRequests()
        val transcription = requests.single { it.path == path }
        assertEquals("POST", transcription.method)
        assertEquals(ApiJson.parseToJsonElement("""{"NoVocalsOnly":true}"""),
            ApiJson.parseToJsonElement(transcription.body.readUtf8()))
        assertFalse(requests.any { it.method == "PATCH" })
        assertTrue(model.library.transcriptionLocked(track))
        assertTrue(model.library.pendingTranscriptions.isEmpty())
        assertEquals("NoVocals version generated.", model.notice)
    }

    private fun startModel(
        track: Track = Track("job", "song.mp3"),
        user: User = account.user,
        response: (RecordedRequest) -> MockResponse? = { null }
    ): MusicViewModel {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = response(request)
                ?: if (request.method != "GET") MockResponse().setResponseCode(404)
                else when (request.requestUrl!!.encodedPath) {
                    "/api/auth/me" -> MockResponse().setBody(ApiJson.encodeToString(UserResponse(user)))
                    "/api/preferences" -> MockResponse().setBody("{}")
                    "/api/library" -> MockResponse().setBody("""{"version":7,"songCount":1}""")
                    "/api/library/tracks" -> MockResponse().setBody(ApiJson.encodeToString(TrackPage(files = listOf(track))))
                    "/api/health" -> MockResponse().setBody("""{"transcription":{"status":"active"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
        }
        sessions.save(account.copy(user = user))
        val model = createModel(collectSession = true, connectPlayback = false)
        waitFor {
            model.library.tracks.files.size == 1 && !model.library.loading &&
                (user.isShared || model.health != null)
        }
        assertNull(model.notice)
        return model
    }

    private fun showTrack(model: MusicViewModel, track: Track) {
        compose.runOnUiThread {
            ReflectionHelpers.getField<MutableStateFlow<PlaybackState>>(model.playback, "mutableState").value =
                PlaybackState(track = track, queue = listOf(track))
        }
        waitFor { model.metadata != null }
    }

    private fun perform(model: MusicViewModel, action: () -> Unit) {
        compose.runOnUiThread { model.message(null); action() }
        waitFor { !model.busy && !model.library.loading }
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private fun drainRequests(): List<RecordedRequest> =
        generateSequence { server.takeRequest(0, TimeUnit.MILLISECONDS) }.toList()

    private fun metadataResponse(value: SongMetadata): MockResponse = MockResponse().setBody(
        JsonObject(ApiJson.encodeToJsonElement(value).jsonObject - "canEdit").toString())

    private fun assertPatch(request: RecordedRequest, path: String, body: String) {
        assertEquals("PATCH", request.method)
        assertEquals(path, request.path)
        assertEquals(ApiJson.parseToJsonElement(body), ApiJson.parseToJsonElement(request.body.readUtf8()))
    }

    private fun createModel(collectSession: Boolean = false, connectPlayback: Boolean = true): MusicViewModel {
        lateinit var model: MusicViewModel
        compose.runOnUiThread {
            model = MusicViewModel(application, connectPlayback)
            models.put("music", model)
            if (!collectSession) model.viewModelScope.cancel()
        }
        return model
    }

    private fun enqueueUser(user: User) {
        server.enqueue(MockResponse().setBody(ApiJson.encodeToString(UserResponse(user))))
    }

    private fun takePath(): String = server.takeRequest(2, TimeUnit.SECONDS)!!.path!!
}

class AccountRefreshKeyStore : KeyStoreSpi() {
    override fun engineGetKey(alias: String?, password: CharArray?): Key = key
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String?): Certificate? = null
    override fun engineGetCreationDate(alias: String?): Date = Date(0)
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = Unit
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
    override fun engineDeleteEntry(alias: String?) = Unit
    override fun engineAliases() = Collections.enumeration(listOf("ssmusic.session"))
    override fun engineContainsAlias(alias: String?) = alias == "ssmusic.session"
    override fun engineSize() = 1
    override fun engineIsKeyEntry(alias: String?) = engineContainsAlias(alias)
    override fun engineIsCertificateEntry(alias: String?) = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit

    companion object {
        private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
    }
}
