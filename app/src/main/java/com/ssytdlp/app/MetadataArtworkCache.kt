package com.ssytdlp.app

import android.graphics.Bitmap
import android.util.Base64
import com.ssytdlp.app.core.Account
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class MetadataArtworkCache(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val maxBytes: Int = 16 * 1024 * 1024,
    private val decode: suspend (String, Int) -> Bitmap? = { artwork, pixels ->
        val started = System.nanoTime()
        try {
            val bytes = Base64.decode(artwork.substringAfter(","), Base64.DEFAULT)
            decodeArtworkBitmap(bytes, targetPixels = pixels)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        } finally {
            DebugLog.timing(DebugEvent.ARTWORK_DECODED, started)
        }
    }
) {
    private data class Key(val source: String, val pixels: Int)
    private val images = LinkedHashMap<Key, Bitmap>(8, 0.75f, true)
    private val pending = LinkedHashMap<Key, Deferred<Bitmap?>>()
    private val capacity = Semaphore(4)
    private val workers = Semaphore(2)
    private val mutableGeneration = MutableStateFlow(0L)
    val generation = mutableGeneration.asStateFlow()
    private var owner: MetadataOwner? = null
    private var bytes = 0L

    @Synchronized
    fun setAccount(account: Account?) {
        val next = account?.let(::MetadataOwner)
        if (owner == next) return
        owner = next
        clear()
    }

    @Synchronized
    fun belongsTo(expectedOwner: MetadataOwner): Boolean = owner == expectedOwner

    @Synchronized
    fun clear() {
        mutableGeneration.value++
        pending.values.toList().forEach { it.cancel() }
        pending.clear()
        // Displayed bitmaps may outlive their cache entries; never recycle them on eviction.
        images.clear()
        bytes = 0
    }

    @Synchronized
    fun peek(artwork: String?, pixels: Int, expectedGeneration: Long): Bitmap? {
        if (expectedGeneration != mutableGeneration.value || !valid(artwork)) return null
        val key = images.keys.filter { it.source == artwork && it.pixels >= pixels }
            .minByOrNull { it.pixels } ?: return null
        return images[key]
    }

    suspend fun load(artwork: String?, pixels: Int, expectedGeneration: Long): Bitmap? {
        if (!valid(artwork)) return null
        val target = artworkSizeBucket(pixels)
        val existing = synchronized(this) {
            if (expectedGeneration != mutableGeneration.value) return null
            peek(artwork, target, expectedGeneration)?.let {
                DebugLog.event(DebugEvent.ARTWORK_CACHE_HIT)
                return it
            }
            pending.entries.firstOrNull { it.key.source == artwork && it.key.pixels >= target }?.value
        }
        val work = existing ?: run {
            // Wait in the consumer's cancellable coroutine instead of evicting visible work.
            capacity.acquire()
            var retained = false
            try {
                synchronized(this) {
                    if (expectedGeneration != mutableGeneration.value) return null
                    peek(artwork, target, expectedGeneration)?.let { return it }
                    pending.entries.firstOrNull { it.key.source == artwork && it.key.pixels >= target }?.value
                        ?: start(Key(artwork!!, target), expectedGeneration).also { retained = true }
                }
            } finally {
                if (!retained) capacity.release()
            }
        }
        val bitmap = work.await()
        return synchronized(this) { bitmap.takeIf { expectedGeneration == mutableGeneration.value } }
    }

    private fun start(key: Key, expectedGeneration: Long): Deferred<Bitmap?> {
        val work = scope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            workers.withPermit {
                currentCoroutineContext().ensureActive()
                val bitmap = decode(key.source, key.pixels)
                currentCoroutineContext().ensureActive()
                synchronized(this@MetadataArtworkCache) {
                    if (expectedGeneration != mutableGeneration.value) return@async null
                    if (bitmap != null) {
                        val entryBytes = cost(key, bitmap)
                        if (entryBytes <= maxBytes) {
                            images.put(key, bitmap)?.let { bytes -= cost(key, it) }
                            bytes += entryBytes
                            while (bytes > maxBytes || images.size > 16) {
                                val oldest = images.entries.first()
                                bytes -= cost(oldest.key, oldest.value)
                                images.remove(oldest.key)
                            }
                        }
                    }
                }
                bitmap
            }
        }
        pending[key] = work
        work.invokeOnCompletion {
            synchronized(this) { if (pending[key] === work) pending.remove(key) }
            capacity.release()
        }
        work.start()
        return work
    }

    private fun cost(key: Key, bitmap: Bitmap): Long = key.source.length * 2L + bitmap.allocationByteCount

    private fun valid(artwork: String?): Boolean =
        artwork != null && artwork.length <= 2_800_000 && artwork.startsWith("data:image/")
}

internal fun artworkSizeBucket(pixels: Int): Int {
    var size = 64
    while (size < pixels && size < 1024) size *= 2
    return size
}
