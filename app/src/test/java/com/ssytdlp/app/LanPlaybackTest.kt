package com.ssytdlp.app

import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.User
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LanPlaybackTest {
    private lateinit var primary: MockWebServer
    private lateinit var lan: MockWebServer
    private lateinit var api: ServerApi
    private var account: Account? = null
    private var config: LanPlaybackConfig? = null
    private var network: Long? = 1L
    private var time = 0L
    private val clearedPrimary = mutableListOf<String>()
    private val clearedLan = mutableListOf<String>()
    private val path = "/api/jobs/job/stream/folder%2Fsong.mp3?userId=owner&v=2"
    private val primaryToken = "P".repeat(43)
    private val lanToken = "L".repeat(43)

    @Before fun setup() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val loopback = InetAddress.getByName("127.0.0.1")
        fun server() = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            start(loopback, 0)
        }
        primary = server()
        lan = server()
        account = Account(origin(primary), User("primary-user"), Session(primaryToken, "2099-01-01T00:00:00Z"))
        config = LanPlaybackConfig(account!!.origin, account!!.user.id, origin(lan),
            Account(origin(lan), User("lan-user"), Session(lanToken, "2099-01-01T00:00:00Z")))
        val client = OkHttpClient.Builder()
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == "localhost") listOf(loopback) else okhttp3.Dns.SYSTEM.lookup(hostname)
            })
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        api = ServerApi({ account }, { clearedPrimary.add(it); account = null }, client,
            lanPlaybackRouter = LanPlaybackRouter({ account }, { config }, { network }, {
                clearedLan.add(it)
                if (config?.account?.session?.token == it) config = config?.copy(account = null)
            }, { time }))
    }

    @After fun teardown() {
        primary.shutdown()
        lan.shutdown()
    }

    private fun origin(server: MockWebServer) = server.url("/").newBuilder().host("localhost").build().toString().trimEnd('/')

    private fun call(path: String = this.path, range: String? = null): okhttp3.Call =
        api.playbackCalls(account!!).newCall(Request.Builder().url(api.url(path))
            .apply { range?.let { header("Range", it) } }.build())

    private fun primaryResponse() { primary.enqueue(MockResponse().setBody("primary audio")) }

    @Test fun `wifi playback uses separate LAN token while retaining canonical URI ranges and query`() {
        lan.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-7/8").setBody("LAN"))
        call(range = "bytes=5-").execute().use {
            assertEquals(api.url(path), it.request.url.toString())
            assertEquals("LAN", it.body!!.string())
        }
        val request = lan.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(path, request.path)
        assertEquals("bytes=5-", request.getHeader("Range"))
        assertEquals(listOf("Bearer", lanToken).joinToString(" "), request.getHeader("Authorization"))
        assertFalse(request.toString().contains(primaryToken))
        assertEquals(0, primary.requestCount)
    }

    @Test fun `cellular missing sign-in and mismatched primary owner never contact LAN`() {
        val original = config!!
        for (case in 0..2) {
            network = if (case == 0) null else 1L
            config = when (case) {
                1 -> original.copy(account = null)
                2 -> original.copy(primaryUserId = "someone-else")
                else -> original
            }
            primaryResponse()
            call().execute().use { assertEquals("primary audio", it.body!!.string()) }
            assertEquals(listOf("Bearer", primaryToken).joinToString(" "),
                primary.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        }
        assertEquals(0, lan.requestCount)
    }

    @Test fun `LAN refusal falls back and backs off for 30 seconds but a new network is reconsidered`() {
        lan.enqueue(MockResponse().setResponseCode(503))
        repeat(2) {
            primaryResponse()
            call().execute().use { assertEquals("primary audio", it.body!!.string()) }
        }
        assertEquals(1, lan.requestCount)
        network = 2L
        lan.enqueue(MockResponse().setResponseCode(503))
        primaryResponse()
        call().execute().use { assertEquals("primary audio", it.body!!.string()) }
        assertEquals(2, lan.requestCount)
        time = TimeUnit.SECONDS.toNanos(30)
        lan.enqueue(MockResponse().setBody("retried LAN"))
        call().execute().use { assertEquals("retried LAN", it.body!!.string()) }
        assertTrue(clearedPrimary.isEmpty())
    }

    @Test fun `LAN authentication rejection clears only LAN credentials then uses primary`() {
        lan.enqueue(MockResponse().setResponseCode(401))
        primaryResponse()
        call().execute().use { assertEquals("primary audio", it.body!!.string()) }
        assertEquals(listOf(lanToken), clearedLan)
        assertTrue(clearedPrimary.isEmpty())
        assertNotNull(account)
        assertNull(config!!.account)
    }

    @Test fun `expired LAN session is discarded without contacting LAN or clearing primary`() {
        config = config!!.copy(account = config!!.account!!.let {
            it.copy(session = it.session.copy(expiresAt = "2000-01-01T00:00:00Z"))
        })
        primaryResponse()
        call().execute().use { assertEquals("primary audio", it.body!!.string()) }
        assertEquals(0, lan.requestCount)
        assertEquals(listOf(lanToken), clearedLan)
        assertNotNull(account)
    }

    @Test fun `redirects cannot receive either token and rejected LAN paths fall back`() {
        MockWebServer().use { other ->
            other.start()
            lan.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/api/stream/stolen")))
            primaryResponse()
            call().execute().use { assertEquals("primary audio", it.body!!.string()) }
            assertEquals(0, other.requestCount)
        }
        assertTrue(clearedLan.isEmpty())
    }

    @Test fun `untrusted LAN certificates cannot receive a token and fall back to primary`() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { untrusted ->
            untrusted.useHttps(tls.sslSocketFactory(), false)
            untrusted.start()
            val origin = origin(untrusted)
            config = config!!.copy(origin = origin, account = config!!.account!!.copy(origin = origin))
            primaryResponse()
            call().execute().use { assertEquals("primary audio", it.body!!.string()) }
            assertEquals(0, untrusted.requestCount)
        }
    }

    @Test fun `unreachable LAN uses primary within short connection timeout`() {
        lan.shutdown()
        primaryResponse()
        val started = System.nanoTime()
        call().execute().use { assertEquals("primary audio", it.body!!.string()) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 4000)
    }

    @Test fun `stalled LAN headers use primary within short read timeout`() {
        lan.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        primaryResponse()
        val started = System.nanoTime()
        call().execute().use { assertEquals("primary audio", it.body!!.string()) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000)
    }

    @Test fun `truncated LAN body routes resumed range request to primary`() {
        lan.enqueue(MockResponse().setBody("a".repeat(32768))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertThrows(IOException::class.java) { call().execute().use { it.body!!.bytes() } }
        primary.enqueue(MockResponse().setResponseCode(206).setBody("remaining"))
        call(range = "bytes=16384-").execute().use { assertEquals("remaining", it.body!!.string()) }
        assertEquals("bytes=16384-", primary.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Range"))
        assertEquals(1, lan.requestCount)
    }

    @Test fun `wifi loss during body routes next load to primary`() {
        lan.enqueue(MockResponse().setBody("LAN"))
        call().execute().use { response ->
            network = null
            assertThrows(IOException::class.java) { response.body!!.string() }
        }
        primaryResponse()
        call().execute().use { assertEquals("primary audio", it.body!!.string()) }
        assertEquals(1, lan.requestCount)
    }

    @Test fun `cancellation never starts primary fallback`() {
        lan.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = call()
        val result = CompletableFuture.supplyAsync { runCatching { call.execute().close() }.exceptionOrNull() }
        assertNotNull(lan.takeRequest(3, TimeUnit.SECONDS))
        call.cancel()
        assertTrue(result.get(3, TimeUnit.SECONDS) is IOException)
        assertEquals(0, primary.requestCount)
    }

    @Test fun `account changes cannot replay LAN failures under another primary session`() {
        lan.enqueue(MockResponse().setHeadersDelay(250, TimeUnit.MILLISECONDS).setResponseCode(503))
        val call = call()
        val result = CompletableFuture.supplyAsync { runCatching { call.execute().close() }.exceptionOrNull() }
        assertNotNull(lan.takeRequest(3, TimeUnit.SECONDS))
        account = account!!.copy(user = User("other-user"))
        assertTrue(result.get(3, TimeUnit.SECONDS) is IOException)
        assertEquals(0, primary.requestCount)
    }

    @Test fun `nonmedia reads mutations and untrusted origins cannot use LAN`() {
        primaryResponse()
        call("/api/library").execute().close()
        primaryResponse()
        api.playbackCalls(account!!).newCall(Request.Builder().url(api.url("/api/stream/song"))
            .post("{}".toRequestBody()).build()).execute().close()
        assertThrows(IOException::class.java) {
            api.playbackCalls(account!!).newCall(Request.Builder().url("https://other.example/api/stream/song").build()).execute()
        }
        assertEquals(2, primary.requestCount)
        assertEquals(0, lan.requestCount)
    }
}
