package com.ssytdlp.app

import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.User
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BackgroundApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ServerApi
    private lateinit var account: Account
    private val gate = UiActivityGate()
    private val activity = Any()
    private val failures = Channel<Unit>(Channel.UNLIMITED)
    private val headers = Channel<Unit>(Channel.UNLIMITED)

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        account = Account(server.url("/").toString().removeSuffix("/"), User("listener", "Listener"),
            Session("T".repeat(43), "2100-01-01T00:00:00Z"))
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) { failures.trySend(Unit) }
                override fun responseHeadersEnd(call: Call, response: Response) { headers.trySend(Unit) }
            }).build()
        api = ServerApi({ account }, {}, client, gate)
    }

    @After fun teardown() { server.shutdown() }

    @Test fun `only playback reads are exempt not jobs or mutations`() {
        listOf("/api/jobs/job/lyrics/song.mp3", "/api/jobs/job/stream/song.mp3",
            "/api/jobs/job/download/song.mp3", "/api/stream/song.mp3").forEach {
            assertTrue(it, isBackgroundPlaybackRequest("GET", it))
            assertFalse(it, isBackgroundPlaybackRequest("POST", it))
        }
        listOf("/api/jobs", "/api/jobs/job", "/api/health", "/api/library/tracks",
            "/api/preferences", "/api/auth/me", "/api/jobs/job/download-all",
            "/api/jobs/job/transcribe/song.mp3", "/api/jobs/job/files/song.mp3/metadata").forEach {
            assertFalse(it, isBackgroundPlaybackRequest("GET", it))
        }
    }

    @Test fun `startup background polls wait while song info and media keep working`() = runBlocking {
        val poll = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { api.request("/api/health") }
        assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody("{}"))
        withTimeout(5_000) { api.request("/api/jobs/job/lyrics/song.mp3") }
        assertEquals("/api/jobs/job/lyrics/song.mp3", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        server.enqueue(MockResponse().setBody("audio"))
        api.authenticatedClient.newCall(Request.Builder().url(api.url("/api/stream/song.mp3")).build()).execute().use {
            assertEquals("audio", it.body!!.string())
        }
        assertEquals("/api/stream/song.mp3", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertFalse(poll.isCompleted)
        server.enqueue(MockResponse().setBody("{}"))
        gate.activityResumed(activity)
        withTimeout(5_000) { poll.await() }
        assertEquals("/api/health", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
    }

    @Test fun `inflight safe poll is cancelled then retried only on resume`() = runBlocking {
        gate.activityResumed(activity)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val poll = async(Dispatchers.IO) { api.request("/api/jobs") }
        assertEquals("/api/jobs", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        gate.activityPaused(activity)
        withTimeout(5_000) { failures.receive() }
        assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        assertFalse(poll.isCompleted)
        server.enqueue(MockResponse().setBody("{}"))
        gate.activityResumed(activity)
        withTimeout(5_000) { poll.await() }
        assertEquals("/api/jobs", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertEquals(2, server.requestCount)
    }

    @Test fun `response body reads also stop when paused`() = runBlocking {
        gate.activityResumed(activity)
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(2, TimeUnit.SECONDS))
        val poll = async(Dispatchers.IO) { api.request("/api/preferences") }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        withTimeout(5_000) { headers.receive() }
        gate.activityPaused(activity)
        withTimeout(5_000) { failures.receive() }
        assertFalse(poll.isCompleted)
        poll.cancel()
        withTimeout(5_000) { poll.join() }
    }

    @Test fun `mutation waits for foreground and is never replayed after it starts`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(250, TimeUnit.MILLISECONDS))
        val mutation = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { api.request("/api/jobs", "POST") }
        assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        gate.activityResumed(activity)
        assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        gate.activityPaused(activity)
        assertEquals(JsonNull, withTimeout(5_000) { mutation.await() })
        gate.activityResumed(activity)
        assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test fun `deferred work cannot run under a replacement account`() = runBlocking {
        val request = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { runCatching { api.request("/api/jobs", "POST") } }
        assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        account = account.copy(user = User("replacement"), session = account.session.copy(token = "N".repeat(43)))
        gate.activityResumed(activity)
        assertTrue(withTimeout(5_000) { request.await() }.exceptionOrNull() is IOException)
        assertEquals(0, server.requestCount)
    }

    @Test fun `login callback exchange waits for resume but sends the code only once`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        val exchange = async(Dispatchers.IO) { api.exchange(account.origin, JsonNull) }
        assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        gate.activityResumed(activity)
        withTimeout(5_000) { exchange.await() }
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/auth/app/token", request.path)
        assertNull(request.getHeader("Authorization"))
        gate.activityPaused(activity)
        gate.activityResumed(activity)
        assertEquals(1, server.requestCount)
    }
}
