package com.ssytdlp.app

import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.LyricLine
import com.ssytdlp.app.core.Session
import com.ssytdlp.app.core.SongMetadata
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.User
import org.junit.Assert.*
import org.junit.Test

class SongMetadataCacheTest {
    private val account = Account("https://music.example", User("listener"), Session("test-session", "2100-01-01T00:00:00Z"))
    private val owner = MetadataOwner(account)
    private val first = MetadataIdentity(Track("job", "one.mp3", streamUrl = "/api/stream/one?v=1", artworkUrl = "/api/art/one?v=1"))
    private val second = first.copy(key = "second")
    private val third = first.copy(key = "third")
    private val song = SongMetadata(uslt = "Plain lyrics", sylt = listOf(LyricLine(1.5, "Timed lyrics")), canEdit = true)

    @Test fun revisitRetainsLyricsAndAuthorityUntilAbsoluteFreshnessExpires() {
        var clock = 0L
        val cache = SongMetadataCache(ttlNanos = 10, now = { clock })
        cache.setOwner(owner)
        cache.put(owner, first, song)
        clock = 9
        assertSame(song, cache.get(owner, first))
        clock = 10
        assertNull(cache.get(owner, first))
        assertEquals(0L, cache.retainedBytes)
    }

    @Test fun entryAndByteBoundsEvictLeastRecentlyUsedAndSkipOversizedArtwork() {
        val cache = SongMetadataCache(maxEntries = 2, maxBytes = 4_000)
        cache.setOwner(owner)
        cache.put(owner, first, song)
        cache.put(owner, second, song)
        assertSame(song, cache.get(owner, first))
        cache.put(owner, third, song)
        assertNull(cache.get(owner, second))
        assertEquals(2, cache.size)
        cache.put(owner, first, song.copy(artwork = "x".repeat(4_000)))
        assertNull(cache.get(owner, first))
        assertTrue(cache.retainedBytes <= 4_000)
        val oneEntryBytes = cache.retainedBytes
        val small = SongMetadataCache(maxBytes = oneEntryBytes + 100)
        small.setOwner(owner)
        small.put(owner, first, song)
        small.put(owner, third, song)
        assertEquals(1, small.size)
        assertNull(small.get(owner, first))
    }

    @Test fun revisionsAreDistinctAndInvalidationRemovesEveryRevision() {
        val cache = SongMetadataCache()
        cache.setOwner(owner)
        cache.put(owner, first, song)
        assertNull(cache.get(owner, first.copy(streamUrl = "/api/stream/one?v=2")))
        val artworkRevision = first.copy(artworkUrl = "/api/art/one?v=2")
        assertNull(cache.get(owner, artworkRevision))
        cache.put(owner, artworkRevision, song.copy(artwork = "new"))
        cache.invalidate(first.key)
        assertEquals(0, cache.size)
        assertEquals(0L, cache.retainedBytes)
    }

    @Test fun logoutOriginAndSessionChangesClearAllRetainedMetadata() {
        val cache = SongMetadataCache()
        listOf(null, owner.copy(origin = "https://other.example"),
            owner.copy(userId = "other"), owner.copy(session = owner.session.copy(token = "new-session"))).forEach { next ->
            cache.setOwner(owner)
            cache.put(owner, first, song)
            cache.setOwner(next)
            assertNull(cache.get(owner, first))
            cache.put(owner, first, song)
            assertEquals(0, cache.size)
            assertEquals(0L, cache.retainedBytes)
            cache.setOwner(owner)
            assertNull(cache.get(owner, first))
        }
    }

    @Test fun refreshingTheProfileDoesNotChangeSessionOwnershipOrDropMetadata() {
        val cache = SongMetadataCache()
        cache.setOwner(owner)
        cache.put(owner, first, song)
        val refreshed = MetadataOwner(account.copy(user = account.user.copy(name = "Updated", role = "shared")))
        cache.setOwner(refreshed)
        assertEquals(owner, refreshed)
        assertSame(song, cache.get(refreshed, first))
    }
}
