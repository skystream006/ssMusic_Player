package com.ssytdlp.app

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.AuthProtocol
import com.ssytdlp.app.core.LoginResponse
import com.ssytdlp.app.core.Job
import com.ssytdlp.app.core.Library
import com.ssytdlp.app.core.LibraryEntry
import com.ssytdlp.app.core.LinkedVideo
import com.ssytdlp.app.core.LyricLine
import com.ssytdlp.app.core.Preferences
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.SongMetadata
import com.ssytdlp.app.core.SongVideo
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.TrackSort
import com.ssytdlp.app.core.User
import com.ssytdlp.app.core.UserResponse
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import org.junit.rules.TemporaryFolder
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowPausedAsyncTask
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MusicApplication::class)
class AccountRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val replacementDocuments = TemporaryFolder()
    private val models = ViewModelStore()
    private lateinit var context: Context
    private lateinit var application: MusicApplication
    private lateinit var server: MockWebServer
    private lateinit var account: Account
    private lateinit var serverTls: HandshakeCertificates
    private val sessions get() = application.sessions

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("private_session", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
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

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun boundCarServiceTracksPhoneSignInAndSignOutWithoutAutoplay() {
        compose.runOnUiThread {
            val action = Intent("android.media.browse.MediaBrowserService").setPackage(context.packageName)
            assertTrue(context.packageManager.queryIntentServices(action, 0)
                .any { it.serviceInfo.name == PlaybackService::class.java.name })
            val voice = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName)
            assertEquals(MainActivity::class.java.name, context.packageManager.resolveActivity(voice, 0)?.activityInfo?.name)
            val service = Robolectric.buildService(PlaybackService::class.java).create()
            try {
                assertNotNull(service.get().onBind(action))
                val host = ControllerInfo.createTestOnlyControllerInfo("car", 1, 2, 0, 0, true, Bundle.EMPTY)
                val session = service.get().onGetSession(host)!!
                val signedOut = session.player
                sessions.save(account)
                shadowOf(Looper.getMainLooper()).idle()
                val player = session.player
                assertNotSame(signedOut, player)
                player.setMediaItem(Track("job", "song.mp3", streamUrl = "/api/stream/song",
                    linkedVideo = LinkedVideo("https://example.com/video", "completed", streamUrl = "/api/jobs/job/video/song.mp3"))
                    .toMediaItem(application.api))
                assertTrue(sessions.updateUser(account, account.user.copy(name = "Updated")))
                shadowOf(Looper.getMainLooper()).idle()
                assertSame(player, session.player)
                assertEquals(1, player.mediaItemCount)
                assertEquals("Use video audio", session.mediaButtonPreferences.single().displayName)
                assertFalse(player.playWhenReady)
                LauncherIcon.update(context, Preferences("gold", "dark"), waveAppearance = false)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(resourceArtwork(R.mipmap.ic_launcher_gold_dark), player.currentMediaItem!!.mediaMetadata.artworkUri)
                sessions.clear()
                shadowOf(Looper.getMainLooper()).idle()
                assertSame(session, service.get().onGetSession(host))
                assertEquals(0, session.player.mediaItemCount)
                assertTrue(session.mediaButtonPreferences.isEmpty())
                assertFalse(session.player.playWhenReady)
                assertEquals(0, server.requestCount)
            } finally { service.destroy() }
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun carProviderServesPrivatePlaylistAndTrackCoversAndRejectsOldSessions() {
        sessions.save(account)
        LauncherIcon.update(context, Preferences("pink", "dark"), waveAppearance = false)
        val art = CarArtwork(account, R.mipmap.ic_launcher_pink_dark)
        val source = "/api/jobs/source/artwork/%5BNoVocals%5D%2Fa%20%26%20b.mp3?v=2&userId=shared"
        val track = Track("source", "[NoVocals]/a & b.mp3", streamUrl = "/api/stream/song", artworkUrl = source)
        val thumbnail = ByteArrayOutputStream().use { out ->
            val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.getHeader("Authorization") != "Bearer ${account.session.token}" -> MockResponse().setResponseCode(401)
                request.path == source -> MockResponse().setBody(okio.Buffer().write(thumbnail))
                request.requestUrl!!.encodedPath == "/api/library/tracks" -> MockResponse().setBody(ApiJson.encodeToString(
                    TrackPage(files = if (request.requestUrl!!.queryParameter("entryId") == "mix") listOf(track) else emptyList())))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val resolver = context.contentResolver
        fun bytes(uri: Uri): ByteArray {
            // Robolectric backs pipes with regular files: finish the framework's pipe writer before reading.
            ShadowPausedAsyncTask.overrideExecutor(Runnable::run)
            return try { resolver.openInputStream(uri)!!.use { it.readBytes() } } finally { ShadowPausedAsyncTask.reset() }
        }
        val cover = art.track(track)
        for (uri in listOf(art.playlist("mix", 1), cover)) {
            assertEquals("content", uri.scheme)
            assertFalse(uri.toString().contains(account.session.token))
            val data = bytes(uri)
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)!!
            assertEquals(3, bitmap.width)
            assertEquals(android.graphics.Color.GREEN, bitmap.getPixel(0, 0))
            bitmap.recycle()
        }
        val logo = context.resources.openRawResource(R.mipmap.ic_launcher_pink_dark).use { it.readBytes() }
        assertArrayEquals(logo, bytes(art.playlist("empty", 1)))
        assertArrayEquals(logo, bytes(art.track(track.copy(artworkUrl = source.replace("v=2", "v=missing")))))
        assertEquals(5, server.requestCount)
        sessions.save(account.copy(session = account.session.copy(token = "new-session")))
        assertThrows(FileNotFoundException::class.java) { resolver.openFileDescriptor(cover, "r")?.close() }
        assertEquals(5, server.requestCount)
    }

    @Test fun lanConfigurationAndSeparateSessionPersistEncryptedWithoutReplacingPrimary() {
        sessions.save(account)
        sessions.configureLanPlayback("music.lan.example:8443")
        val config = sessions.lanPlayback.value!!
        val lan = Account(config.origin, User("lan-listener", "LAN Listener"),
            Session("L".repeat(43), "2099-01-01T00:00:00Z"))
        sessions.saveLanAccount(account, config, lan)
        val restored = SessionStore(context)
        assertEquals(account, restored.account.value)
        assertEquals(config.copy(account = lan), restored.lanPlayback.value)
        val stored = context.getSharedPreferences("private_session", Context.MODE_PRIVATE).getString("lan_playback", "")!!
        assertFalse(stored.contains(lan.session.token))
        assertFalse(stored.contains(config.origin))
        restored.clearLanSession("stale-token")
        assertEquals(lan, restored.lanPlayback.value!!.account)
        restored.clearLanSession(lan.session.token)
        assertNull(restored.lanPlayback.value!!.account)
        assertEquals(account, restored.account.value)
        restored.clear()
        assertNull(SessionStore(context).lanPlayback.value)
    }

    @Test fun changingLANHostnameOrPrimarySessionRejectsLateLANSignIns() {
        sessions.save(account)
        sessions.configureLanPlayback("first.lan.example")
        val config = sessions.lanPlayback.value!!
        val lan = account.copy(origin = config.origin)
        sessions.saveLanAccount(account, config, lan)
        sessions.configureLanPlayback("second.lan.example")
        assertNull(sessions.lanPlayback.value!!.account)
        assertThrows(IllegalStateException::class.java) { sessions.saveLanAccount(account, config, lan) }
        for (invalid in listOf("http://music.lan.example", "https://192.168.1.5", "https://music.lan.example/api/")) {
            assertThrows(IllegalArgumentException::class.java) { sessions.configureLanPlayback(invalid) }
        }
        sessions.save(account.copy(session = account.session.copy(token = "N".repeat(43))))
        assertNull(sessions.lanPlayback.value)
        assertThrows(IllegalStateException::class.java) { sessions.saveLanAccount(account, config, lan) }
    }

    @Test fun LANPasskeyCallbackUsesSavedHostnameAndSeparateSessionWithoutReloadingPrimaryLibrary() {
        val model = startModel()
        drainRequests()
        application.serverConfig.set(account.origin)
        val library = model.library
        MockWebServer().use { lan ->
            lan.useHttps(serverTls.sslSocketFactory(), false)
            lan.start(InetAddress.getByName("127.0.0.1"), 0)
            val origin = lan.url("/").newBuilder().host("localhost").build().toString().trimEnd('/')
            lateinit var login: String
            compose.runOnUiThread {
                assertTrue(model.configureLanPlayback(origin))
                login = model.beginLanLogin()!!
            }
            assertTrue(login.startsWith("$origin/app-login?"))
            assertTrue(model.lanLoginPending)
            val pending = sessions.pending!!
            assertEquals(AuthProtocol.challenge(pending.verifier), Uri.parse(login).getQueryParameter("code_challenge"))
            val code = "C".repeat(43)
            compose.runOnUiThread {
                model.callback("${AuthProtocol.REDIRECT_URI}?code=$code&state=${"X".repeat(43)}")
            }
            assertEquals(0, lan.requestCount)
            assertEquals(account, sessions.account.value)
            val response = LoginResponse(User("lan-user", "LAN Listener"),
                Session("L".repeat(43), "2099-01-01T00:00:00Z"))
            lan.enqueue(MockResponse().setBody(ApiJson.encodeToString(response)))
            compose.runOnUiThread {
                model.callback("${AuthProtocol.REDIRECT_URI}?code=$code&state=${pending.state}")
            }
            waitFor { !model.signingIn && sessions.lanPlayback.value?.account != null }
            val request = lan.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/api/auth/app/token", request.path)
            assertNull(request.getHeader("Authorization"))
            val body = ApiJson.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(json("code" to code, "codeVerifier" to pending.verifier,
                "redirectUri" to AuthProtocol.REDIRECT_URI), body)
            assertEquals(account, sessions.account.value)
            assertEquals(response.session, sessions.lanPlayback.value!!.account!!.session)
            assertFalse(model.lanLoginPending)
            assertEquals(library, model.library)
            assertTrue(drainRequests().isEmpty())
        }
    }

    @Test fun failedLANPasskeyExchangeKeepsPrimarySignedInAndDoesNotSaveLANSession() {
        val model = startModel()
        drainRequests()
        application.serverConfig.set(account.origin)
        MockWebServer().use { lan ->
            lan.useHttps(serverTls.sslSocketFactory(), false)
            lan.start(InetAddress.getByName("127.0.0.1"), 0)
            val origin = lan.url("/").newBuilder().host("localhost").build().toString().trimEnd('/')
            compose.runOnUiThread {
                model.configureLanPlayback(origin)
                model.beginLanLogin()
            }
            val pending = sessions.pending!!
            lan.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"LAN passkey exchange expired."}"""))
            compose.runOnUiThread {
                model.callback("${AuthProtocol.REDIRECT_URI}?code=${"C".repeat(43)}&state=${pending.state}")
            }
            waitFor { !model.signingIn }
            assertEquals(account, sessions.account.value)
            assertNull(sessions.lanPlayback.value!!.account)
            assertEquals("LAN passkey exchange expired.", model.notice)
            assertTrue(drainRequests().isEmpty())
        }
    }

    @Test fun pageSizeDefaultsPersistAcrossRecreationAndInvalidPreferencesFallBackTo50() {
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settings.edit().remove("items_per_page").commit()
        try {
            var model = createModel(connectPlayback = false)
            assertEquals(LibraryPageSize.DEFAULT, model.library.pageSize)
            assertFalse(settings.contains("items_per_page"))
            for (size in LibraryPageSize.entries.reversed()) {
                compose.runOnUiThread { model.choosePageSize(size) }
                assertEquals(size.items, settings.getInt("items_per_page", -1))
                model = createModel(connectPlayback = false)
                assertEquals(size, model.library.pageSize)
            }
            listOf(0, -1, 75, 1001, Int.MAX_VALUE).forEach {
                settings.edit().putInt("items_per_page", it).commit()
                assertEquals(LibraryPageSize.DEFAULT, createModel(connectPlayback = false).library.pageSize)
            }
            settings.edit().putString("items_per_page", "invalid").commit()
            assertEquals(LibraryPageSize.DEFAULT, createModel(connectPlayback = false).library.pageSize)
        } finally {
            settings.edit().remove("items_per_page").commit()
        }
    }

    @Test fun linkedVideoDownloadsUseTheSongEndpointAndRetryFailuresWithoutChangingTheQueue() {
        val source = "https://www.youtube.com/watch?v=abcdefghijk"
        val job = Job("source /?#", initiatedBy = account.user)
        val track = Track(job.id, "folder/song #+.mp3", videoUrl = source, sourceJob = job)
        val video = AtomicReference(SongVideo(source, null))
        val model = startModel(track = track) { request ->
            when (request.requestUrl!!.encodedPath) {
                songVideoPath(track) -> {
                    if (request.method == "POST") video.set(SongVideo(source, LinkedVideo(source, "queued")))
                    MockResponse().setResponseCode(if (request.method == "POST") 202 else 200)
                        .setBody(ApiJson.encodeToString(video.get()))
                }
                songPath(track, "lyrics") -> metadataResponse(SongMetadata(videoUrl = source))
                else -> null
            }
        }
        val before = PlaybackState(track = track, queue = listOf(track, Track("other", "next.mp3")), position = 42_000)
        setPlayback(model, before)
        waitFor { model.metadata != null }
        drainRequests()
        perform(model) { model.downloadSongVideo(track) }
        val post = drainRequests().single()
        assertEquals("POST", post.method)
        assertEquals(songVideoPath(track), post.path)
        assertEquals(listOf("Bearer", account.session.token).joinToString(" "), post.getHeader("Authorization"))
        assertEquals("{}", post.body.readUtf8())
        assertTrue(model.library.songVideo(track).linkedVideo!!.active)
        perform(model) { model.downloadSongVideo(track) }
        assertTrue(model.notice!!.contains("already running"))
        assertTrue(drainRequests().isEmpty())
        video.set(SongVideo(source, LinkedVideo(source, "failed", error = "Download failed")))
        runBlocking { model.refreshSongVideo(track) }
        assertEquals("Download failed", model.library.songVideo(track).linkedVideo!!.error)
        drainRequests()
        perform(model) { model.downloadSongVideo(track) }
        assertEquals("POST", drainRequests().single().method)
        video.set(SongVideo(source, LinkedVideo(source, "completed", streamUrl = "/api/jobs/source/video/song.mp3?v=2")))
        runBlocking { model.refreshSongVideo(track) }
        assertTrue(model.library.songVideo(track).available)
        assertEquals(before, model.playback.state.value)
    }

    @Test fun songVideoDialogConfirmsTheDownloadTracksProgressAndRetriesFailures() {
        val source = "https://www.youtube.com/watch?v=abcdefghijk"
        val job = Job("source", initiatedBy = account.user)
        val track = Track(job.id, "song.mp3", videoUrl = source, sourceJob = job)
        val video = AtomicReference(SongVideo(null, null))
        val posts = AtomicInteger()
        val model = startModel(track = track) { request ->
            if (request.requestUrl!!.encodedPath != songVideoPath(track)) null
            else {
                if (request.method == "POST") {
                    posts.incrementAndGet()
                    video.set(SongVideo(source, LinkedVideo(source, "queued")))
                }
                MockResponse().setResponseCode(if (request.method == "POST") 202 else 200)
                    .setBody(ApiJson.encodeToString(video.get()))
            }
        }
        var closed = false
        compose.setContent { MusicTheme { SongVideoDialog(model, track) { closed = true } } }
        compose.onNodeWithText("Download video").assertIsEnabled().performClick()
        waitFor { model.library.songVideo(track).linkedVideo?.active == true && model.pendingSongs.isEmpty() }
        compose.onNodeWithText("Video download queued. You can close this dialog.").assertIsDisplayed()
        compose.onNodeWithText("Download video").assertIsNotEnabled()
        compose.onNodeWithText("Close").assertIsEnabled()
        assertEquals(1, posts.get())
        video.set(SongVideo(source, LinkedVideo(source, "failed", error = "Download failed")))
        waitFor { model.library.songVideo(track).linkedVideo?.status == "failed" }
        compose.onNodeWithText("Download failed").assertIsDisplayed()
        compose.onNodeWithText("Retry download").assertIsEnabled().performClick()
        waitFor { posts.get() == 2 && model.library.songVideo(track).linkedVideo?.active == true }
        video.set(SongVideo(source, LinkedVideo(source, "completed", streamUrl = "/api/jobs/source/video/song.mp3?v=2")))
        waitFor { model.library.songVideo(track).available }
        compose.onNodeWithText("Video ready. Select Video in the player.").assertIsDisplayed()
        compose.onNodeWithText("Download video").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        assertTrue(closed)
    }

    @Test fun videoStatusWithoutACatalogSourcePreservesTheUrlDiscoveredInSongTags() = runBlocking {
        val source = "https://www.youtube.com/watch?v=abcdefghijk"
        val track = Track("source", "song.mp3", videoUrl = source,
            sourceJob = Job("source", initiatedBy = account.user))
        val model = startModel(track = track) { request ->
            if (request.requestUrl!!.encodedPath != songVideoPath(track)) null
            else MockResponse().setBody(ApiJson.encodeToString(
                if (request.method == "GET") SongVideo(null, null) else SongVideo(source, LinkedVideo(source, "queued"))))
        }
        model.refreshSongVideo(track)
        assertTrue(model.canDownloadSongVideo(track))
        assertEquals(source, model.library.songVideo(track).videoUrl)
        drainRequests()
        perform(model) { model.downloadSongVideo(track) }
        assertEquals("POST", drainRequests().single().method)
        assertTrue(model.library.songVideo(track).linkedVideo!!.active)
    }

    @Test fun staleVideoStatusCannotUndoADownloadAndDeniedOrMalformedDownloadsStayExplicit() = runBlocking {
        val source = "https://www.youtube.com/watch?v=abcdefghijk"
        val job = Job("source", initiatedBy = account.user)
        val track = Track(job.id, "song.mp3", videoUrl = source, sourceJob = job)
        val readStarted = CountDownLatch(1)
        var postStatus = 202
        var postBody = ApiJson.encodeToString(SongVideo(source, LinkedVideo(source, "queued")))
        val model = startModel(track = track) { request ->
            if (request.requestUrl!!.encodedPath != songVideoPath(track)) null
            else if (request.method == "GET") {
                readStarted.countDown()
                MockResponse().setBody(ApiJson.encodeToString(SongVideo(source, null)))
                    .setBodyDelay(1, TimeUnit.SECONDS)
            } else MockResponse().setResponseCode(postStatus).setBody(postBody)
        }
        drainRequests()
        val stale = async(Dispatchers.IO) { model.refreshSongVideo(track) }
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))
        perform(model) { model.downloadSongVideo(track) }
        stale.await()
        assertTrue(model.library.songVideo(track).linkedVideo!!.active)
        for ((status, body) in listOf(403 to """{"error":"Only the owner may download"}""", 200 to "{}")) {
            compose.runOnUiThread {
                ReflectionHelpers.getField<androidx.compose.runtime.MutableState<LibraryState>>(model, "library\$delegate")
                    .value = model.library.withSongVideo(track, SongVideo(source, null))
            }
            postStatus = status
            postBody = body
            perform(model) { model.downloadSongVideo(track) }
            assertNotNull(model.notice)
            assertFalse(model.notice!!.contains("queued"))
            assertFalse(model.library.songVideo(track).available)
            assertNull(model.library.songVideo(track).linkedVideo)
        }
        sessions.clear()
        waitFor { model.library.tracks.files.isEmpty() }
        assertTrue(model.library.songVideos.isEmpty())
    }

    @Test fun playingAJobWithOnlyNoVocalsReportsNoPlayableOriginals() {
        val files = listOf(
            Track(name = "[NoVocals]/song.mp3", streamUrl = "/api/stream/song.mp3"),
            Track(name = "[novocals]/other.mp3", streamUrl = "/api/stream/other.mp3"),
            Track(name = "unavailable.mp3", streamUrl = null),
            Track(name = "notes.txt", isPlayable = false, streamUrl = "/api/stream/notes.txt"))
        val model = startModel { request ->
            if (request.requestUrl!!.encodedPath == "/api/jobs/job/files")
                MockResponse().setBody(ApiJson.encodeToString(TrackPage(files = files)))
            else null
        }

        perform(model) { model.playJob(Job("job")) }

        assertEquals("No playable files are available yet.", model.notice)
        assertTrue(model.playback.state.value.queue.isEmpty())
        assertNull(model.playback.controller)
    }

    @Test fun pageSizeAppliesToEverySelectionAndRefreshWithoutChangingPlaybackOrFetchingOnResize() {
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settings.edit().putInt("items_per_page", 100).commit()
        val files = (1..1205).map { Track("job", "$it.mp3", title = "Song ${it.toString().padStart(4, '0')}") }
        try {
            val model = startModel(expectedTracks = files.size) { request ->
                when (request.requestUrl!!.encodedPath) {
                    "/api/library" -> MockResponse().setBody(ApiJson.encodeToString(Library(entries = listOf(
                        LibraryEntry("playlist", "playlist"), LibraryEntry("folder", "folder")))))
                    "/api/library/tracks" -> {
                        val size = request.requestUrl!!.queryParameter("pageSize")?.toInt() ?: files.size
                        val page = request.requestUrl!!.queryParameter("page")?.toInt() ?: 1
                        MockResponse().setBody(ApiJson.encodeToString(TrackPage(
                            files = files.drop((page - 1) * size).take(size), page = page, pageSize = size,
                            total = files.size, totalPages = (files.size + size - 1) / size)))
                    }
                    songPath(files.first(), "lyrics") -> MockResponse().setBody(ApiJson.encodeToString(SongMetadata()))
                    else -> null
                }
            }
            assertEquals(LibraryPageSize.ITEMS_100, model.library.pageSize)
            assertEquals(100, model.library.orderedTracks.size)
            val playback = PlaybackState(track = files.first(), queue = files.take(3), position = 42_000)
            setPlayback(model, playback)
            waitFor { model.metadata != null }
            compose.runOnUiThread { model.sortLibrary(TrackSort.NAME) }
            drainRequests()
            for (size in LibraryPageSize.entries) {
                compose.runOnUiThread { model.page(2); model.choosePageSize(size) }
                assertEquals(1, model.library.currentPage)
                assertEquals(size.items, model.library.orderedTracks.size)
                assertEquals(TrackSort.NAME, model.library.sort)
                assertEquals(playback, model.playback.state.value)
                assertTrue(drainRequests().isEmpty())
            }
            for (selection in listOf("playlist", "folder", null)) {
                perform(model) { model.selectLibrary(selection) }
                assertEquals(selection, model.library.selectedId)
                assertEquals(LibraryPageSize.ITEMS_1000, model.library.pageSize)
                assertEquals(1000, model.library.orderedTracks.size)
                perform(model) { model.search("Song") }
                assertEquals("Song", model.library.search)
                assertEquals(1000, model.library.orderedTracks.size)
                compose.runOnUiThread { model.page(2) }
                assertEquals(files.drop(1000), model.library.orderedTracks)
                perform(model) { model.refresh() }
                assertEquals(2, model.library.currentPage)
                assertEquals(files.drop(1000), model.library.orderedTracks)
                assertEquals(playback, model.playback.state.value)
            }
            compose.runOnUiThread {
                model.refreshTracks()
                model.choosePageSize(LibraryPageSize.ITEMS_200)
            }
            waitFor { !model.library.loading }
            assertEquals(1, model.library.currentPage)
            assertEquals(200, model.library.orderedTracks.size)
            assertEquals(playback, model.playback.state.value)
            compose.runOnUiThread { sessions.clear() }
            waitFor { model.library.tracks.files.isEmpty() }
            assertEquals(LibraryPageSize.ITEMS_200, model.library.pageSize)
            compose.runOnUiThread { sessions.save(account) }
            waitFor { model.library.allPagesLoaded && !model.library.loading }
            assertEquals(200, model.library.orderedTracks.size)
            assertEquals(LibraryPageSize.ITEMS_200, model.library.pageSize)
        } finally {
            settings.edit().remove("items_per_page").commit()
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

    @Test fun legacyPorcelainIsOnlyReplacedOnExplicitThemeOrModeChanges() {
        val saved = AtomicReference(Preferences(theme = "light", mode = "dark"))
        val model = startModel { request ->
            if (request.requestUrl!!.encodedPath == "/api/preferences") {
                if (request.method == "PUT") saved.set(ApiJson.decodeFromString(request.body.clone().readUtf8()))
                MockResponse().setBody(ApiJson.encodeToString(saved.get()))
            } else null
        }
        assertEquals("green", model.preferences.effectiveTheme)
        assertEquals("dark", model.preferences.mode)
        assertTrue(drainRequests().all { it.method == "GET" })
        assertEquals("light", saved.get().theme)

        perform(model) { model.setTheme(mode = "light") }
        val update = drainRequests().single()
        assertEquals("PUT", update.method)
        assertEquals("/api/preferences", update.path)
        assertEquals(ApiJson.parseToJsonElement("""{"theme":"green","mode":"light"}"""),
            ApiJson.parseToJsonElement(update.body.readUtf8()))
        assertEquals(Preferences("green", "light"), model.preferences)

        perform(model) { model.setTheme(theme = "light", mode = "dark") }
        assertEquals(Preferences("green", "dark"), saved.get())
        drainRequests()
        perform(model) { model.setTheme(theme = "pink") }
        assertEquals(Preferences("pink", "dark"), model.preferences)
        assertNull(model.notice)
    }

    @Test fun launcherFollowsLoadedAndSavedAppearanceWithoutApplyingFailedThemeChanges() {
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settings.edit().putBoolean("wave_appearance", false).commit()
        val saved = AtomicReference(Preferences("midnight"))
        val rejectSave = AtomicBoolean(false)
        try {
            val model = startModel { request ->
                if (request.requestUrl!!.encodedPath != "/api/preferences") null
                else if (request.method == "PUT" && rejectSave.get()) MockResponse().setResponseCode(500)
                else {
                    if (request.method == "PUT") saved.set(ApiJson.decodeFromString(request.body.clone().readUtf8()))
                    MockResponse().setBody(ApiJson.encodeToString(saved.get()))
                }
            }
            fun assertIcon(alias: String) {
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
                assertEquals("${context.packageName}.$alias",
                    context.packageManager.queryIntentActivities(intent, 0).single().activityInfo.name)
            }
            assertIcon("LauncherMidnightDark")
            perform(model) { model.setTheme(theme = "royal-purple") }
            assertEquals(Preferences("royal-purple", "dark"), model.preferences)
            assertIcon("LauncherRoyalPurpleDark")
            perform(model) { model.setTheme(theme = "pink", mode = "light") }
            assertIcon("LauncherPinkLight")

            rejectSave.set(true)
            perform(model) { model.setTheme(theme = "green", mode = "dark") }
            assertNotNull(model.notice)
            assertEquals(Preferences("pink", "light"), model.preferences)
            assertIcon("LauncherPinkLight")
            rejectSave.set(false)

            compose.runOnUiThread { model.chooseWaveAppearance(true) }
            assertIcon("LauncherDefault")
            compose.runOnUiThread { model.chooseWaveAppearance(false) }
            assertIcon("LauncherPinkLight")
            val recreated = createModel(collectSession = true, connectPlayback = false)
            waitFor { recreated.preferences == saved.get() && !recreated.library.loading }
            assertIcon("LauncherPinkLight")
            assertNull(recreated.notice)
        } finally {
            settings.edit().remove("wave_appearance").commit()
        }
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

    @Test fun playerStarsSaveOnlyRatingAndClearWithoutOpeningADialogOrChangingPlayback() {
        verifyPlayerRatingSaves(account.user)
    }

    @Test fun sharedPlayerStarsSaveOnlyRatingAndClearWithoutMetadataEditingPermission() {
        verifyPlayerRatingSaves(User("reader", role = "Shared"))
    }

    private fun verifyPlayerRatingSaves(user: User) {
        val track = Track("job", "song.mp3", title = "Song", rating = 3)
        val next = track.copy(name = "next.mp3", title = "Next song", rating = 1)
        val files = listOf(track) + (1..49).map { track.copy(name = "filler$it.mp3") } + Track("other", "other.mp3")
        val original = SongMetadata(title = "Song", artist = "Artist", album = "Album", genre = "Pop",
            year = "2026", rating = 3, sylt = listOf(LyricLine(1.5, "Timed lyrics")), uslt = "Plain lyrics",
            transcriptionLocked = true, canEdit = !user.isShared)
        val saved = AtomicReference(original)
        val path = "/api/jobs/job/files/song.mp3/metadata"
        val nextPath = "/api/jobs/job/files/next.mp3/metadata"
        val model = startModel(track, user = user, expectedTracks = 51) { request ->
            when (request.requestUrl!!.encodedPath) {
                "/api/library" -> MockResponse().setBody(ApiJson.encodeToString(
                    Library(jobs = listOf(Job("job", initiatedBy = account.user)))))
                songPath(track, "lyrics") -> MockResponse().setBody(ApiJson.encodeToString(original))
                songPath(next, "lyrics") -> MockResponse().setBody(ApiJson.encodeToString(original.copy(rating = 1)))
                path, nextPath -> metadataResponse(saved.get())
                "/api/library/tracks" -> MockResponse().setBody(ApiJson.encodeToString(
                    if (request.requestUrl!!.queryParameter("page") == "2")
                        TrackPage(files = files.drop(50), page = 2, total = 51, totalPages = 2)
                    else TrackPage(files = files.take(50), total = 51, totalPages = 2)))
                else -> null
            }
        }
        showTrack(model, track)
        val playback = PlaybackState(track = track, queue = listOf(track, next), position = 42_000, duration = 60_000)
        setPlayback(model, playback)
        compose.setContent {
            MusicTheme { NowPlayingScreen(model, model.playback.state.collectAsState().value) { _, _ -> } }
        }
        compose.onNodeWithText("Player").performClick()
        // Browse another page so the original playback snapshot must use the saved rating cache.
        compose.runOnUiThread { model.page(2) }
        waitFor { !model.library.loading }
        assertEquals(listOf(files.last()), model.library.orderedTracks)
        drainRequests()

        listOf(5 to 5, 2 to 2, 2 to 0).forEach { (star, rating) ->
            saved.set(original.copy(rating = rating))
            compose.onNodeWithContentDescription("$star stars").assertIsEnabled().performClick()
            waitFor { model.library.rating(track) == rating && !model.songBusy(track) && !model.library.loading }
            compose.onNodeWithContentDescription("Rating: $rating out of 5").assertIsDisplayed()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithText("Save").assertDoesNotExist()
            compose.onNodeWithText("0:42").assertIsDisplayed()
            assertEquals(playback, model.playback.state.value)
            assertEquals(original.copy(rating = rating), model.metadata)
            assertPatch(drainRequests().single { it.method == "PATCH" }, path, """{"rating":$rating}""")
        }

        setPlayback(model, playback.copy(track = next, index = 1))
        waitFor { model.metadata?.rating == 1 }
        compose.onNodeWithContentDescription("Rating: 1 out of 5").assertIsDisplayed()
        saved.set(original.copy(rating = 4))
        compose.onNodeWithContentDescription("4 stars").performClick()
        waitFor { model.library.rating(next) == 4 && !model.songBusy(next) && !model.library.loading }
        assertEquals(0, model.library.rating(track))
        assertPatch(drainRequests().single { it.method == "PATCH" }, nextPath, """{"rating":4}""")
    }

    @Test fun failedInlineRatingSaveKeepsPreviousRatingAndAllowsRetry() {
        verifyFailedRatingSave(account.user)
    }

    @Test fun deniedSharedRatingSaveKeepsPreviousRatingAndAllowsRetry() {
        verifyFailedRatingSave(User("reader", role = "shared"))
    }

    private fun verifyFailedRatingSave(user: User) {
        val track = Track("job", "song.mp3", rating = 3)
        val original = SongMetadata(title = "Song", rating = 3, canEdit = !user.isShared)
        val status = AtomicInteger(403)
        val path = "/api/jobs/job/files/song.mp3/metadata"
        val model = startModel(track, user = user) { request ->
            when (request.requestUrl!!.encodedPath) {
                "/api/library" -> MockResponse().setBody(ApiJson.encodeToString(
                    Library(jobs = listOf(Job("job", initiatedBy = account.user)))))
                songPath(track, "lyrics") -> MockResponse().setBody(ApiJson.encodeToString(original))
                path -> if (status.get() == 200) metadataResponse(original.copy(rating = 5))
                    else MockResponse().setResponseCode(status.get()).setBody("""{"error":"Rating denied."}""")
                "/api/library/tracks" -> MockResponse().setBody(ApiJson.encodeToString(
                    TrackPage(files = listOf(track.copy(rating = if (status.get() == 200) 5 else 3)))))
                else -> null
            }
        }
        showTrack(model, track)
        compose.setContent {
            MusicTheme { NowPlayingScreen(model, model.playback.state.collectAsState().value) { _, _ -> } }
        }
        compose.onNodeWithText("Player").performClick()
        drainRequests()
        for (code in listOf(403, 409)) {
            status.set(code)
            compose.runOnUiThread { model.message(null) }
            compose.onNodeWithContentDescription("5 stars").performClick()
            waitFor { model.notice != null && !model.songBusy(track) }
            assertEquals("Rating denied.", model.notice)
            assertEquals(original, model.metadata)
            assertEquals(3, model.library.rating(track))
            compose.onNodeWithContentDescription("Rating: 3 out of 5").assertIsDisplayed()
            compose.onNode(isDialog()).assertDoesNotExist()
            assertPatch(drainRequests().single { it.method == "PATCH" }, path, """{"rating":5}""")
        }

        status.set(200)
        compose.onNodeWithContentDescription("5 stars").assertIsEnabled().performClick()
        waitFor { model.library.rating(track) == 5 && !model.songBusy(track) && !model.library.loading }
        compose.onNodeWithContentDescription("Rating: 5 out of 5").assertIsDisplayed()
        assertPatch(drainRequests().single { it.method == "PATCH" }, path, """{"rating":5}""")
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun sharedLibraryAndQueueRatingDialogsSaveOnlyRatingAndKeepOtherMutationsBlocked() {
        val track = Track("source job", "[NoVocals]/song.MP3", rating = 3)
        val original = SongMetadata(title = "Song", artist = "Artist", album = "Album", genre = "Pop",
            year = "2026", rating = 3, uslt = "Lyrics", transcriptionLocked = true, canEdit = false)
        val saved = AtomicReference(original)
        val path = "/api/jobs/source%20job/files/%5BNoVocals%5D%2Fsong.MP3/metadata"
        val model = startModel(track, user = User("reader", role = "SHARED")) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(track, "lyrics") -> MockResponse().setBody(ApiJson.encodeToString(saved.get()))
                path -> metadataResponse(saved.get())
                else -> null
            }
        }
        showTrack(model, track)
        val playback = model.playback.state.value
        val showLibrary = mutableStateOf(true)
        compose.setContent {
            MusicTheme {
                if (showLibrary.value) LibraryScreen(model, playback, {}) { _, _ -> }
                else NowPlayingScreen(model, playback) { _, _ -> }
            }
        }
        drainRequests()
        for ((library, rating) in listOf(true to 5, false to 0)) {
            compose.runOnUiThread { showLibrary.value = library }
            if (library) compose.onNodeWithContentDescription("Expand NoVocals").performClick()
            else compose.onNodeWithText("Queue").performClick()
            compose.onNodeWithContentDescription("Rating: ${model.library.rating(track)} out of 5").performClick()
            waitFor { compose.onAllNodesWithText("Rate song").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save").assertIsEnabled()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithContentDescription("Unlock transcription").assertDoesNotExist()
            compose.onNode(hasContentDescription("5 stars") and hasAnyAncestor(isDialog())).performClick()
            saved.set(original.copy(rating = rating))
            compose.onNodeWithText("Save").performClick()
            waitFor { model.library.rating(track) == rating && !model.songBusy(track) }
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithContentDescription("Rating: $rating out of 5").assertIsDisplayed()
            assertEquals(original.copy(rating = rating), model.metadata)
            assertEquals(playback, model.playback.state.value)
            assertPatch(drainRequests().single { it.method == "PATCH" }, path, """{"rating":$rating}""")
        }
        perform(model) { model.saveMetadata(track, original.copy(title = "Not allowed")) }
        assertEquals("This library is read-only.", model.notice)
        perform(model) { model.lockTranscription(track, false) }
        assertEquals("This library is read-only.", model.notice)
        perform(model) { model.saveLyrics(track, json("uslt" to "Not allowed")) {} }
        assertEquals("This library is read-only.", model.notice)
        for (rating in listOf(-1, 6)) {
            perform(model) { model.saveRating(track, rating) }
            assertEquals("Rating must be between 0 and 5.", model.notice)
        }
        assertFalse(drainRequests().any { it.method != "GET" })
        assertEquals(0, model.library.rating(track))
    }

    @Test fun songSavesPreviewLocallyAndLeaveOtherSongsAndNavigationUsable() {
        val first = Track("job", "first.mp3", title = "First", rating = 1)
        val second = first.copy(name = "second.mp3", title = "Second", rating = 2)
        val files = listOf(first) + (1..49).map { first.copy(name = "filler$it.mp3") } + second
        val release = CountDownLatch(1)
        val started = AtomicInteger()
        val model = startModel(first, expectedTracks = 51) { request ->
            when {
                request.method == "PATCH" -> {
                    started.incrementAndGet()
                    check(release.await(10, TimeUnit.SECONDS))
                    metadataResponse(SongMetadata(rating = if (request.path!!.contains("first.mp3")) 5 else 4))
                }
                request.requestUrl!!.encodedPath == "/api/library/tracks" ->
                    MockResponse().setBody(ApiJson.encodeToString(
                        if (request.requestUrl!!.queryParameter("page") == "2")
                            TrackPage(files = files.drop(50), page = 2, total = 51, totalPages = 2)
                        else TrackPage(files = files.take(50), total = 51, totalPages = 2)))
                else -> null
            }
        }
        drainRequests()
        try {
            compose.runOnUiThread {
                model.saveRating(first, 5)
                assertEquals(5, model.library.rating(first))
                assertTrue(model.songBusy(first))
                assertFalse(model.songBusy(second))
                assertFalse(model.busy)
                assertFalse(model.library.loading)
                model.saveRating(first, 3)
                model.saveRating(second, 4)
                assertEquals(5, model.library.rating(first))
                assertEquals(4, model.library.rating(second))
                model.page(2)
            }
            waitFor { model.library.page == 2 && !model.library.loading && started.get() == 1 }
            assertEquals(4, model.library.rating(second))
            assertEquals(1, started.get())
        } finally { release.countDown() }
        waitFor { model.pendingSongs.isEmpty() }
        assertEquals(2, started.get())
        assertEquals(5, model.library.rating(first))
        assertEquals(4, model.library.rating(second))
        assertFalse(model.busy)
        val sent = drainRequests()
        assertEquals(2, sent.count { it.method == "PATCH" })
        assertEquals(0, sent.count { it.path!!.startsWith("/api/library/tracks?") })
    }

    @Test fun metadataPreviewRollsBackOnlyItsSongWhenSavingFails() {
        val track = Track("job", "song.mp3", title = "Original", artist = "Artist", rating = 2)
        val other = track.copy(name = "other.mp3")
        val release = CountDownLatch(1)
        val model = startModel(track) { request ->
            if (request.method == "PATCH") {
                check(release.await(10, TimeUnit.SECONDS))
                MockResponse().setResponseCode(403).setBody("""{"error":"Edit denied"}""")
            } else null
        }
        drainRequests()
        try {
            compose.runOnUiThread {
                model.saveMetadata(track, SongMetadata(title = "Edited", artist = "New artist", rating = 5), true)
                assertEquals("Edited", model.library.tracks.files.single().title)
                assertEquals(5, model.library.rating(track))
                assertTrue(model.library.transcriptionLocked(track))
                ReflectionHelpers.getField<androidx.compose.runtime.MutableState<LibraryState>>(
                    model, "library\$delegate").value = model.library.copy(ratings = model.library.ratings + (other.key to 4))
                assertFalse(model.busy)
                assertFalse(model.library.loading)
            }
        } finally { release.countDown() }
        waitFor { model.pendingSongs.isEmpty() }
        assertEquals(track, model.library.tracks.files.single())
        assertEquals(2, model.library.rating(track))
        assertEquals(4, model.library.rating(other))
        assertFalse(model.library.transcriptionLocked(track))
        assertEquals("Edit denied", model.notice)
        assertEquals(1, drainRequests().size)
    }

    @Test fun removingSongPreviewsMembershipLocallyAndRestoresItOnFailure() {
        val track = Track("job", "song.mp3", playlistId = "playlist")
        val release = CountDownLatch(1)
        val model = startModel(track) { request ->
            if (request.path == "/api/library/songs/remove") {
                check(release.await(10, TimeUnit.SECONDS))
                MockResponse().setResponseCode(403).setBody("""{"error":"Removal denied"}""")
            } else null
        }
        drainRequests()
        try {
            compose.runOnUiThread {
                model.remove(track)
                assertTrue(model.library.tracks.files.isEmpty())
                assertFalse(model.busy)
                assertFalse(model.library.loading)
            }
        } finally { release.countDown() }
        waitFor { model.pendingSongs.isEmpty() }
        assertEquals(listOf(track), model.library.tracks.files)
        assertEquals("Removal denied", model.notice)
        assertEquals(1, drainRequests().size)
    }

    @Test fun signOutCancelsPendingSongActionsWithoutPublishingLateResults() {
        val track = Track("job", "song.mp3", rating = 2)
        val release = CountDownLatch(1)
        val started = AtomicInteger()
        val responded = AtomicInteger()
        val model = startModel(track) { request ->
            if (request.method == "PATCH") {
                started.incrementAndGet()
                check(release.await(10, TimeUnit.SECONDS))
                responded.incrementAndGet()
                metadataResponse(SongMetadata(title = "Late", rating = 5))
            } else null
        }
        drainRequests()
        try {
            compose.runOnUiThread {
                model.saveRating(track, 5)
                model.saveRating(track.copy(name = "queued.mp3"), 4)
            }
            waitFor { started.get() == 1 }
            compose.runOnUiThread { sessions.clear() }
            waitFor { model.pendingSongs.isEmpty() && model.library.tracks.files.isEmpty() }
        } finally { release.countDown() }
        waitFor { responded.get() == 1 }
        idleFor(100)
        assertEquals(LibraryState(), model.library)
        assertNull(model.notice)
        assertEquals(1, drainRequests().size)
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

    @Test fun nowPlayingMetadataEditorKeepsItsDraftAndSaveBoundToTheOriginalSong() {
        val track = Track("job", "song.mp3", title = "Original song")
        val next = track.copy(name = "next.mp3", title = "Next song")
        val original = SongMetadata(title = "Original song", artist = "Artist", album = "Album",
            genre = "Pop", year = "2026", rating = 3, canEdit = true)
        val nextMetadata = original.copy(title = "Next song")
        val updated = original.copy(title = "Edited song")
        val path = "/api/jobs/job/files/song.mp3/metadata"
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                "/api/library" -> MockResponse().setBody(ApiJson.encodeToString(
                    Library(jobs = listOf(Job("job", initiatedBy = account.user)))))
                songPath(track, "lyrics") -> MockResponse().setBody(ApiJson.encodeToString(original))
                songPath(next, "lyrics") -> MockResponse().setBody(ApiJson.encodeToString(nextMetadata))
                path -> metadataResponse(updated)
                else -> null
            }
        }
        showTrack(model, track)
        compose.setContent {
            MusicTheme { NowPlayingScreen(model, model.playback.state.collectAsState().value) { _, _ -> } }
        }
        compose.onNodeWithContentDescription("More song actions").performClick()
        compose.onNodeWithText("Edit metadata").performClick()
        waitFor { compose.onAllNodesWithText("Title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Title").performTextReplacement("Discard this")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertFalse(drainRequests().any { it.method == "PATCH" })

        compose.onNodeWithContentDescription("More song actions").performClick()
        compose.onNodeWithText("Edit metadata").performClick()
        waitFor { compose.onAllNodesWithText("Title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Title").assertTextContains("Original song").performTextReplacement("Edited song")
        showTrack(model, next)
        waitFor { model.metadata == nextMetadata }
        compose.onNodeWithText("Title").assertTextContains("Edited song")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        waitFor { model.notice == "Song information saved." && !model.busy }
        compose.onNode(isDialog()).assertDoesNotExist()
        assertPatch(drainRequests().single { it.method == "PATCH" }, path,
            """{"title":"Edited song","artist":"Artist","album":"Album","genre":"Pop","year":"2026","rating":3}""")
        assertEquals(nextMetadata, model.metadata)
        assertEquals(next, model.playback.state.value.track)
    }

    @Test fun unlockingTranscriptionPatchesOnlyTheLockEvenWhenServiceIsInactive() {
        val track = Track("job", "song.mp3", transcriptionLocked = true)
        val listed = AtomicReference(track)
        val path = "/api/jobs/job/files/song.mp3/metadata"
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                path -> {
                    listed.set(track.copy(transcriptionLocked = false))
                    metadataResponse(SongMetadata(transcriptionLocked = false))
                }
                "/api/library/tracks" -> MockResponse().setBody(ApiJson.encodeToString(
                    TrackPage(files = listOf(listed.get()))))
                "/api/health" -> MockResponse().setBody("""{"transcription":{"status":"inactive"}}""")
                else -> null
            }
        }
        assertTrue(model.library.transcriptionLocked(track))
        assertFalse(model.transcriptionAvailable)
        drainRequests()
        perform(model) { model.lockTranscription(track, false) }
        val requests = drainRequests()
        assertPatch(requests.single { it.path == path }, path, """{"transcriptionLocked":false}""")
        assertFalse(requests.any { it.path!!.endsWith("/transcribe") })
        assertFalse(model.library.transcriptionLocked(track))
        assertEquals("Transcription unlocked for song.", model.notice)
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
            assertFalse(requests.any { it.method == "GET" && it.path!!.startsWith("/api/library/tracks?") })
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
        assertFalse(requests.any { it.method == "GET" && it.path!!.startsWith("/api/library/tracks?") })
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

    @Test fun replacementUpdatesLibraryAfterAConfirmedSuccessfulUpload() {
        val track = Track("job", "song.mp3", title = "Old song", rating = 5,
            playlistId = "playlist", playlistTitle = "Playlist", transcriptionLocked = true,
            transcription = com.ssytdlp.app.core.Transcription(status = "transcribed"),
            streamUrl = "/api/stream/song.mp3?v=1")
        val replacement = track.copy(title = "New song", rating = 0, transcription = null,
            streamUrl = "/api/stream/song.mp3?v=2")
        val saved = AtomicReference(track)
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                "/api/library" -> MockResponse().setBody(ApiJson.encodeToString(
                    Library(jobs = listOf(Job("job", initiatedBy = account.user)))))
                "/api/library/tracks" -> MockResponse().setBody(ApiJson.encodeToString(
                    TrackPage(files = listOf(saved.get()))))
                "/api/jobs/job/files/song.mp3/replace" -> {
                    saved.set(replacement)
                    MockResponse().setBody(JsonObject(mapOf(
                        "file" to ApiJson.encodeToJsonElement(replacement.copy(jobId = "", playlistId = null, playlistTitle = "")),
                        "metadata" to ApiJson.encodeToJsonElement(SongMetadata(title = "New song", transcriptionLocked = true))
                    )).toString())
                }
                else -> null
            }
        }
        drainRequests()
        var completed = false
        val uri = replacementDocument()
        perform(model) { model.replaceFile(track, uri) { completed = true } }
        val upload = drainRequests().single { it.method == "POST" }
        assertEquals("/api/jobs/job/files/song.mp3/replace", upload.path)
        assertTrue(upload.body.readUtf8().contains("name=\"file\"; filename=\"replacement.MP3\""))
        assertTrue(completed)
        assertEquals(replacement, model.library.tracks.files.single())
        assertEquals(0, model.library.rating(track))
        assertNull(model.library.transcription(track))
        assertTrue(model.library.transcriptionLocked(track))
        assertEquals("Replaced Old song.", model.notice)
    }

    @Test fun rejectedReplacementLeavesTheSongAndDialogUnchanged() {
        val track = Track("job", "song.mp3", title = "Original", rating = 5)
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                "/api/library" -> MockResponse().setBody(ApiJson.encodeToString(
                    Library(jobs = listOf(Job("job", initiatedBy = account.user)))))
                "/api/jobs/job/files/song.mp3/replace" ->
                    MockResponse().setResponseCode(409).setBody("""{"error":"Song is busy"}""")
                else -> null
            }
        }
        drainRequests()
        var completed = false
        val uri = replacementDocument()
        perform(model) { model.replaceFile(track, uri) { completed = true } }
        assertFalse(completed)
        assertEquals(track, model.library.tracks.files.single())
        assertEquals(5, model.library.rating(track))
        assertEquals("Song is busy", model.notice)
        assertEquals(1, drainRequests().size)
    }

    @Test fun libraryOffersReplacementForNonMp3Audio() {
        val track = Track("job", "song.flac")
        val model = startModel(track) { request ->
            if (request.requestUrl!!.encodedPath == "/api/library")
                MockResponse().setBody(ApiJson.encodeToString(
                    Library(jobs = listOf(Job("job", initiatedBy = account.user))))) else null
        }
        compose.setContent { MusicTheme(waveAppearance = false) { TrackMenu(model, track, 0) { _, _ -> } } }
        compose.onNodeWithContentDescription("Options for ${track.displayTitle}").performClick()
        compose.onNodeWithText("Edit metadata").assertDoesNotExist()
        compose.onNodeWithText("Replace file").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText("Choose replacement file").assertExists()
    }

    @Test fun changedStreamUrlReloadsLyricsAndArtworkForTheSameSongKey() {
        val track = Track("job", "song.mp3", streamUrl = "/api/stream/song.mp3?v=1")
        val old = SongMetadata(uslt = "Old lyrics", artwork = "old artwork", canEdit = true)
        val saved = AtomicReference(old)
        val model = startModel(track) { request ->
            if (request.requestUrl!!.encodedPath == songPath(track, "lyrics"))
                MockResponse().setBody(ApiJson.encodeToString(saved.get())) else null
        }
        showTrack(model, track)
        assertEquals(old, model.metadata)
        drainRequests()
        val updated = old.copy(uslt = "Replacement lyrics", artwork = "replacement artwork")
        saved.set(updated)
        showTrack(model, track.copy(streamUrl = "/api/stream/song.mp3?v=2"))
        waitFor { model.metadata == updated }
        assertEquals(listOf(songPath(track, "lyrics")), drainRequests().map { it.path })
        assertTrue(model.metadata!!.canEdit)
    }

    @Test fun recentSongRevisitReusesMetadataButArtworkRevisionDoesNot() {
        val first = Track("job", "first.mp3", streamUrl = "/api/stream/first?v=1", artworkUrl = "/api/art/first?v=1")
        val second = first.copy(name = "second.mp3")
        val firstMetadata = SongMetadata(title = "First", uslt = "Lyrics", canEdit = true)
        val requests = AtomicInteger()
        val model = startModel(first) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(first, "lyrics") -> {
                    requests.incrementAndGet()
                    MockResponse().setBody(ApiJson.encodeToString(firstMetadata))
                }
                songPath(second, "lyrics") -> MockResponse().setBody("""{"title":"Second"}""")
                else -> null
            }
        }
        showTrack(model, first)
        showTrack(model, second)
        showTrack(model, first)
        assertEquals(firstMetadata, model.metadata)
        assertEquals(1, requests.get())
        showTrack(model, first.copy(artworkUrl = "/api/art/first?v=2"))
        waitFor { requests.get() == 2 && model.metadata != null }
        assertEquals(firstMetadata, model.metadata)
    }

    @Test fun cachedLyricsPatchSurvivesRevisitWithoutTrustingPatchCanEdit() {
        val first = Track("job", "first.mp3")
        val second = Track("job", "second.mp3")
        val old = SongMetadata(title = "First", uslt = "Old", canEdit = false)
        val requests = AtomicInteger()
        val model = startModel(first) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(first, "lyrics") -> {
                    requests.incrementAndGet()
                    MockResponse().setBody(ApiJson.encodeToString(old))
                }
                songPath(second, "lyrics") -> MockResponse().setBody("""{"title":"Second","canEdit":true}""")
                "/api/jobs/job/files/first.mp3/metadata" ->
                    MockResponse().setBody(ApiJson.encodeToString(old.copy(uslt = "Edited", canEdit = true)))
                else -> null
            }
        }
        showTrack(model, first)
        showTrack(model, second)
        perform(model) { model.saveLyrics(first, json("uslt" to "Edited")) {} }
        assertEquals("Second", model.metadata!!.title)
        showTrack(model, first)
        assertEquals("Edited", model.metadata!!.uslt)
        assertFalse(model.metadata!!.canEdit)
        assertEquals(1, requests.get())
    }

    @Test fun patchCancelsOlderGetAndItsLateResponseCannotOverwriteSavedLyrics() {
        val track = Track("job", "song.mp3")
        val requests = AtomicInteger()
        val saved = SongMetadata(title = "Song", uslt = "Saved", canEdit = true)
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(track, "lyrics") -> if (requests.incrementAndGet() == 1)
                    MockResponse().setBody("""{"uslt":"Stale","canEdit":true}""").setBodyDelay(1, TimeUnit.SECONDS)
                else MockResponse().setBody(ApiJson.encodeToString(saved))
                "/api/jobs/job/files/song.mp3/metadata" -> metadataResponse(saved)
                else -> null
            }
        }
        setPlayback(model, PlaybackState(track = track, queue = listOf(track)))
        waitFor { requests.get() == 1 }
        perform(model) { model.saveLyrics(track, json("uslt" to "Saved")) {} }
        waitFor { model.metadata == saved }
        idleFor(1_200)
        assertEquals(saved, model.metadata)
        assertEquals(2, requests.get())
    }

    @Test fun rapidSkipsDoNotPublishOrCacheCancelledResponses() {
        val first = Track("job", "first.mp3")
        val second = Track("job", "second.mp3")
        val requests = AtomicInteger()
        val model = startModel(first) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(first, "lyrics") -> if (requests.incrementAndGet() == 1)
                    MockResponse().setBody("""{"title":"Stale first"}""").setBodyDelay(1, TimeUnit.SECONDS)
                else MockResponse().setBody("""{"title":"Fresh first"}""")
                songPath(second, "lyrics") -> MockResponse().setBody("""{"title":"Second"}""")
                else -> null
            }
        }
        setPlayback(model, PlaybackState(track = first, queue = listOf(first)))
        waitFor { requests.get() == 1 }
        showTrack(model, second)
        idleFor(1_200)
        assertEquals("Second", model.metadata!!.title)
        showTrack(model, first)
        assertEquals("Fresh first", model.metadata!!.title)
        assertEquals(2, requests.get())
    }

    @Test fun logoutAndNewSessionCannotReuseCachedLyricsForTheSameTrack() {
        val track = Track("job", "song.mp3")
        val requests = AtomicInteger()
        val model = startModel(track) { request ->
            if (request.requestUrl!!.encodedPath == songPath(track, "lyrics"))
                MockResponse().setBody("""{"title":"Session ${requests.incrementAndGet()}"}""") else null
        }
        showTrack(model, track)
        assertEquals("Session 1", model.metadata!!.title)
        compose.runOnUiThread { sessions.clear() }
        waitFor { model.metadata == null && model.playback.state.value.track == null }
        compose.runOnUiThread { sessions.save(account.copy(session = account.session.copy(token = "N".repeat(43)))) }
        waitFor { model.health != null && !model.library.loading }
        showTrack(model, track)
        assertEquals("Session 2", model.metadata!!.title)
        assertEquals(2, requests.get())
    }

    @Test fun switchingAccountsCannotPublishALateResponseOrReuseThePreviousOwnersCache() {
        val track = Track("job", "song.mp3")
        val requests = AtomicInteger()
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                "/api/auth/me" -> MockResponse().setBody(ApiJson.encodeToString(UserResponse(sessions.account.value!!.user)))
                songPath(track, "lyrics") -> {
                    val owner = sessions.account.value!!.user.id
                    val response = MockResponse().setBody("""{"title":"$owner"}""")
                    if (requests.incrementAndGet() == 1) response.setBodyDelay(1, TimeUnit.SECONDS) else response
                }
                else -> null
            }
        }
        setPlayback(model, PlaybackState(track = track, queue = listOf(track)))
        waitFor { requests.get() == 1 }
        compose.runOnUiThread { sessions.save(account.copy(user = User("other"))) }
        waitFor { model.metadata?.title == "other" }
        idleFor(1_200)
        assertEquals("other", model.metadata!!.title)
        assertEquals(2, requests.get())
    }

    @Test fun metadataGetterHidesPreviousOwnerBeforeAnyAccountCollectorCanRun() {
        val track = Track("job", "song.mp3")
        val model = startModel(track) { request ->
            if (request.requestUrl!!.encodedPath == songPath(track, "lyrics"))
                MockResponse().setBody("""{"uslt":"Private lyrics","artwork":"Private artwork"}""") else null
        }
        showTrack(model, track)
        compose.runOnUiThread {
            model.viewModelScope.cancel()
            sessions.save(account.copy(user = User("other")))
            assertNull(model.metadata)
            sessions.save(account.copy(origin = "https://other.example"))
            assertNull(model.metadata)
            sessions.save(account.copy(session = account.session.copy(token = "N".repeat(43))))
            assertNull(model.metadata)
            sessions.clear()
            assertNull(model.metadata)
        }
    }

    @Test fun profileRefreshDoesNotCancelMetadataDownloadsOrDiscardRecentCacheEntries() {
        val first = Track("job", "first.mp3")
        val second = Track("job", "second.mp3")
        val requests = AtomicInteger()
        val model = startModel(first) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(first, "lyrics") -> {
                    requests.incrementAndGet()
                    MockResponse().setBody("""{"title":"First","canEdit":true}""").setBodyDelay(1, TimeUnit.SECONDS)
                }
                songPath(second, "lyrics") -> MockResponse().setBody("""{"title":"Second"}""")
                else -> null
            }
        }
        setPlayback(model, PlaybackState(track = first, queue = listOf(first)))
        waitFor { requests.get() == 1 }
        compose.runOnUiThread {
            assertTrue(sessions.updateUser(account, account.user.copy(name = "Updated profile")))
        }
        waitFor { model.metadata?.title == "First" }
        val original = model.metadata
        compose.runOnUiThread {
            val current = sessions.account.value!!
            assertTrue(sessions.updateUser(current, current.user.copy(sharedUserIds = listOf("friend"))))
            assertSame(original, model.metadata)
        }
        showTrack(model, second)
        showTrack(model, first)
        assertSame(original, model.metadata)
        assertEquals(1, requests.get())
    }

    @Test fun foregroundPrefetchWaitsForMetadataAndBufferingThenOnlyFetchesOneNextSong() {
        val first = Track("job", "first.mp3")
        val second = Track("job", "second.mp3")
        val third = Track("job", "third.mp3")
        val requests = AtomicInteger()
        val model = startModel(first) { request ->
            if (request.requestUrl!!.encodedPath.contains("/lyrics/")) {
                requests.incrementAndGet()
                MockResponse().setBody("""{"title":"${request.requestUrl!!.pathSegments.last()}"}""")
            } else null
        }
        setPlayback(model, PlaybackState(track = first, queue = listOf(first, second, third), buffering = true))
        waitFor { model.metadata != null }
        idleFor(500)
        assertEquals(1, requests.get())
        compose.runOnUiThread { application.uiActivity.activityPaused(compose.activity) }
        setPlayback(model, model.playback.state.value.copy(buffering = false))
        idleFor(500)
        assertEquals(1, requests.get())
        compose.runOnUiThread { application.uiActivity.activityResumed(compose.activity) }
        waitFor { requests.get() == 2 }
        idleFor(500)
        assertEquals(2, requests.get())
        assertEquals("first.mp3", model.metadata!!.title)
        setPlayback(model, PlaybackState(track = second, queue = listOf(second)))
        waitFor { model.metadata?.title == "second.mp3" }
        assertEquals(2, requests.get())
    }

    @Test fun prefetchDoesNotGuessShuffleAndCancelsOnQueueOrBackgroundChanges() {
        val first = Track("job", "first.mp3")
        val second = Track("job", "second.mp3")
        val third = Track("job", "third.mp3")
        val secondRequests = AtomicInteger()
        val thirdRequests = AtomicInteger()
        val model = startModel(first) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(first, "lyrics") -> MockResponse().setBody("""{"title":"First"}""")
                songPath(second, "lyrics") -> {
                    secondRequests.incrementAndGet()
                    MockResponse().setBody("""{"title":"Second"}""").setBodyDelay(1, TimeUnit.SECONDS)
                }
                songPath(third, "lyrics") -> {
                    thirdRequests.incrementAndGet()
                    MockResponse().setBody("""{"title":"Third"}""").setBodyDelay(1, TimeUnit.SECONDS)
                }
                else -> null
            }
        }
        setPlayback(model, PlaybackState(track = first, queue = listOf(first, second), shuffle = true))
        waitFor { model.metadata != null }
        idleFor(500)
        assertEquals(0, secondRequests.get())
        setPlayback(model, model.playback.state.value.copy(shuffle = false))
        waitFor { secondRequests.get() == 1 }
        setPlayback(model, model.playback.state.value.copy(queue = listOf(first, third)))
        waitFor { thirdRequests.get() == 1 }
        compose.runOnUiThread { application.uiActivity.activityPaused(compose.activity) }
        idleFor(1_200)
        assertEquals("First", model.metadata!!.title)
        assertNull(model.metadataError)
        showTrack(model, second)
        assertEquals(2, secondRequests.get())
        showTrack(model, third)
        assertEquals(2, thirdRequests.get())
    }

    @Test fun failedPrefetchIsNotReplayedWhenTheSongBecomesCurrent() {
        val first = Track("job", "first.mp3")
        val second = Track("job", "second.mp3")
        val requests = AtomicInteger()
        val model = startModel(first) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(first, "lyrics") -> MockResponse().setBody("""{"title":"First"}""")
                songPath(second, "lyrics") -> if (requests.incrementAndGet() == 1)
                    MockResponse().setResponseCode(503).setBody("""{"error":"Temporarily unavailable"}""")
                else MockResponse().setBody("""{"title":"Second"}""")
                else -> null
            }
        }
        setPlayback(model, PlaybackState(track = first, queue = listOf(first, second)))
        waitFor { requests.get() == 1 }
        idleFor(300)
        assertNull(model.metadataError)
        showTrack(model, second)
        assertEquals("Second", model.metadata!!.title)
        assertEquals(2, requests.get())
    }

    @Test fun patchingAPrefetchedSongCancelsItsOldGetBeforeItCanEnterTheCache() {
        val first = Track("job", "first.mp3")
        val second = Track("job", "second.mp3")
        val requests = AtomicInteger()
        val model = startModel(first) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(first, "lyrics") -> MockResponse().setBody("""{"title":"First"}""")
                songPath(second, "lyrics") -> if (requests.incrementAndGet() == 1)
                    MockResponse().setBody("""{"uslt":"Old"}""").setBodyDelay(1, TimeUnit.SECONDS)
                else MockResponse().setBody("""{"uslt":"Saved","canEdit":true}""")
                "/api/jobs/job/files/second.mp3/metadata" -> MockResponse().setBody("""{"uslt":"Saved"}""")
                else -> null
            }
        }
        setPlayback(model, PlaybackState(track = first, queue = listOf(first, second)))
        waitFor { requests.get() == 1 }
        perform(model) { model.saveLyrics(second, json("uslt" to "Saved")) {} }
        waitFor { requests.get() == 2 }
        idleFor(1_200)
        assertEquals("First", model.metadata!!.title)
        showTrack(model, second)
        assertEquals("Saved", model.metadata!!.uslt)
        assertTrue(model.metadata!!.canEdit)
        assertEquals(2, requests.get())
    }

    @Test fun transcriptionCompletionInvalidatesMetadataEvenWithoutAStreamRevision() {
        val track = Track("job", "song.mp3")
        val saved = AtomicReference(SongMetadata(uslt = "Old lyrics", canEdit = true))
        val requests = AtomicInteger()
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                songPath(track, "lyrics") -> {
                    requests.incrementAndGet()
                    MockResponse().setBody(ApiJson.encodeToString(saved.get()))
                }
                "/api/jobs/job/files/song.mp3/transcribe" -> {
                    saved.set(saved.get().copy(uslt = "Transcribed lyrics"))
                    MockResponse().setBody("""{"id":"job","transcriptions":{"song.mp3":{"status":"transcribed"}}}""")
                }
                "/api/jobs/job" -> MockResponse().setBody("""{"id":"job"}""")
                else -> null
            }
        }
        showTrack(model, track)
        perform(model) { model.transcribe(track, TranscriptionOptions()) }
        waitFor { model.metadata?.uslt == "Transcribed lyrics" }
        assertTrue(requests.get() >= 2)
        assertTrue(model.metadata!!.canEdit)
    }

    @Test fun replacingCurrentFileInvalidatesLyricsAndGetsFreshEditAuthorityEvenWithoutNewRevision() {
        val track = Track("job", "song.mp3", streamUrl = "/api/stream/song.mp3?v=1")
        val saved = AtomicReference(SongMetadata(uslt = "Old lyrics", canEdit = true))
        val requests = AtomicInteger()
        val model = startModel(track) { request ->
            when (request.requestUrl!!.encodedPath) {
                "/api/library" -> MockResponse().setBody(ApiJson.encodeToString(
                    Library(jobs = listOf(Job("job", initiatedBy = account.user)))))
                songPath(track, "lyrics") -> {
                    requests.incrementAndGet()
                    MockResponse().setBody(ApiJson.encodeToString(saved.get()))
                }
                "/api/jobs/job/files/song.mp3/replace" -> {
                    saved.set(SongMetadata(uslt = "Replacement lyrics", canEdit = false))
                    MockResponse().setBody(JsonObject(mapOf(
                        "file" to ApiJson.encodeToJsonElement(track),
                        "metadata" to ApiJson.encodeToJsonElement(saved.get().copy(canEdit = true))
                    )).toString())
                }
                else -> null
            }
        }
        showTrack(model, track)
        val uri = replacementDocument()
        perform(model) { model.replaceFile(track, uri) {} }
        waitFor { model.metadata?.uslt == "Replacement lyrics" }
        assertFalse(model.metadata!!.canEdit)
        assertEquals(2, requests.get())
    }

    private fun replacementDocument(): Uri {
        val uri = Uri.parse("content://replacement-test/song")
        val provider = ReplacementDocumentProvider(replacementDocuments.newFile().apply { writeText("replacement audio") })
        provider.attachInfo(context, ProviderInfo().apply { authority = uri.authority })
        ShadowContentResolver.registerProviderInternal(uri.authority, provider)
        return uri
    }

    private fun startModel(
        track: Track = Track("job", "song.mp3"),
        user: User = account.user,
        expectedTracks: Int = 1,
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
            model.library.tracks.files.size == expectedTracks && !model.library.loading &&
                (user.isShared || model.health != null)
        }
        assertNull(model.notice)
        return model
    }

    private fun showTrack(model: MusicViewModel, track: Track) {
        setPlayback(model, PlaybackState(track = track, queue = listOf(track)))
        waitFor { model.metadata != null }
    }

    private fun setPlayback(model: MusicViewModel, state: PlaybackState) = compose.runOnUiThread {
        ReflectionHelpers.getField<MutableStateFlow<PlaybackState>>(model.playback, "mutableState").value = state
    }

    private fun idleFor(milliseconds: Long) {
        val until = System.nanoTime() + milliseconds * 1_000_000
        waitFor { System.nanoTime() >= until }
    }

    private fun perform(model: MusicViewModel, action: () -> Unit) {
        compose.runOnUiThread { model.message(null); action() }
        waitFor { !model.busy && model.pendingSongs.isEmpty() && !model.library.loading &&
            ReflectionHelpers.getField<kotlinx.coroutines.Job?>(model, "trackRequest")?.isActive != true }
    }

    @Test fun waitForRunsDelayedMainLooperWorkWithoutComposeContent() {
        var completed = false
        Handler(Looper.getMainLooper()).postDelayed({ completed = true }, 350)
        waitFor { completed }
        assertTrue(completed)
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 30_000) {
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
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
