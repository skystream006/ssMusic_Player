package com.ssytdlp.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.User
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackArtworkCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val owner = Account("https://music.example", User("one"),
        Session("test", "2099-01-01T00:00:00Z"))
    private val accounts = MutableStateFlow<Account?>(owner)
    private val path = "/api/jobs/source/artwork/song.mp3?v=1"
    private val calls = AtomicInteger()
    private val decodes = AtomicInteger()

    @After fun teardown() { scope.cancel() }

    private fun image() = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()

    private fun cache(
        maxBytes: Long = 8L * 1024 * 1024,
        maxEntries: Int = 128,
        maxPending: Int = 8,
        maxConcurrent: Int = 4,
        fetch: suspend (Account, String) -> ByteArray = { _, _ -> calls.incrementAndGet(); byteArrayOf(1) },
        decode: suspend (ByteArray) -> ImageBitmap? = { decodes.incrementAndGet(); image() }
    ) = TrackArtworkCache({ accounts.value }, fetch, scope, maxBytes, maxEntries, maxPending, maxConcurrent, decode)
        .also { it.observeAccount(accounts) }

    @Test fun repeatedRelativeAndAbsoluteUrlsReuseTheSameDecodedBitmap() = runBlocking<Unit> {
        val cache = cache()
        val first = cache.load(owner, path)
        assertNotNull(first)
        repeat(3) { assertSame(first, cache.load(owner, owner.origin + path)) }
        assertSame(first, cache.peek(owner, path))
        assertEquals(1, calls.get())
        assertEquals(1, decodes.get())
        assertEquals(1, cache.size)
        assertTrue(cache.retainedBytes >= first!!.asAndroidBitmap().allocationByteCount)
    }

    @Test fun fullUrlIncludingRevisionAndQueryIsTheCacheKey() = runBlocking<Unit> {
        val cache = cache()
        val first = cache.load(owner, path)
        val revision = cache.load(owner, path.replace("v=1", "v=2"))
        val variant = cache.load(owner, "$path&format=webp")
        assertNotSame(first, revision)
        assertNotSame(first, variant)
        assertSame(first, cache.peek(owner, path))
        assertEquals(3, calls.get())
        assertEquals(3, decodes.get())
    }

    @Test fun displayNameChangesRetainArtworkButEveryOwnerIdentityChangeClearsIt() = runBlocking<Unit> {
        val cache = cache()
        val first = cache.load(owner, path)!!
        accounts.value = owner.copy(user = owner.user.copy(name = "Renamed"))
        assertSame(first, cache.peek(accounts.value, path))
        for (next in listOf(owner.copy(origin = "https://other.example"),
            owner.copy(user = User("two")),
            owner.copy(session = owner.session.copy(token = "replacement")),
            owner.copy(session = owner.session.copy(expiresAt = "2099-02-01T00:00:00Z")), null)) {
            accounts.value = next
            assertNull(cache.peek(owner, path))
            assertNull(cache.load(owner, path))
            assertEquals(0, cache.size)
            assertEquals(0L, cache.retainedBytes)
            if (next != null) assertNotNull(cache.load(next, path))
        }
        assertFalse(first.asAndroidBitmap().isRecycled)
    }

    @Test fun signOutAndBackInWithoutComposingInvalidatesSameSessionArtwork() = runBlocking<Unit> {
        val cache = cache()
        val first = cache.load(owner, path)
        accounts.value = null
        accounts.value = owner
        assertNull(cache.peek(owner, path))
        assertNotSame(first, cache.load(owner, path))
        assertEquals(2, calls.get())
    }

    @Test fun currentAccountIsCheckedEvenWithoutAnObserver() = runBlocking<Unit> {
        val cache = TrackArtworkCache({ accounts.value }, { _, _ -> byteArrayOf(1) }, scope, decode = { image() })
        assertNotNull(cache.load(owner, path))
        accounts.value = owner.copy(user = User("other"))
        assertNull(cache.peek(owner, path))
        assertNull(cache.load(owner, path))
        assertEquals(0, cache.size)
    }

    @Test fun lateNonCancellableDecodeCannotRepopulateAfterAccountSwitchAndReturn() = runBlocking<Unit> {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cache = cache(decode = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    image()
                }
            })
            val request = async { cache.load(owner, path) }
            entered.await()
            accounts.value = owner.copy(user = User("other"))
            accounts.value = owner
            release.complete(Unit)
            request.join()
            assertTrue(request.isCancelled)
            assertNull(cache.peek(owner, path))
            assertEquals(0, cache.size)
            assertEquals(0L, cache.retainedBytes)
            assertNotNull(cache.load(owner, path))
        }
    }

    @Test fun signOutCancelsDownloadAndDoesNotStartDecode() = runBlocking<Unit> {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val cache = cache(fetch = { _, _ ->
                try {
                    entered.complete(Unit)
                    CompletableDeferred<ByteArray>().await()
                } finally { cancelled.complete(Unit) }
            })
            val request = async { cache.load(owner, path) }
            entered.await()
            accounts.value = null
            cancelled.await()
            request.join()
            assertTrue(request.isCancelled)
            assertEquals(0, decodes.get())
            assertEquals(0, cache.pendingCount)
        }
    }

    @Test fun simultaneousConsumersShareDownloadAndDecodeAndSurviveOneCancellation() = runBlocking<Unit> {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cache = cache(fetch = { _, _ ->
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                byteArrayOf(1)
            })
            val library = async { cache.load(owner, path) }
            entered.await()
            val queue = async(start = CoroutineStart.UNDISPATCHED) { cache.load(owner, path) }
            library.cancel()
            release.complete(Unit)
            val bitmap = queue.await()
            assertNotNull(bitmap)
            assertSame(bitmap, cache.peek(owner, path))
            assertEquals(1, calls.get())
            assertEquals(1, decodes.get())
        }
    }

    @Test fun remountJoinsBoundedWorkAfterAllOriginalConsumersLeave() = runBlocking<Unit> {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cache = cache(fetch = { _, _ ->
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                byteArrayOf(1)
            })
            val oldRow = async { cache.load(owner, path) }
            entered.await()
            oldRow.cancel()
            oldRow.join()
            val remounted = async(start = CoroutineStart.UNDISPATCHED) { cache.load(owner, path) }
            release.complete(Unit)
            assertNotNull(remounted.await())
            assertEquals(1, calls.get())
            assertEquals(1, decodes.get())
        }
    }

    @Test fun entryLimitEvictsLeastRecentlyUsedWithoutRecyclingDisplayedBitmaps() = runBlocking<Unit> {
        val cache = cache(maxEntries = 2)
        val first = cache.load(owner, path)!!
        val second = cache.load(owner, "$path&second")!!
        assertSame(first, cache.peek(owner, path))
        assertNotNull(cache.load(owner, "$path&third"))
        assertEquals(2, cache.size)
        assertSame(first, cache.peek(owner, path))
        assertNull(cache.peek(owner, "$path&second"))
        assertFalse(second.asAndroidBitmap().isRecycled)
        assertNotNull(cache.load(owner, "$path&second"))
        assertEquals(4, calls.get())
    }

    @Test fun byteLimitCountsDecodedAllocationAndUrlAndSkipsOversizedEntries() = runBlocking<Unit> {
        val cache = cache(maxBytes = 700)
        val first = cache.load(owner, path)!!
        assertTrue(cache.retainedBytes in 1..700)
        assertNotNull(cache.load(owner, "$path&next"))
        assertNull(cache.peek(owner, path))
        assertEquals(1, cache.size)
        assertTrue(cache.retainedBytes <= 700)
        assertFalse(first.asAndroidBitmap().isRecycled)
        assertNotNull(cache.load(owner, "$path&long=" + "a".repeat(400)))
        assertEquals(1, cache.size)
        assertNull(cache.peek(owner, "$path&long=" + "a".repeat(400)))
        assertTrue(cache.retainedBytes <= 700)
    }

    @Test fun zeroEntryOrByteBudgetsStillReturnButDoNotRetainArtwork() = runBlocking<Unit> {
        for (cache in listOf(cache(maxEntries = 0), cache(maxBytes = 0))) {
            assertNotNull(cache.load(owner, path))
            assertNull(cache.peek(owner, path))
            assertEquals(0, cache.size)
            assertEquals(0L, cache.retainedBytes)
        }
    }

    @Test fun concurrentWorkersAndPendingRequestsStayBoundedWithoutDroppingRows() = runBlocking<Unit> {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val cache = cache(maxPending = 3, maxConcurrent = 2, fetch = { _, _ ->
                calls.incrementAndGet()
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                if (count == 2) entered.complete(Unit)
                try { release.await(); byteArrayOf(1) } finally { active.decrementAndGet() }
            })
            val requests = (1..12).map { index ->
                async(start = CoroutineStart.UNDISPATCHED) { cache.load(owner, "$path&row=$index") }
            }
            entered.await()
            assertEquals(3, cache.pendingCount)
            assertEquals(2, calls.get())
            release.complete(Unit)
            assertTrue(requests.awaitAll().all { it != null })
            assertTrue(peak.get() <= 2)
            assertEquals(12, calls.get())
            assertEquals(12, decodes.get())
        }
    }

    @Test fun cancellingCapacityWaiterNeverDownloadsItOrLeaksAPermit() = runBlocking<Unit> {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cache = cache(maxPending = 1, fetch = { _, _ ->
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                byteArrayOf(1)
            })
            val first = async { cache.load(owner, path) }
            entered.await()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { cache.load(owner, "$path&cancelled") }
            waiting.cancel()
            waiting.join()
            assertEquals(1, cache.pendingCount)
            release.complete(Unit)
            assertNotNull(first.await())
            assertNotNull(cache.load(owner, "$path&next"))
            assertEquals(2, calls.get())
            assertNull(cache.peek(owner, "$path&cancelled"))
        }
    }

    @Test fun concurrencyPermitIsHeldUntilDecodingFinishes() = runBlocking<Unit> {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cache = cache(maxConcurrent = 2, decode = {
                if (decodes.incrementAndGet() == 2) entered.complete(Unit)
                release.await()
                image()
            })
            val requests = (1..8).map { index ->
                async(start = CoroutineStart.UNDISPATCHED) { cache.load(owner, "$path&row=$index") }
            }
            entered.await()
            assertEquals(2, calls.get())
            assertEquals(2, decodes.get())
            release.complete(Unit)
            assertTrue(requests.awaitAll().all { it != null })
            assertEquals(8, calls.get())
            assertEquals(8, decodes.get())
        }
    }

    @Test fun accountSwitchRejectsCapacityWaitersAndAllowsCurrentOwnerRequests() = runBlocking<Unit> {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val cache = cache(maxPending = 1, fetch = { expected, _ ->
                calls.incrementAndGet()
                if (expected == owner) {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                byteArrayOf(1)
            })
            val first = async { cache.load(owner, path) }
            entered.await()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { cache.load(owner, "$path&waiting") }
            accounts.value = owner.copy(user = User("other"))
            first.join()
            assertTrue(first.isCancelled)
            assertNull(waiting.await())
            assertNotNull(cache.load(accounts.value, path))
            assertEquals(2, calls.get())
            assertEquals(1, decodes.get())
        }
    }

    @Test fun cancelledCacheWorkPropagatesCancellationAndReleasesCapacityForRetry() = runBlocking<Unit> {
        withTimeout(5_000) {
            val cache = cache(maxPending = 1, fetch = { _, _ ->
                if (calls.incrementAndGet() == 1) throw CancellationException("Cancelled download")
                byteArrayOf(1)
            })
            val failed = async { cache.load(owner, path) }
            failed.join()
            assertTrue(failed.isCancelled)
            while (cache.pendingCount != 0) delay(1)
            assertNotNull(cache.load(owner, path))
            assertEquals(2, calls.get())
        }
    }

    @Test fun networkAndDecodeFailuresAreNotCachedAndCanBeRetried() = runBlocking<Unit> {
        withTimeout(5_000) {
            val cache = cache(maxPending = 1, fetch = { _, _ ->
                if (calls.incrementAndGet() == 1) throw IOException("Unavailable")
                byteArrayOf(1)
            }, decode = {
                when (decodes.incrementAndGet()) {
                    1 -> throw IllegalArgumentException("Malformed")
                    2 -> null
                    else -> image()
                }
            })
            repeat(3) {
                assertNull(cache.load(owner, path))
                assertNull(cache.peek(owner, path))
                while (cache.pendingCount != 0) delay(1)
            }
            assertNotNull(cache.load(owner, path))
            assertEquals(4, calls.get())
            assertEquals(3, decodes.get())
        }
    }

    @Test fun invalidUrlsAndMissingAccountsNeverReachTheDownloader() = runBlocking<Unit> {
        val cache = cache()
        for (invalid in listOf(null, "", " ", "https://other.example/api/art", "/other/image",
            "$path#fragment", "$path&" + "a".repeat(16_384))) {
            assertNull(cache.load(owner, invalid))
            assertNull(cache.peek(owner, invalid))
        }
        assertNull(cache.load(null, path))
        assertEquals(0, calls.get())
        assertEquals(0, decodes.get())
    }
}
