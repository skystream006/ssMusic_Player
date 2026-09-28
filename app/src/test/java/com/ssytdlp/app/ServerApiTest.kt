package com.ssytdlp.app

import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.AuthProtocol
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

    @Test fun `app login targets the configured primary RP hostname`() {
        val pending = AppServer.beginLogin()
        val login = URI(AuthProtocol.loginUrl(pending))
        assertEquals(BuildConfig.PASSKEY_RP_ID, login.host)
        assertEquals("https", login.scheme)
        assertEquals("/app-login", login.path)
        assertEquals(BuildConfig.PASSKEY_ORIGIN, pending.origin)
    }

    @Test fun `primary callbacks retain the primary token exchange origin`() {
        val pending = AppServer.beginLogin()
        val callback = "${AuthProtocol.REDIRECT_URI}?code=${"C".repeat(43)}&state=${pending.state}"
        assertEquals(AppServer.origin, AppServer.acceptCallback(callback, pending).origin)
    }

    @Test fun `stored secondary login requests cannot be exchanged by the primary app`() {
        val pending = AuthProtocol.begin("https://secondary.example:4123")
        val callback = "${AuthProtocol.REDIRECT_URI}?code=${"C".repeat(43)}&state=${pending.state}"
        assertThrows(IllegalArgumentException::class.java) { AppServer.acceptCallback(callback, pending) }
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
}