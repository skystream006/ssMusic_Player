package com.ssytdlp.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ServerResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class TrackArtworkCache(
    private val currentAccount: () -> Account?,
    private val fetch: suspend (Account, String) -> ByteArray,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val maxBytes: Long = 8L * 1024 * 1024,
    private val maxEntries: Int = 128,
    maxPending: Int = 8,
    maxConcurrent: Int = 4,
    private val decode: suspend (ByteArray) -> ImageBitmap? = { decodeTrackArtwork(it) }
) {
    private data class Key(val owner: MetadataOwner, val url: String, val generation: Long)
    private data class Entry(val bitmap: ImageBitmap, val bytes: Long)
    private val images = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val pending = LinkedHashMap<String, Deferred<ImageBitmap?>>()
    private val capacity = Semaphore(maxPending)
    private val workers = Semaphore(maxConcurrent)
    private var owner: MetadataOwner? = null
    private var generation = 0L
    private var bytes = 0L

    init {
        require(maxBytes >= 0 && maxEntries >= 0)
    }

    internal val retainedBytes: Long @Synchronized get() = bytes
    internal val size: Int @Synchronized get() = images.size
    internal val pendingCount: Int @Synchronized get() = pending.size

    fun observeAccount(accounts: StateFlow<Account?>) {
        // Invalidate on the emitting thread, including sign-out/sign-in before another UI frame.
        scope.launch(Dispatchers.Unconfined) {
            accounts.collect { synchronizeAccount() }
        }
    }

    @Synchronized
    fun synchronizeAccount() {
        val next = currentAccount()?.let(::MetadataOwner)
        if (next == owner) return
        owner = next
        generation++
        pending.values.toList().forEach { it.cancel() }
        pending.clear()
        // Rows may still display evicted bitmaps; never recycle them.
        images.clear()
        bytes = 0
    }

    @Synchronized
    fun peek(account: Account?, path: String?): ImageBitmap? {
        val key = requestKey(account, path) ?: return null
        return images[key.url]?.bitmap
    }

    suspend fun load(account: Account?, path: String?): ImageBitmap? {
        currentCoroutineContext().ensureActive()
        val key: Key
        val existing = synchronized(this) {
            key = requestKey(account, path) ?: return null
            images[key.url]?.let { return it.bitmap }
            pending[key.url]
        }
        val work = existing ?: run {
            // Excess rows wait in their own cancellable coroutines, not an unbounded work queue.
            capacity.acquire()
            var retained = false
            try {
                synchronized(this) {
                    if (!current(key)) return null
                    images[key.url]?.let { return it.bitmap }
                    pending[key.url] ?: start(account!!, key).also { retained = true }
                }
            } finally {
                if (!retained) capacity.release()
            }
        }
        val bitmap = work.await()
        currentCoroutineContext().ensureActive()
        return synchronized(this) { bitmap.takeIf { current(key) } }
    }

    private fun start(account: Account, key: Key): Deferred<ImageBitmap?> {
        // Shared work survives a row leaving composition, but remains bounded and account-owned.
        val work = scope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            try {
                workers.withPermit {
                    currentCoroutineContext().ensureActive()
                    if (!synchronized(this@TrackArtworkCache) { current(key) }) return@async null
                    val data = fetch(account, key.url)
                    currentCoroutineContext().ensureActive()
                    if (!synchronized(this@TrackArtworkCache) { current(key) }) return@async null
                    val bitmap = decode(data)
                    currentCoroutineContext().ensureActive()
                    synchronized(this@TrackArtworkCache) {
                        if (!current(key)) return@async null
                        if (bitmap != null) {
                            val cost = 128L + key.url.length * 2L + bitmap.asAndroidBitmap().allocationByteCount
                            if (maxEntries > 0 && cost <= maxBytes) {
                                images.put(key.url, Entry(bitmap, cost))?.let { bytes -= it.bytes }
                                bytes += cost
                                while (bytes > maxBytes || images.size > maxEntries) {
                                    val oldest = images.entries.first()
                                    bytes -= oldest.value.bytes
                                    images.remove(oldest.key)
                                }
                            }
                        }
                        bitmap
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        }
        pending[key.url] = work
        work.invokeOnCompletion {
            synchronized(this) { if (pending[key.url] === work) pending.remove(key.url) }
            capacity.release()
        }
        work.start()
        return work
    }

    private fun current(key: Key): Boolean {
        synchronizeAccount()
        return owner == key.owner && generation == key.generation
    }

    private fun requestKey(account: Account?, path: String?): Key? {
        synchronizeAccount()
        if (account == null || MetadataOwner(account) != owner || path.isNullOrBlank() || path.length > 16_384) return null
        val url = try { ServerResource.resolve(account.origin, path) } catch (_: IllegalArgumentException) { return null }
        return Key(MetadataOwner(account), url, generation)
    }
}
