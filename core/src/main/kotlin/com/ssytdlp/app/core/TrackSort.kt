package com.ssytdlp.app.core

enum class TrackSort {
    ORDER, NAME, RATING;

    fun indices(tracks: List<Track>, rating: (Track) -> Int = { it.rating }): List<Int> = when (this) {
        ORDER -> tracks.indices.toList()
        NAME -> tracks.indices.sortedBy { tracks[it].displayTitle.lowercase() }
        RATING -> tracks.indices.sortedByDescending { rating(tracks[it]) }
    }
}
