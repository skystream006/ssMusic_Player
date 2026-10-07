package com.ssytdlp.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ssytdlp.app.core.Track
import kotlin.math.roundToInt

@Composable
internal fun QueueContent(state: PlaybackState, library: LibraryState, modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit,
    onRating: (Track) -> Unit = {}, artwork: @Composable (Track) -> ImageBitmap? = { null },
    trackActions: @Composable (Track, Int) -> Unit = { _, _ -> }) {
    val listState = rememberLazyListState()
    // Indices identify occurrences, not songs: a queue may contain the same song more than once.
    // A new queue snapshot cancels in-flight gestures rather than applying stale indices.
    key(state.queue) {
        val keys = remember(state.queue) { state.queue.indices.map(Int::toString) }
        val reorder = rememberSongReorderState(listState, keys, state.connected) { source, target, after ->
            val from = source.toInt()
            val targetIndex = target.toInt()
            val to = targetIndex + (if (after) 1 else 0) - (if (from < targetIndex) 1 else 0)
            onMove(from, to)
        }
        LazyColumn(modifier.testTag("queue-tracks"), state = listState) {
            items(reorder.keys, key = { it }) { key ->
                val index = key.toInt()
                val track = state.queue[index]
                Box(Modifier.songReorderItem(reorder, key).testTag("queue-row-$index")) {
                    QueueSwipeToRemove(state.connected, { onRemove(index) }) {
                        TrackRow(track.copy(rating = library.rating(track)), active = index == state.index,
                            enabled = state.connected, transcription = library.transcription(track),
                            artwork = { artwork(track.copy(artworkUrl = library.artworkUrl(track))) },
                            onRatingClick = { onRating(track) }, onClick = { onSelect(index) }) {
                            SongDragHandle(reorder, key, track.displayTitle, enabled = state.connected)
                            trackActions(track, index)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSwipeToRemove(enabled: Boolean, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val remove by rememberUpdatedState(onRemove)
    var distance by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    val offset by animateFloatAsState(distance, if (distance == 0f) spring() else snap(), label = "Queue swipe")
    Box(Modifier.fillMaxWidth().clipToBounds().then(if (!enabled) Modifier else Modifier.pointerInput(threshold) {
        try {
            detectHorizontalDragGestures(
                onDragStart = { distance = 0f },
                onDragCancel = { distance = 0f },
                onDragEnd = {
                    val dismiss = distance <= -threshold
                    distance = 0f
                    if (dismiss) remove()
                },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    distance = (distance + amount).coerceIn(-size.width.toFloat(), 0f)
                })
        } finally {
            distance = 0f
        }
    })) {
        if (offset < 0f) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.errorContainer)) {
            Icon(Icons.Rounded.DeleteOutline, null,
                Modifier.align(Alignment.CenterRight).padding(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer)
        }
        Box(Modifier.absoluteOffset { IntOffset(offset.roundToInt(), 0) }
            .background(MaterialTheme.colorScheme.surface)) { content() }
    }
}
