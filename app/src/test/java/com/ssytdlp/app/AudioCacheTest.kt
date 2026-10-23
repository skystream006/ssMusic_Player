package com.ssytdlp.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.User
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioCacheTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var account: Account
    private lateinit var cache: AudioCache
    private lateinit var client: OkHttpClient
    private val leases = mutableListOf<AudioCache.Handle>()
    private val bytes = ByteArray(4096) { (it % 251).toByte() }
    private val root get() = File(context.noBackupFilesDir, "audio")
    private fun uri(path: String = "/api/stream/song") = Uri.parse(server.url(path).toString())

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        root.deleteRecursively()
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = response(request)
        }
        server.start()
        account = Account(server.url("/").toString().trimEnd('/'), User("listener"),
            Session("test-session", "2100-01-01T00:00:00Z"))
        cache = AudioCache(context).apply { setAccount(account) }
    }

    @After fun teardown() {
        leases.forEach { it.close() }
        cache.setAccount(null)
        server.shutdown()
    }

    private fun response(request: RecordedRequest): MockResponse {
        val range = request.getHeader("Range")?.removePrefix("bytes=")?.split('-')
        val start = range?.get(0)?.toInt() ?: 0
        val end = range?.get(1)?.toIntOrNull() ?: bytes.lastIndex
        return MockResponse().setResponseCode(if (range == null) 200 else 206)
            .setBody(Buffer().write(bytes, start, end - start + 1))
            .apply { if (range != null) setHeader("Content-Range", "bytes $start-$end/${bytes.size}") }
    }

    private fun lease(owner: Account = account, calls: okhttp3.Call.Factory = client) =
        cache.acquire(owner, calls).also { leases.add(it) }

    private fun fill(handle: AudioCache.Handle, uri: Uri = uri()) = runBlocking {
        withTimeout(5_000) { requireNotNull(handle.cacheCurrent(uri)).join() }
    }

    private fun read(handle: AudioCache.Handle, uri: Uri = uri(), position: Long = 0,
        length: Long = C.LENGTH_UNSET.toLong()): ByteArray {
        val source = handle.factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build())
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            source.close()
        }
    }

    @Test fun `current track finishes in background and survives a cache recreation without HTTP`() = runBlocking {
        assertEquals(0L, cache.usedBytes())
        val handle = lease()
        withTimeout(5_000) { requireNotNull(handle.cacheCurrent(uri())).join() }
        assertEquals(bytes.size.toLong(), cache.usedBytes())
        assertEquals(1, server.requestCount)
        handle.close()
        assertEquals(bytes.size.toLong(), cache.usedBytes())
        cache = AudioCache(context).apply { setAccount(account) }
        assertEquals(bytes.size.toLong(), cache.usedBytes())

        assertArrayEquals(bytes, read(lease()))
        assertEquals(1, server.requestCount)
        assertArrayEquals(bytes.copyOfRange(800, 1200), read(lease(), position = 800, length = 400))
        assertEquals(1, server.requestCount)
    }

    @Test fun `partial prefix and cached seek range are reused while only holes are fetched`() = runBlocking {
        fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = File(root, digest("${account.origin.length}:${account.origin}${account.user.id}"))
        val partial = SimpleCache(directory, LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
            StandaloneDatabaseProvider(context))
        try {
            val source = CacheDataSource.Factory().setCache(partial)
                .setCacheKeyFactory { digest(it.uri.toString()) }
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(client))
            for (position in listOf(0L, 2048L)) {
                CacheWriter(source.createDataSource(), DataSpec.Builder().setUri(uri())
                    .setPosition(position).setLength(512).build(), null, null).cache()
            }
        } finally {
            partial.release()
        }
        assertEquals("bytes=0-511", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=2048-2559", server.takeRequest().getHeader("Range"))
        val restored = lease()
        assertArrayEquals(bytes.copyOfRange(2048, 2560), read(restored, position = 2048, length = 512))
        withTimeout(5_000) { requireNotNull(restored.cacheCurrent(uri())).join() }
        assertEquals("bytes=512-2047", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=2560-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(bytes, read(restored))
        assertEquals(4, server.requestCount)
    }

    @Test fun `eviction bounds stored bytes and evicted data is fetched again`() {
        cache = AudioCache(context, maxBytes = 4096, fragmentBytes = 1024).apply { setAccount(account) }
        val handle = lease()
        fill(handle, uri("/api/stream/first"))
        fill(handle, uri("/api/stream/second"))
        assertTrue(root.walkTopDown().filter { it.extension == "exo" }.sumOf { it.length() } <= 4096)
        assertArrayEquals(bytes, read(handle, uri("/api/stream/first")))
        assertEquals(3, server.requestCount)
    }

    @Test fun `shrinking immediately evicts least recently used audio without revoking leases`() {
        cache = AudioCache(context, maxBytes = 12288, fragmentBytes = 1024).apply { setAccount(account) }
        val handle = lease()
        val first = uri("/api/stream/first")
        val second = uri("/api/stream/second")
        val third = uri("/api/stream/third")
        fill(handle, first)
        fill(handle, second)
        fill(handle, third)
        assertEquals(12288L, runBlocking { cache.usedBytes() })
        Thread.sleep(10) // Make the touch newer than every committed span, even on coarse clocks.
        assertArrayEquals(bytes, read(handle, first))
        cache.setMaxBytes(8192)
        assertEquals(8192L, storedBytes())
        assertEquals(8192L, runBlocking { cache.usedBytes() })
        assertArrayEquals(bytes, read(handle, first))
        assertArrayEquals(bytes, read(lease(), third))
        assertEquals(3, server.requestCount)
        assertArrayEquals(bytes, read(handle, second))
        assertEquals(4, server.requestCount)
    }

    @Test fun `growth retains existing audio and enlarged limit survives lease recreation`() {
        cache = AudioCache(context, maxBytes = 4096, fragmentBytes = 1024).apply { setAccount(account) }
        val handle = lease()
        val second = uri("/api/stream/second")
        val third = uri("/api/stream/third")
        fill(handle)
        cache.setMaxBytes(12288)
        fill(handle, second)
        handle.close()
        val recreated = lease()
        fill(recreated, third)
        assertEquals(12288L, storedBytes())
        listOf(uri(), second, third).forEach { assertArrayEquals(bytes, read(recreated, it)) }
        assertEquals(3, server.requestCount)
    }

    @Test fun `shrinking preserves an already open reader even when its span is evicted`() {
        cache = AudioCache(context, maxBytes = 8192, fragmentBytes = 4096).apply { setAccount(account) }
        val handle = lease()
        fill(handle)
        val source = handle.factory.createDataSource()
        try {
            source.open(DataSpec(uri()))
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(128)
            var count = source.read(buffer, 0, buffer.size)
            output.write(buffer, 0, count)
            Thread.sleep(10)
            val second = uri("/api/stream/second")
            fill(handle, second)
            cache.setMaxBytes(4096)
            assertEquals(4096L, storedBytes())
            while (true) {
                count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, count)
            }
            assertArrayEquals(bytes, output.toByteArray())
            assertArrayEquals(bytes, read(handle, second))
            assertEquals(2, server.requestCount)
            assertArrayEquals(bytes, read(lease()))
            assertEquals(3, server.requestCount)
        } finally {
            source.close()
        }
    }

    @Test fun `in flight writer commits under resized limit and remains usable`() = runBlocking {
        cache = AudioCache(context, maxBytes = 8192, fragmentBytes = 1024).apply { setAccount(account) }
        val handle = lease()
        fill(handle)
        val requested = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested.countDown()
                check(proceed.await(5, TimeUnit.SECONDS))
                return response(request)
            }
        }
        val second = uri("/api/stream/second")
        val download = requireNotNull(handle.cacheCurrent(second))
        try {
            assertTrue(requested.await(5, TimeUnit.SECONDS))
            cache.setMaxBytes(2048)
            assertTrue(storedBytes() <= 2048)
        } finally {
            proceed.countDown()
        }
        withTimeout(5_000) { download.join() }
        assertEquals(2048L, storedBytes())
        assertArrayEquals(bytes.copyOfRange(2048, 4096), read(handle, second, position = 2048))
        assertEquals(2, server.requestCount)
        cache.setMaxBytes(8192)
        fill(handle, uri("/api/stream/third"))
        assertArrayEquals(bytes, read(handle, uri("/api/stream/third")))
        assertEquals(3, server.requestCount)
    }

    @Test fun `invalid runtime limits are rejected without changing the cache`() {
        cache = AudioCache(context, maxBytes = 4096).apply { setAccount(account) }
        val handle = lease()
        fill(handle)
        listOf(0L, -1L, Long.MIN_VALUE).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { cache.setMaxBytes(invalid) }
        }
        assertEquals(4096L, storedBytes())
        assertArrayEquals(bytes, read(handle))
        assertEquals(1, server.requestCount)
    }

    private fun storedBytes() = root.walkTopDown().filter { it.extension == "exo" }.sumOf { it.length() }

    @Test fun `usage excludes another account even while its old reader retains cache files`() = runBlocking {
        val first = lease()
        fill(first)
        val source = first.factory.createDataSource()
        try {
            source.open(DataSpec(uri()))
            val other = account.copy(user = User("other"))
            cache.setAccount(other)
            assertEquals(bytes.size.toLong(), storedBytes())
            assertEquals(0L, cache.usedBytes())
            fill(lease(other))
            assertEquals(bytes.size.toLong() * 2, storedBytes())
            assertEquals(bytes.size.toLong(), cache.usedBytes())
        } finally { source.close() }
        cache.setAccount(null)
        assertEquals(0L, cache.usedBytes())
    }

    @Test fun `account user origin and logout changes revoke old factories and remove old bytes`() {
        val first = lease()
        fill(first)
        val other = account.copy(user = User("other"))
        cache.setAccount(other)
        assertThrows(IOException::class.java) { read(first) }
        assertArrayEquals(bytes, read(lease(other)))
        assertEquals(2, server.requestCount)
        val otherOrigin = other.copy(origin = "https://other.example")
        cache.setAccount(otherOrigin)
        assertArrayEquals(bytes, read(lease(otherOrigin)))
        assertEquals(3, server.requestCount)
        cache.setAccount(null)
        assertTrue(root.listFiles().isNullOrEmpty())
        assertThrows(IOException::class.java) { read(lease(account)) }
        cache.setAccount(account)
        assertArrayEquals(bytes, read(lease()))
        assertEquals(4, server.requestCount)
    }

    @Test fun `token renewal retains cached bytes but revokes old service and cosmetic changes do not`() {
        val first = lease()
        fill(first)
        cache.setAccount(account.copy(user = account.user.copy(name = "Renamed")))
        assertArrayEquals(bytes, read(first))
        val renewed = account.copy(session = account.session.copy(token = "renewed-session"))
        cache.setAccount(renewed)
        assertThrows(IOException::class.java) { read(first) }
        assertArrayEquals(bytes, read(lease(renewed)))
        assertEquals(1, server.requestCount)
    }

    @Test fun `logout cancels an in flight fill and late work cannot resurrect deleted data`() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = response(request)
                .throttleBody(1, 1, TimeUnit.SECONDS)
        }
        val handle = lease()
        val download = requireNotNull(handle.cacheCurrent(uri()))
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        cache.setAccount(null)
        withTimeout(5_000) { download.join() }
        assertTrue(root.listFiles().isNullOrEmpty())
        cache.setAccount(account)
        assertThrows(IOException::class.java) { read(handle) }
    }

    @Test fun `changing current track cancels its fill without downloading a queue`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = response(request)
                .throttleBody(1, 1, TimeUnit.SECONDS)
        }
        val handle = lease()
        val download = requireNotNull(handle.cacheCurrent(uri()))
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        handle.cacheCurrent(null)
        withTimeout(5_000) { download.join() }
        assertEquals(1, server.requestCount)
    }

    @Test fun `failed fill retries on resumed playback without retrying every player event`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        val handle = lease()
        withTimeout(5_000) { requireNotNull(handle.cacheCurrent(uri())).join() }
        assertNull(handle.cacheCurrent(uri()))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = response(request)
        }
        assertArrayEquals(bytes, read(handle))
        withTimeout(5_000) { requireNotNull(handle.cacheCurrent(uri(), retry = true)).join() }
        assertArrayEquals(bytes, read(handle))
        assertEquals(3, server.requestCount)
    }

    @Test fun `overlapping service leases share one cache and closing one does not release another`() {
        val first = lease()
        val second = lease()
        fill(first)
        first.close()
        assertArrayEquals(bytes, read(second))
        assertEquals(1, server.requestCount)
        second.close()
        assertArrayEquals(bytes, read(lease()))
        assertEquals(1, server.requestCount)
    }

    @Test fun `URL query and account credentials are not stored in cache filenames or index`() {
        val secretUrl = uri("/api/stream/song?token=private-query-value")
        fill(lease(), secretUrl)
        leases.forEach { it.close() }
        root.walkTopDown().filter { it.isFile }.forEach {
            assertFalse(it.name.contains("private-query-value"))
            assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains("private-query-value"))
            assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains("test-session"))
        }
    }

    @Test fun `upstream is bound to original account even before a stale request starts`() {
        var active: Account? = account
        val api = ServerApi({ active }, {}, client)
        val handle = lease(calls = api.playbackCalls(account))
        active = account.copy(user = User("other"))
        assertThrows(IOException::class.java) { read(handle) }
        assertEquals(0, server.requestCount)
    }

    @Test fun `unavailable cache directory falls back to normal streaming`() {
        root.writeText("not a directory")
        assertThrows(IOException::class.java) { runBlocking { cache.usedBytes() } }
        assertArrayEquals(bytes, read(lease()))
        assertEquals(1, server.requestCount)
        root.delete()
    }

    @Test fun `buffered playback does not prevent the entire current track being cached`() {
        val handle = lease()
        val streaming = handle.factory.createDataSource()
        try {
            streaming.open(DataSpec(uri()))
            assertEquals(128, streaming.read(ByteArray(128), 0, 128))
            fill(handle)
            assertEquals(2, server.requestCount)
            assertArrayEquals(bytes, read(handle))
            assertEquals(2, server.requestCount)
        } finally {
            streaming.close()
        }
    }

    @Test fun `same URL edits invalidate cached bytes across recreation without dropping other songs`() {
        val handle = lease()
        fill(handle)
        fill(handle, uri("/api/stream/unchanged"))
        cache.invalidate(account, uri().toString())
        handle.close()
        val changed = bytes.reversedArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody(Buffer().write(changed))
        }
        cache = AudioCache(context).apply { setAccount(account) }
        val restored = lease()
        assertArrayEquals(bytes, read(restored, uri("/api/stream/unchanged")))
        assertEquals(2, server.requestCount)
        fill(restored)
        assertArrayEquals(changed, read(restored))
        assertEquals(3, server.requestCount)
        cache.invalidate(account.copy(user = User("other")), uri().toString())
        assertArrayEquals(changed, read(restored))
        assertEquals(3, server.requestCount)
    }

    @Test fun `an in flight old file cannot repopulate an invalidated URL`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = response(request)
                .throttleBody(1, 1, TimeUnit.SECONDS)
        }
        val handle = lease()
        val oldFill = requireNotNull(handle.cacheCurrent(uri()))
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val changed = bytes.reversedArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody(Buffer().write(changed))
        }
        val replacements = cache.invalidate(account, uri().toString())
        assertEquals(1, replacements.size)
        withTimeout(5_000) {
            oldFill.join()
            replacements.forEach { it.join() }
        }
        assertArrayEquals(changed, read(handle))
        assertEquals(2, server.requestCount)
    }

    @Test fun `editing the selected cached track immediately fills its new revision without a player event`() = runBlocking {
        val handle = lease()
        withTimeout(5_000) { requireNotNull(handle.cacheCurrent(uri())).join() }
        val changed = bytes.reversedArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody(Buffer().write(changed))
        }
        val replacements = cache.invalidate(account, uri().toString())
        assertEquals(1, replacements.size)
        withTimeout(5_000) { replacements.forEach { it.join() } }
        assertArrayEquals(changed, read(handle))
        assertEquals(2, server.requestCount)
        handle.close()
        cache = AudioCache(context).apply { setAccount(account) }
        assertArrayEquals(changed, read(lease()))
        assertEquals(2, server.requestCount)
    }

    @Test fun `expiry before cached open clears the expected session outside the cache monitor`() {
        verifyCachedExpiry(alreadyOpen = false)
    }

    @Test fun `expiry during cached playback rejects reads and clears audio without HTTP`() {
        verifyCachedExpiry(alreadyOpen = true)
    }

    private fun verifyCachedExpiry(alreadyOpen: Boolean) {
        var time = Instant.parse("2099-12-31T23:59:59Z")
        val cleared = mutableListOf<String>()
        cache = AudioCache(context, now = { time }, onSessionExpired = { token ->
            assertFalse("Session cleanup must not hold the cache monitor", Thread.holdsLock(cache))
            cleared.add(token)
            if (token == account.session.token) cache.setAccount(null)
        }).apply { setAccount(account) }
        val handle = lease()
        fill(handle)
        val source = handle.factory.createDataSource()
        try {
            if (alreadyOpen) source.open(DataSpec(uri()))
            time = Instant.parse(account.session.expiresAt)
            assertThrows(IOException::class.java) {
                if (alreadyOpen) source.read(ByteArray(64), 0, 64)
                else source.open(DataSpec(uri()))
            }
        } finally {
            source.close()
        }
        assertEquals(listOf(account.session.token), cleared)
        assertEquals(1, server.requestCount)
        assertTrue(root.listFiles().isNullOrEmpty())
        assertThrows(IOException::class.java) { read(handle) }
    }
}
