package com.ssytdlp.app

import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.SongMetadata
import com.ssytdlp.app.core.Track

internal data class MetadataIdentity(val key: String, val streamUrl: String?, val artworkUrl: String?) {
    constructor(track: Track) : this(track.key, track.streamUrl, track.artworkUrl)
}

internal data class MetadataOwner(val origin: String, val userId: String, val session: Session) {
    constructor(account: Account) : this(account.origin, account.user.id, account.session)
}

/** Retains typed metadata, not a second copy of its JSON or decoded artwork. */
internal class SongMetadataCache(
    private val maxEntries: Int = 16,
    private val maxBytes: Long = 8L * 1024 * 1024,
    private val ttlNanos: Long = 5L * 60 * 1_000_000_000,
    private val now: () -> Long = System::nanoTime
) {
    private data class Entry(val metadata: SongMetadata, val bytes: Long, val created: Long)
    private val entries = LinkedHashMap<MetadataIdentity, Entry>(16, .75f, true)
    private var owner: MetadataOwner? = null
    internal var retainedBytes = 0L
        private set
    internal val size get() = entries.size

    fun setOwner(account: MetadataOwner?) {
        if (owner == account) return
        owner = account
        entries.clear()
        retainedBytes = 0
    }

    fun get(account: MetadataOwner, identity: MetadataIdentity): SongMetadata? {
        if (account != owner) return null
        val entry = entries[identity] ?: return null
        if (now() - entry.created >= ttlNanos) {
            remove(identity)
            return null
        }
        return entry.metadata
    }

    fun put(account: MetadataOwner, identity: MetadataIdentity, metadata: SongMetadata) {
        if (account != owner) return
        remove(identity)
        val bytes = retainedSize(identity, metadata)
        if (maxEntries <= 0 || bytes > maxBytes || ttlNanos <= 0) return
        entries[identity] = Entry(metadata, bytes, now())
        retainedBytes += bytes
        while (entries.size > maxEntries || retainedBytes > maxBytes) remove(entries.keys.first())
    }

    fun invalidate(key: String) {
        entries.keys.filter { it.key == key }.forEach(::remove)
    }

    private fun remove(identity: MetadataIdentity) {
        entries.remove(identity)?.let { retainedBytes -= it.bytes }
    }

    private fun retainedSize(identity: MetadataIdentity, value: SongMetadata): Long {
        fun String?.bytes() = 40L + (this?.length ?: 0).toLong() * 2
        return 256L + identity.key.bytes() + identity.streamUrl.bytes() + identity.artworkUrl.bytes() +
            value.title.bytes() + value.artist.bytes() + value.album.bytes() + value.genre.bytes() +
            value.year.bytes() + value.artwork.bytes() + value.uslt.bytes() +
            value.sylt.sumOf { 48L + it.text.bytes() }
    }
}
