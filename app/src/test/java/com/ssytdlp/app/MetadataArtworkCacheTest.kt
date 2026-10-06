package com.ssytdlp.app

import android.app.Application
import android.graphics.Bitmap
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.User
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
class MetadataArtworkCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val art = "data:image/png;base64,first"
    private val owner = Account("https://music.example", User("one"),
        Session("test", "2099-01-01T00:00:00Z"))

    @After fun teardown() { scope.cancel() }

    private fun image() = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    @Test fun repeatedAndSmallerRequestsReuseLargerBitmap() = runBlocking {
        val calls = AtomicInteger()
        val cache = MetadataArtworkCache(scope, decode = { _, _ -> calls.incrementAndGet(); image() })
        val first = cache.load(art, 512, 0)
        assertSame(first, cache.load(art, 512, 0))
        assertSame(first, cache.load(art, 64, 0))
        assertEquals(1, calls.get())
        assertSame(first, cache.peek(art, 256, 0))
    }

    @Test fun smallImageIsUpgradedWithoutDiscardingItWhileLoading() = runBlocking {
        val sizes = mutableListOf<Int>()
        val cache = MetadataArtworkCache(scope, decode = { _, size -> sizes.add(size); image() })
        val small = cache.load(art, 48, 0)
        assertSame(small, cache.peek(art, 0, 0))
        assertNull(cache.peek(art, 512, 0))
        val large = cache.load(art, 320, 0)
        assertNotSame(small, large)
        assertEquals(listOf(64, 512), sizes)
        assertSame(large, cache.load(art, 512, 0))
    }

    @Test fun simultaneousRequestsShareWorkAndSurviveOneConsumersCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val cache = MetadataArtworkCache(scope, decode = { _, _ ->
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            image()
        })
        val first = async { cache.load(art, 512, 0) }
        entered.await()
        val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { cache.load(art, 64, 0) }
        first.cancel()
        release.complete(Unit)
        assertNotNull(second.await())
        assertEquals(1, calls.get())
    }

    @Test fun accountChangesClearBitmapsAndRejectOldGenerations() = runBlocking {
        val cache = MetadataArtworkCache(scope, decode = { _, _ -> image() })
        cache.setAccount(owner)
        val version = cache.generation.value
        val bitmap = cache.load(art, 64, version)
        cache.setAccount(owner.copy(user = owner.user.copy(name = "Updated")))
        assertEquals(version, cache.generation.value)
        assertSame(bitmap, cache.load(art, 64, version))
        for (next in listOf(owner.copy(origin = "https://other.example"),
            owner.copy(user = User("two")), owner.copy(session = owner.session.copy(token = "new")), null)) {
            val before = cache.generation.value
            cache.setAccount(next)
            assertNull(cache.peek(art, 64, before))
            assertNull(cache.load(art, 64, before))
        }
        assertFalse(bitmap!!.isRecycled)
    }

    @Test fun clearingCancelsPendingDecodeAndDoesNotRepopulateCache() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cache = MetadataArtworkCache(scope, decode = { _, _ ->
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
            image()
        })
        val pending = async { cache.load(art, 64, 0) }
        entered.await()
        cache.clear()
        pending.join()
        assertTrue(pending.isCancelled)
        assertNull(cache.peek(art, 64, cache.generation.value))
    }

    @Test fun capacityPressureDoesNotCancelVisibleConsumers() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = MetadataArtworkCache(scope, decode = { _, _ ->
            entered.complete(Unit)
            release.await()
            image()
        })
        val requests = (1..6).map { index ->
            async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { cache.load("$art-$index", 64, 0) }
        }
        entered.await()
        release.complete(Unit)
        assertTrue(requests.awaitAll().all { it != null })
    }

    @Test fun changedContentAndMemoryBudgetEvictWithoutRecyclingDisplayedImages() = runBlocking {
        val calls = AtomicInteger()
        val cache = MetadataArtworkCache(scope, maxBytes = 400, decode = { _, _ -> calls.incrementAndGet(); image() })
        val first = cache.load(art, 64, 0)!!
        val next = cache.load("$art-changed", 64, 0)
        assertNotSame(first, next)
        assertNull(cache.peek(art, 64, 0))
        assertFalse(first.isRecycled)
        cache.load(art, 64, 0)
        assertEquals(3, calls.get())
    }

    @Test fun malformedAndOversizedInputNeverReachesDecoder() = runBlocking {
        val cache = MetadataArtworkCache(scope, decode = { _, _ -> fail("Unexpected decode"); null })
        assertNull(cache.load(null, 64, 0))
        assertNull(cache.load("https://music.example/art.jpg", 64, 0))
        assertNull(cache.load("data:image/png;base64," + "a".repeat(2_800_000), 64, 0))
    }

    @Test fun targetSizeBucketsAreBounded() {
        assertEquals(64, artworkSizeBucket(0))
        assertEquals(128, artworkSizeBucket(96))
        assertEquals(512, artworkSizeBucket(320))
        assertEquals(1024, artworkSizeBucket(Int.MAX_VALUE))
    }
}
