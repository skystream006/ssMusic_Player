package com.ssytdlp.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ServerResource
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

/** One application-owned cache, with revocable service leases and deferred release of active readers. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioCache(
    context: Context,
    maxBytes: Long = OfflineCacheSize.DEFAULT.bytes,
    private val fragmentBytes: Long = 2L * 1024 * 1024,
    private val now: () -> Instant = Instant::now,
    private val onSessionExpired: (String) -> Unit = {}
) {
    private var maxBytes = maxBytes.also { require(it > 0) }
    private val directory = File(context.noBackupFilesDir, "audio")
    private val database = StandaloneDatabaseProvider(context)
    private val revisions = context.getSharedPreferences("audio_cache_revisions", Context.MODE_PRIVATE)
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = mutableMapOf<String, Entry>()
    private val handles = mutableSetOf<Handle>()
    private var account: Account? = null

    internal class Entry(val directory: File, val cache: SimpleCache, val evictor: ResizableEvictor) {
        var discard = false
    }

    @Synchronized fun setMaxBytes(bytes: Long) {
        require(bytes > 0)
        if (maxBytes == bytes) return
        maxBytes = bytes
        entries.values.forEach { it.evictor.resize(it.cache, bytes) }
    }

    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        synchronized(this@AudioCache) {
            val owner = account?.owner() ?: return@synchronized 0L
            entries[owner]?.takeUnless { it.discard }?.let { return@synchronized it.cache.cacheSpace }
            if (directory.exists() && !directory.isDirectory) throw IOException("Offline cache directory is unavailable.")
            val path = File(directory, owner).toPath()
            if (!Files.exists(path)) return@synchronized 0L
            var bytes = 0L
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (attributes.isRegularFile && file.fileName.toString().endsWith(".exo")) bytes += attributes.size()
                    return FileVisitResult.CONTINUE
                }
            })
            bytes
        }
    }

    /** Callbacks only use the SimpleCache monitor, never the enclosing AudioCache monitor. */
    internal class ResizableEvictor(maxBytes: Long) : CacheEvictor {
        private var delegate = LeastRecentlyUsedCacheEvictor(maxBytes)

        fun resize(cache: Cache, maxBytes: Long) = synchronized(cache) {
            val spans = cache.keys.flatMap { cache.getCachedSpans(it) }.sortedBy { it.lastTouchTimestamp }
            // Install before replay: eviction re-enters onSpanRemoved on this wrapper.
            delegate = LeastRecentlyUsedCacheEvictor(maxBytes)
            spans.forEach { delegate.onSpanAdded(cache, it) }
        }

        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() = delegate.onCacheInitialized()
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) =
            delegate.onStartFile(cache, key, position, length)
        override fun onSpanAdded(cache: Cache, span: CacheSpan) = delegate.onSpanAdded(cache, span)
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) = delegate.onSpanRemoved(cache, span)
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) =
            delegate.onSpanTouched(cache, oldSpan, newSpan)
    }

    @Synchronized fun setAccount(next: Account?) {
        val previousOwner = account?.owner()
        val nextOwner = next?.owner()
        if (nextOwner != previousOwner || next?.session?.token != account?.session?.token) {
            account = next
            entries.values.forEach { if (previousOwner != nextOwner) it.discard = true }
            handles.toList().forEach { it.close() }
        } else {
            account = next
        }
        if (nextOwner == null || revisions.getString("owner", null) != nextOwner) {
            revisions.edit().clear().putString("owner", nextOwner).commit()
        }
        // Also remove files left by an expired account or a process killed during sign-out.
        directory.listFiles()?.filter { it.name != nextOwner && it.name !in entries }?.forEach {
            runCatching { SimpleCache.delete(it, database) }
        }
    }

    @Synchronized fun invalidate(owner: Account, path: String?): List<Job> {
        if (path == null || account?.owner() != owner.owner() || account?.session?.token != owner.session.token) return emptyList()
        val uri = Uri.parse(ServerResource.resolve(owner.origin, path))
        // A new key also isolates a writer that was already in flight when the file changed.
        revisions.edit().putString(digest(uri.toString()), UUID.randomUUID().toString()).commit()
        return handles.mapNotNull { it.invalidate(uri) }
    }

    @Synchronized fun acquire(owner: Account, upstream: Call.Factory): Handle {
        val valid = account?.let {
            it.owner() == owner.owner() && it.session.token == owner.session.token
        } == true
        val key = owner.owner()
        val entry = if (!valid || entries[key]?.discard == true) null else entries[key] ?: runCatching {
            val evictor = ResizableEvictor(maxBytes)
            val cache = SimpleCache(File(directory, key), evictor, database)
            try {
                cache.checkInitialization()
                Entry(File(directory, key), cache, evictor).also { entries[key] = it }
            } catch (error: Exception) {
                cache.release()
                throw error
            }
        }.getOrNull()
        return Handle(entry, upstream, valid).also { handles.add(it) }
    }

    inner class Handle internal constructor(
        private val entry: Entry?,
        private val upstream: Call.Factory,
        private var active: Boolean
    ) : AutoCloseable {
        private val readers = mutableSetOf<Reader>()
        private var current: Uri? = null
        private var download: Reader? = null
        private var writer: CacheWriter? = null
        private var fillFailed = false

        internal fun invalidate(uri: Uri): Job? {
            if (current != uri) return null
            cacheCurrent(null)
            return cacheCurrent(uri)
        }

        val factory = DataSource.Factory {
            object : DataSource {
                private var reader: Reader? = null
                private val listeners = mutableListOf<TransferListener>()
                override fun addTransferListener(listener: TransferListener) { listeners.add(listener) }
                override fun open(dataSpec: DataSpec): Long {
                    checkSession()
                    val opened = synchronized(this@AudioCache) { reader().also { reader = it } }
                    listeners.forEach(opened.source::addTransferListener)
                    return opened.source.open(dataSpec.buildUpon()
                        .setFlags(dataSpec.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build())
                        .also { checkSession() }
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    checkSession()
                    return requireNotNull(reader).source.read(buffer, offset, length).also { checkSession() }
                }
                override fun getUri(): Uri? = reader?.source?.uri
                override fun getResponseHeaders(): Map<String, List<String>> = reader?.source?.responseHeaders.orEmpty()
                override fun close() {
                    reader?.close()
                    reader = null
                }
            }
        }

        /** Only the selected, started track is filled; no playlist prefetching. */
        fun cacheCurrent(uri: Uri?, retry: Boolean = false): Job? = synchronized(this@AudioCache) {
            if (!active || current == uri && (!retry || !fillFailed)) return@synchronized null
            current = uri
            fillFailed = false
            writer?.cancel()
            download?.cancel()
            writer = null
            download = null
            if (uri == null || entry == null) return@synchronized null
            val reader = reader(blocking = true)
            val task = CacheWriter(reader.source as CacheDataSource,
                DataSpec.Builder().setUri(uri).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build(),
                null, null)
            download = reader
            writer = task
            worker.launch {
                try {
                    reader.fill(task)
                } catch (_: IOException) {
                    // Opportunistic download failures must never interrupt playback.
                    synchronized(this@AudioCache) {
                        if (writer === task) fillFailed = true
                    }
                } finally {
                    runCatching { reader.close() }
                }
            }
        }

        private fun reader(blocking: Boolean = false): Reader {
            checkActive()
            return Reader(blocking).also { readers.add(it) }
        }

        private fun checkActive() = synchronized(this@AudioCache) {
            if (!active) throw IOException("The playback account changed or the player closed.")
        }

        private fun checkSession() {
            val expiredToken = synchronized(this@AudioCache) {
                checkActive()
                account?.takeUnless {
                    runCatching { Instant.parse(it.session.expiresAt).isAfter(now()) }.getOrDefault(false)
                }?.session?.token
            }
            if (expiredToken != null) {
                // SessionStore takes its own monitor before updating this cache; never call it
                // while holding the cache monitor, including on cached-only reads.
                onSessionExpired(expiredToken)
                throw IOException("The playback session expired.")
            }
        }

        override fun close() = synchronized(this@AudioCache) {
            active = false
            writer?.cancel()
            readers.forEach { it.cancel() }
            releaseIfIdle()
        }

        private fun releaseIfIdle() {
            if (active || readers.isNotEmpty()) return
            if (!handles.remove(this)) return
            val entry = entry
            if (entry != null && handles.none { it.entry === entry }) {
                entry.cache.release()
                entries.remove(entry.directory.name)
                if (entry.discard) runCatching { SimpleCache.delete(entry.directory, database) }
            }
        }

        private inner class Reader(blocking: Boolean) : AutoCloseable {
            private val calls = mutableSetOf<Call>()
            private val keys = mutableMapOf<Uri, String>()
            private var cancelled = false
            private var fillingThread: Thread? = null
            private val remote = OkHttpDataSource.Factory(Call.Factory { request ->
                checkSession()
                synchronized(this@AudioCache) {
                    checkActive()
                    if (cancelled) throw IOException("Audio download cancelled.")
                    upstream.newCall(request).also { calls.add(it) }
                }
            })
            val source: DataSource = entry?.let {
                CacheDataSource.Factory().setCache(it.cache)
                    .setUpstreamDataSourceFactory(remote)
                    .setCacheKeyFactory { spec ->
                        synchronized(this@AudioCache) {
                            keys.getOrPut(spec.uri) {
                                val key = digest(spec.uri.toString())
                                key + revisions.getString(key, "")
                            }
                        }
                    }
                    // Playback must not hold a hole lock while buffering is paused: that would
                    // prevent the background writer from finishing the current track.
                    .setCacheWriteDataSinkFactory(if (blocking) CacheDataSink.Factory().setCache(it.cache)
                        .setFragmentSize(fragmentBytes.coerceAtMost(maxBytes)) else null)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
                        if (blocking) CacheDataSource.FLAG_BLOCK_ON_CACHE else 0)
                    .createDataSource()
            } ?: remote.createDataSource()

            fun cancel() {
                cancelled = true
                calls.forEach { it.cancel() }
                fillingThread?.interrupt()
            }

            fun fill(writer: CacheWriter) {
                checkSession()
                synchronized(this@AudioCache) {
                    if (cancelled) return
                    fillingThread = Thread.currentThread()
                }
                try {
                    writer.cache()
                } finally {
                    synchronized(this@AudioCache) {
                        fillingThread = null
                        Thread.interrupted()
                    }
                }
            }

            override fun close() {
                try {
                    source.close()
                } finally {
                    synchronized(this@AudioCache) {
                        calls.clear()
                        readers.remove(this)
                        releaseIfIdle()
                    }
                }
            }
        }
    }

    private fun Account.owner() = digest("${origin.length}:$origin${user.id}")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
