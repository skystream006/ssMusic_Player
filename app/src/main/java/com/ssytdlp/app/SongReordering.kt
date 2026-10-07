package com.ssytdlp.app

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun rememberSongReorderState(
    listState: LazyListState,
    keys: List<String>,
    enabled: Boolean = true,
    canMove: (String, String) -> Boolean = { _, _ -> true },
    onMove: (String, String, Boolean) -> Unit
): SongReorderState {
    val currentKeys = rememberUpdatedState(keys)
    val currentEnabled = rememberUpdatedState(enabled)
    val currentCanMove = rememberUpdatedState(canMove)
    val currentOnMove = rememberUpdatedState(onMove)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state = remember(listState, density) {
        SongReorderState(listState, currentKeys, currentEnabled, currentCanMove, currentOnMove,
            scope, with(density) { 56.dp.toPx() }, with(density) { 700.dp.toPx() })
    }
    SideEffect { state.validateDrag() }
    DisposableEffect(state) {
        onDispose { state.cancelDrag() }
    }
    return state
}

internal class SongReorderState internal constructor(
    private val listState: LazyListState,
    private val sourceKeys: State<List<String>>,
    private val enabled: State<Boolean>,
    private val canMove: State<(String, String) -> Boolean>,
    private val onMove: State<(String, String, Boolean) -> Unit>,
    private val scope: CoroutineScope,
    private val edgeSize: Float,
    private val scrollSpeed: Float
) {
    private data class Drag(
        val key: String,
        val original: List<String>,
        val preview: List<String>,
        val top: Float,
        val size: Int
    )

    private var drag by mutableStateOf<Drag?>(null)
    private var scrollJob: Job? = null

    val keys: List<String>
        get() = drag?.takeIf { enabled.value && it.original == sourceKeys.value }?.preview ?: sourceKeys.value
    internal fun isDragging(key: String): Boolean = drag?.key == key
    internal fun isEnabled(key: String): Boolean =
        enabled.value && sourceKeys.value.size > 1 && key in sourceKeys.value

    internal fun translation(key: String): Float {
        val active = drag?.takeIf { it.key == key } ?: return 0f
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        return active.top - item.offset
    }

    internal fun startDrag(key: String) {
        if (!isEnabled(key)) return
        cancelDrag()
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val original = sourceKeys.value.toList()
        drag = Drag(key, original, original, item.offset.toFloat(), item.size)
    }

    internal fun dragBy(key: String, distance: Float) {
        validateDrag()
        val active = drag?.takeIf { it.key == key } ?: return
        drag = active.copy(top = active.top + distance)
        movePastNeighbours()
        startAutoScroll()
    }

    internal fun validateDrag() {
        val active = drag ?: return
        if (!enabled.value || sourceKeys.value != active.original || !allowedPath(active)) cancelDrag()
    }

    private fun allowedPath(active: Drag): Boolean {
        val from = active.original.indexOf(active.key)
        val to = active.preview.indexOf(active.key)
        return (minOf(from, to)..maxOf(from, to)).all {
            val target = active.original[it]
            target == active.key || canMove.value(active.key, target)
        }
    }

    private fun preserveViewport() {
        // Do not let LazyColumn anchor the viewport to the key that is being moved.
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }

    private fun movePastNeighbours() {
        validateDrag()
        val active = drag ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val indices = visible.mapNotNull { item -> active.preview.indexOf(item.key).takeIf { it >= 0 } }
        // Wait for the preceding preview move to be measured before using its item offsets again.
        if (indices.zipWithNext().any { (first, second) -> first >= second }) return
        val from = active.preview.indexOf(active.key)
        val center = active.top + active.size / 2f
        var to = from
        for (direction in listOf(-1, 1)) {
            var candidate = from + direction
            while (candidate in active.preview.indices) {
                val target = active.preview[candidate]
                if (!canMove.value(active.key, target)) break
                val item = visible.firstOrNull { it.key == target } ?: break
                val crossed = if (direction < 0) center < item.offset + item.size / 2f
                    else center > item.offset + item.size / 2f
                if (!crossed) break
                to = candidate
                candidate += direction
            }
            if (to != from) break
        }
        if (to == from) return
        val preview = active.preview.toMutableList().apply { add(to, removeAt(from)) }
        preserveViewport()
        drag = active.copy(preview = preview)
    }

    private fun edgeScrollSpeed(): Float {
        val active = drag ?: return 0f
        val layout = listState.layoutInfo
        val center = active.top + active.size / 2f
        val edge = minOf(edgeSize, (layout.viewportEndOffset - layout.viewportStartOffset) / 3f)
        if (edge <= 0f) return 0f
        val speed = when {
            center < layout.viewportStartOffset + edge && listState.canScrollBackward ->
                -scrollSpeed * ((layout.viewportStartOffset + edge - center) / edge).coerceIn(0f, 1f)
            center > layout.viewportEndOffset - edge && listState.canScrollForward ->
                scrollSpeed * ((center - layout.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        if (speed == 0f) return 0f
        val next = active.preview.getOrNull(active.preview.indexOf(active.key) + if (speed < 0f) -1 else 1)
        return if (next != null && canMove.value(active.key, next)) speed else 0f
    }

    private fun startAutoScroll() {
        if (scrollJob?.isActive == true || edgeScrollSpeed() == 0f) return
        scrollJob = scope.launch {
            var previous = withFrameNanos { it }
            while (isActive && drag != null) {
                val now = withFrameNanos { it }
                validateDrag()
                val speed = edgeScrollSpeed()
                if (speed == 0f) break
                val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(0.032f)
                previous = now
                if (listState.scrollBy(speed * seconds) == 0f) break
                movePastNeighbours()
            }
        }
    }

    internal fun finishDrag(key: String) {
        validateDrag()
        val active = drag?.takeIf { it.key == key } ?: return
        val from = active.original.indexOf(key)
        val to = active.preview.indexOf(key)
        cancelDrag()
        if (from != to) onMove.value(key, active.original[to], to > from)
    }

    internal fun cancelDrag(key: String? = null) {
        if (key != null && drag?.key != key) return
        scrollJob?.cancel()
        scrollJob = null
        if (drag?.let { it.original != it.preview } == true) preserveViewport()
        drag = null
    }

    internal fun accessibilityTarget(key: String, direction: Int): String? {
        if (!isEnabled(key) || drag != null) return null
        val target = sourceKeys.value.getOrNull(sourceKeys.value.indexOf(key) + direction) ?: return null
        return target.takeIf { canMove.value(key, it) }
    }

    internal fun moveAccessible(key: String, direction: Int): Boolean {
        val target = accessibilityTarget(key, direction) ?: return false
        preserveViewport()
        onMove.value(key, target, direction > 0)
        return true
    }
}

internal fun Modifier.songReorderItem(state: SongReorderState, key: String): Modifier =
    zIndex(if (state.isDragging(key)) 1f else 0f)
        .graphicsLayer { translationY = state.translation(key) }

@Composable
internal fun SongDragHandle(
    state: SongReorderState,
    key: String,
    title: String,
    enabled: Boolean = true
) {
    val available = enabled && state.isEnabled(key)
    val container = LocalPinnableContainer.current
    val dragging = state.isDragging(key)
    DisposableEffect(container, dragging) {
        val pinned = if (dragging) container?.pin() else null
        onDispose { pinned?.release() }
    }
    Box(
        Modifier.size(48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Drag to reorder $title"
                if (!available) disabled()
                customActions = if (!available) emptyList() else buildList {
                    if (state.accessibilityTarget(key, -1) != null) {
                        add(CustomAccessibilityAction("Move up") { state.moveAccessible(key, -1) })
                    }
                    if (state.accessibilityTarget(key, 1) != null) {
                        add(CustomAccessibilityAction("Move down") { state.moveAccessible(key, 1) })
                    }
                }
            }
            .pointerInput(state, key, available) {
                if (!available) return@pointerInput
                try {
                    detectVerticalDragGestures(
                        onDragStart = { state.startDrag(key) },
                        onDragEnd = { state.finishDrag(key) },
                        onDragCancel = { state.cancelDrag(key) },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            state.dragBy(key, amount)
                        }
                    )
                } finally {
                    state.cancelDrag(key)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.DragHandle, contentDescription = null,
            tint = LocalContentColor.current.copy(alpha = if (available) 1f else 0.38f))
    }
}
