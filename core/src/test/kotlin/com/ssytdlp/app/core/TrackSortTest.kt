package com.ssytdlp.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSortTest {
    @Test fun `name uses case insensitive titles with filename fallback and stable ties`() {
        val tracks = listOf(
            Track(name = "z.mp3", title = "beta"),
            Track(name = "folder/Alpha.mp3"),
            Track(name = "a.mp3", title = "BETA"),
            Track(name = "ten.mp3", title = "Song 10"),
            Track(name = "two.mp3", title = "Song 2"))
        assertEquals(listOf(1, 0, 2, 3, 4), TrackSort.NAME.indices(tracks))
        assertEquals(listOf(0, 1, 2, 3, 4), TrackSort.ORDER.indices(tracks))
    }

    @Test fun `rating sorts highest first with unrated last and preserves tie order`() {
        val tracks = listOf(Track(name = "z.mp3", rating = 3), Track(name = "unrated.mp3"),
            Track(name = "a.mp3", rating = 3), Track(name = "best.mp3", rating = 5))
        assertEquals(listOf(3, 0, 2, 1), TrackSort.RATING.indices(tracks))
        assertEquals(listOf(1, 3, 0, 2), TrackSort.RATING.indices(tracks) {
            if (it.name == "unrated.mp3") 5 else it.rating
        })
    }

    @Test fun `sorts preserve every duplicate occurrence and handle empty and singleton lists`() {
        val song = Track(name = "song.mp3")
        for (sort in TrackSort.entries) {
            assertEquals(emptyList<Int>(), sort.indices(emptyList()))
            assertEquals(listOf(0), sort.indices(listOf(song)))
            assertEquals(listOf(0, 1, 2), sort.indices(listOf(song, song, song)))
        }
    }
}
