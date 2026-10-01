package com.ssytdlp.app

import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.AuthProtocol
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.Job
import com.ssytdlp.app.core.Library
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.User
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ServerApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ServerApi
    private var account: Account? = null
    private val cleared = mutableListOf<String>()
    private val token = "T".repeat(43)
    private val origin = "https://primary.example"

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        account = Account(server.url("/").newBuilder().host("localhost").build().toString().removeSuffix("/"),
            User("user", "Listener"), Session(token, Instant.now().plusSeconds(3600).toString()))
        api = ServerApi({ account }, { expected -> cleared.add(expected); if (account?.session?.token == expected) account = null },
            OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
    }

    @After fun teardown() { server.shutdown() }

    @Test fun `shared users do not log health or jobs statuses`() {
        val shared = account!!.copy(user = account!!.user.copy(role = "Shared"))
        assertFalse(shouldLogApiStatus(shared, "/api/health"))
        assertFalse(shouldLogApiStatus(shared, "/api/jobs"))
        assertTrue(shouldLogApiStatus(shared, "/api/library"))
        assertTrue(shouldLogApiStatus(account, "/api/health"))
    }

    @Test fun `app login targets the configured server hostname`() {
        val pending = AppServer.beginLogin(origin)
        val login = URI(AuthProtocol.loginUrl(pending))
        assertEquals(URI(origin).host, login.host)
        assertEquals("https", login.scheme)
        assertEquals("/app-login", login.path)
        assertEquals(origin, pending.origin)
    }

    @Test fun `callbacks retain the configured token exchange origin`() {
        val pending = AppServer.beginLogin(origin)
        val callback = "${AuthProtocol.REDIRECT_URI}?code=${"C".repeat(43)}&state=${pending.state}"
        assertEquals(origin, AppServer.acceptCallback(callback, pending, origin).origin)
    }

    @Test fun `stored login requests for a different server cannot be exchanged`() {
        val pending = AuthProtocol.begin("https://secondary.example:4123")
        val callback = "${AuthProtocol.REDIRECT_URI}?code=${"C".repeat(43)}&state=${pending.state}"
        assertThrows(IllegalArgumentException::class.java) { AppServer.acceptCallback(callback, pending, origin) }
    }

    @Test fun `authenticated requests send bearer only in the header`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        api.request("/api/library/tracks?page=1&pageSize=50")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
        assertFalse(request.path!!.contains(token))
        assertEquals("/api/library/tracks?page=1&pageSize=50", request.path)
    }

    @Test fun `no vocals only posts its standalone option and reads saved result mode`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"source","transcriptions":{
            "folder/song.mp3":{"status":"transcribed","options":{"NoVocalsOnly":true}}}}"""))
        val options = TranscriptionOptions(language = "vi", multilingual = true, noVocals = true,
            vietLyricsFallback = true, addLyrics = true, lyrics = "Ignored lyrics", noVocalsOnly = true)
        val result = ApiJson.decodeFromJsonElement<Job>(api.request(
            "/api/jobs/source/files/folder%2Fsong.mp3/transcribe", "POST", options.toRequestBody()))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals(listOf("api", "jobs", "source", "files", "folder/song.mp3", "transcribe"),
            request.requestUrl!!.pathSegments)
        assertEquals(listOf("Bearer", token).joinToString(" "), request.getHeader("Authorization"))
        assertEquals(ApiJson.parseToJsonElement("""{"NoVocalsOnly":true}"""),
            ApiJson.parseToJsonElement(request.body.readUtf8()))
        val record = result.transcriptions.getValue("folder/song.mp3")
        assertEquals(true, record.options!!.noVocalsOnly)
        assertEquals("No-vocals version generated", record.label)
        assertFalse(record.lyricsIncluded)
    }

    @Test fun `app exchange uses unauthenticated transport even with an existing session`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        val origin = account!!.origin
        api.exchange(origin, buildJsonObject {
            put("code", "C".repeat(43)); put("codeVerifier", "V".repeat(43)); put("redirectUri", AuthProtocol.REDIRECT_URI)
        })
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/auth/app/token", request.path)
        assertEquals("POST", request.method)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
        assertTrue(request.body.readUtf8().contains("codeVerifier"))
    }

    @Test fun `redirects are not followed and cannot receive the bearer token`() = runBlocking {
        MockWebServer().use { other ->
            other.start()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/api/stolen")))
            val failure = runCatching { api.request("/api/library") }.exceptionOrNull()
            assertTrue(failure is ApiException)
            assertEquals(302, (failure as ApiException).status)
            assertEquals(0, other.requestCount)
        }
    }

    @Test fun `401 clears precisely the session used by the request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Sign in again"}"""))
        val failure = runCatching { api.request("/api/library") }.exceptionOrNull() as ApiException
        assertEquals(401, failure.status)
        assertEquals("Sign in again", failure.message)
        assertEquals(listOf(token), cleared)
        assertNull(account)
    }

    @Test fun `expired sessions are cleared without making a request`() = runBlocking {
        account = account!!.copy(session = account!!.session.copy(expiresAt = Instant.now().minusSeconds(60).toString()))
        val failure = runCatching { api.request("/api/library") }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals(0, server.requestCount)
        assertEquals(listOf(token), cleared)
    }

    @Test fun `stream transport blocks foreign origins before connecting`() {
        val failure = runCatching {
            api.authenticatedClient.newCall(Request.Builder().url("https://other.example/api/audio").build()).execute()
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(0, server.requestCount)
    }

    @Test fun `logout handles the server no-content response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(JsonNull, api.request("/api/auth/logout", "POST"))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
    }

    @Test fun `downloads use the same authenticated transport and preserve binary data`() = runBlocking {
        val bytes = byteArrayOf(0, 1, 2, 3, -1)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val output = ByteArrayOutputStream()
        api.download("/api/jobs/123/download/song.mp3") { it.copyTo(output) }
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals("Bearer $token", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test fun `library conflicts preserve their response payload for the UI`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"Library changed","code":"CONFLICT"}"""))
        val failure = runCatching { api.request("/api/library/entries", "POST", buildJsonObject { put("version", 1) }) }.exceptionOrNull() as ApiException
        assertEquals(409, failure.status)
        assertEquals(JsonPrimitive("CONFLICT"), failure.payload?.get("code"))
        assertNotNull(account)
    }

    @Test fun `track status refresh reads inline records and preserves the selected page query`() = runBlocking {
        var state = LibraryState(library = Library(version = 7, jobs = listOf(Job(id = "source"))),
            selectedId = "folder/playlist", search = "a & b", page = 2)
        server.enqueue(MockResponse().setBody("""{"files":[{"jobId":"source","name":"song.mp3",
            "transcription":{"status":"sent"}}],"page":2,"version":9}"""))
        state = state.withTrackPage(api.trackPage(state))
        val queued = state.tracks.files.single()
        assertEquals("sent", state.transcription(queued)?.status)
        server.enqueue(MockResponse().setBody("""{"files":[{"jobId":"source","name":"song.mp3",
            "transcription":{"status":"transcribed","lyricsIncluded":true}}],"page":2,"version":9}"""))
        state = state.withTrackPage(api.trackPage(state))
        assertEquals("transcribed", state.transcription(queued)?.status)
        assertEquals(true, state.transcription(queued)?.lyricsIncluded)
        repeat(2) {
            assertEquals("/api/library/tracks?page=2&pageSize=50&search=a%20%26%20b&entryId=folder%2Fplaylist",
                server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        }
        assertTrue(state.library.jobs.single().transcriptions.isEmpty())
        assertEquals(7L, state.library.version)
    }
}