@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ssytdlp.app

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.ssytdlp.app.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.math.roundToInt
import java.util.Locale

@Composable
fun PlayerDock(state: PlaybackState, model: MusicViewModel, expand: () -> Unit, requestNotifications: () -> Unit) {
    MiniPlayer(state, model.metadata?.artwork, expand, { requestNotifications(); model.playback.toggle() },
        model.playback::next, model.playback::seek, model.playback::previous, model.playback::repeat)
}

@Composable
fun MiniPlayer(state: PlaybackState, artwork: String?, expand: () -> Unit, toggle: () -> Unit,
    next: () -> Unit, onSeek: (Long) -> Unit, previous: () -> Unit = {}, repeat: () -> Unit = {}) {
    val track = state.track ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 440.dp
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    key(track.key) {
                        MiniPlayerTrack(track, artwork, expand, Modifier.weight(1f), compact)
                    }
                    MiniPlayerControls(state, previous, toggle, next, repeat)
                }
                key(track.key) {
                    var seeking by remember(state.duration) { mutableStateOf<Float?>(null) }
                    Slider(value = seeking ?: state.position.toFloat().coerceIn(0f, state.duration.toFloat().coerceAtLeast(1f)),
                        onValueChange = { seeking = it },
                        onValueChangeFinished = { seeking?.let { onSeek(it.toLong()) }; seeking = null },
                        valueRange = 0f..state.duration.toFloat().coerceAtLeast(1f), enabled = state.duration > 0,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics { contentDescription = "Playback position" })
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerTrack(track: Track, artwork: String?, expand: () -> Unit, modifier: Modifier, compact: Boolean) {
    Row(modifier.heightIn(min = 76.dp).padding(start = if (compact) 8.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AlbumArtwork(artwork, Modifier.size(if (compact) 40.dp else 48.dp).clickable(onClick = expand))
        Column(Modifier.weight(1f).clickable(onClick = expand)
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 10.dp)) {
            Text(track.displayTitle, modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = MaterialTheme.typography.titleSmall)
            Text(track.displayArtist, modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MiniPlayerControls(state: PlaybackState, previous: () -> Unit, toggle: () -> Unit,
    next: () -> Unit, repeat: () -> Unit) {
    ToolButton(Icons.Rounded.SkipPrevious, "Previous track", modifier = Modifier.size(48.dp), onClick = previous)
    FilledIconButton(onClick = toggle, modifier = Modifier.size(48.dp), shape = CircleShape) {
        Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (state.playing) "Pause" else "Play", Modifier.size(22.dp))
    }
    ToolButton(Icons.Rounded.SkipNext, "Next track", enabled = state.queue.size > 1, modifier = Modifier.size(48.dp),
        onClick = next)
    RepeatButton(state.repeat, repeat)
}

@Composable
private fun RepeatButton(mode: Int, repeat: () -> Unit) {
    IconToggleButton(checked = mode != Player.REPEAT_MODE_OFF, onCheckedChange = { repeat() }, modifier = Modifier.size(48.dp)) {
        Icon(if (mode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            "Repeat: ${if (mode == Player.REPEAT_MODE_OFF) "off" else if (mode == Player.REPEAT_MODE_ONE) "one" else "all"}", Modifier.size(20.dp))
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun NowPlayingScreen(model: MusicViewModel, state: PlaybackState, download: (String, String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var ratingTrack by remember { mutableStateOf<Track?>(null) }
    var editingTrack by remember { mutableStateOf<Track?>(null) }
    var replacingTrack by remember { mutableStateOf<Track?>(null) }
    val account by model.sessions.account.collectAsStateWithLifecycle()
    var preferUslt by rememberSaveable(state.track?.key) { mutableStateOf(false) }
    var artworkDrag by remember(state.track?.key, tab) { mutableFloatStateOf(0f) }
    val artworkOffset by key(state.track?.key, tab) {
        animateFloatAsState(artworkDrag, animationSpec = if (artworkDrag == 0f) spring() else snap(),
            label = "Artwork swipe")
    }
    Column(Modifier.fillMaxSize().playerTrackSwipes(
        enabled = tab == 0 && state.track != null, nextEnabled = state.queue.size > 1,
        previous = model.playback::previousTrack, next = model.playback::next,
        trackKey = state.track?.key, onDragDistanceChanged = { artworkDrag = it }
    )) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(state.track?.playlistTitle?.ifBlank { null } ?: "Your queue", style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            state.track?.let { track ->
                val canEdit = track.name.endsWith(".mp3", true) && account?.user?.let { user ->
                    !user.isShared && model.library.library.jobs.find { it.id == track.jobId }?.canModify(user) == true
                } == true
                if (canEdit) ToolButton(Icons.Rounded.Edit, "Edit metadata", enabled = !model.busy) { editingTrack = track }
                if (model.canReplaceFile(track)) ToolButton(Icons.Rounded.UploadFile, "Replace File", enabled = !model.busy) { replacingTrack = track }
                ToolButton(Icons.Rounded.Download, "Save file", enabled = !model.busy) {
                    download(track.downloadUrl ?: songPath(track, "download"), track.name)
                }
            }
        }
        if (state.track == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Choose a song from Library to start playing.", Modifier.padding(24.dp),
                    textAlign = TextAlign.Center)
            }
        } else {
            val hasSylt = model.metadata?.sylt?.isNotEmpty() == true
            val hasUslt = !model.metadata?.uslt.isNullOrBlank()
            val showUslt = hasUslt && (preferUslt || !hasSylt)
            val lyricsTabTitle = when {
                showUslt -> "USLT Lyrics"
                hasSylt -> "SYLT Lyrics"
                else -> "Lyrics"
            }
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                listOf("Player", lyricsTabTitle, "Queue").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = {
                        if (index == 1 && tab == 1 && hasSylt && hasUslt) preferUslt = !preferUslt
                        tab = index
                    }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> PlayerArtwork(state, model.metadata, Modifier.weight(1f), model.playback.controller,
                    artworkOffset = { artworkOffset }, transcription = model.library.transcription(state.track)) {
                    model.playback.play(listOfNotNull(state.track?.noVocalsVersion))
                }
                1 -> Lyrics(model, state, Modifier.weight(1f), showUslt)
                else -> LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(state.queue, key = { index, item -> "${item.key}:$index" }) { index, queued ->
                        TrackRow(queued.copy(rating = model.library.rating(queued)), active = index == state.index,
                            transcription = model.library.transcription(queued),
                            onRatingClick = { ratingTrack = queued },
                            onClick = { model.playback.select(index) }) {
                            ToolButton(Icons.Rounded.Close, "Remove from queue") { model.playback.remove(index) }
                        }
                    }
                }
            }
            PlayerTransport(state, model.playback::seek, model.playback::previous, model.playback::toggle,
                model.playback::next, model.playback::shuffle, model.playback::repeat)
        }
    }
    ratingTrack?.let { track -> MetadataDialog(model, track, ratingOnly = true) { ratingTrack = null } }
    editingTrack?.let { track -> MetadataDialog(model, track) { editingTrack = null } }
    replacingTrack?.let { track -> ReplaceFileDialog(model, track) { replacingTrack = null } }
}

@Composable
internal fun Modifier.playerTrackSwipes(enabled: Boolean, nextEnabled: Boolean,
    previous: () -> Unit, next: () -> Unit, trackKey: String? = null,
    onDragDistanceChanged: (Float) -> Unit = {}): Modifier {
    val currentPrevious by rememberUpdatedState(previous)
    val currentNext by rememberUpdatedState(next)
    val currentDragDistanceChanged by rememberUpdatedState(onDragDistanceChanged)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    return if (!enabled) this else pointerInput(nextEnabled, threshold, trackKey) {
        var distance = 0f
        try {
            detectHorizontalDragGestures(
                onDragStart = { distance = 0f; currentDragDistanceChanged(0f) },
                onDragCancel = { distance = 0f; currentDragDistanceChanged(0f) },
                onDragEnd = {
                    if (distance <= -threshold && nextEnabled) currentNext()
                    else if (distance >= threshold) currentPrevious()
                    distance = 0f
                    currentDragDistanceChanged(0f)
                },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    distance += amount
                    currentDragDistanceChanged(distance)
                }
            )
        } finally {
            currentDragDistanceChanged(0f)
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerArtwork(state: PlaybackState, metadata: SongMetadata?, modifier: Modifier = Modifier,
    controller: MediaController? = null, artworkOffset: () -> Float = { 0f },
    transcription: Transcription? = null, onInstrumental: () -> Unit = {}) {
    val track = state.track
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (track?.mediaType == "video") AndroidView(factory = { context -> PlayerView(context).apply { useController = false; player = controller } },
            update = { it.player = controller }, onRelease = { it.player = null }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        else AlbumArtwork(metadata?.artwork, Modifier.absoluteOffset { IntOffset(artworkOffset().roundToInt(), 0) }
            .widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(28.dp))
        Text(metadata?.title?.ifBlank { null } ?: track?.displayTitle.orEmpty(), style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(metadata?.artist?.ifBlank { null } ?: track?.displayArtist.orEmpty(), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        TranscriptionStatus(transcription)
        if (!metadata?.album.isNullOrBlank()) Text(metadata?.album.orEmpty(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        if (state.buffering) CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        if (track?.noVocalsVersion != null) TextButton(onClick = onInstrumental) {
            Icon(Icons.Rounded.MicOff, null); Spacer(Modifier.width(8.dp)); Text("Instrumental version")
        }
    }
}

@Composable
fun PlayerTransport(state: PlaybackState, onSeek: (Long) -> Unit, previous: () -> Unit, toggle: () -> Unit,
    next: () -> Unit, shuffle: () -> Unit, repeat: () -> Unit) {
    var seeking by remember(state.track?.key) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
        Slider(value = seeking ?: state.position.toFloat().coerceIn(0f, state.duration.toFloat().coerceAtLeast(1f)),
            onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { onSeek(it.toLong()) }; seeking = null },
            valueRange = 0f..state.duration.toFloat().coerceAtLeast(1f), enabled = state.duration > 0,
            modifier = Modifier.semantics { contentDescription = "Playback position" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(timestamp(seeking?.toLong() ?: state.position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(timestamp(state.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = state.shuffle, onCheckedChange = { shuffle() }) { Icon(Icons.Rounded.Shuffle, "Shuffle", Modifier.size(20.dp)) }
            ToolButton(Icons.Rounded.SkipPrevious, "Previous track", onClick = previous)
            FilledIconButton(onClick = toggle, modifier = Modifier.size(64.dp), shape = CircleShape) {
                Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(32.dp))
            }
            ToolButton(Icons.Rounded.SkipNext, "Next track", enabled = state.queue.size > 1, onClick = next)
            RepeatButton(state.repeat, repeat)
        }
    }
}

@Composable
fun Lyrics(model: MusicViewModel, state: PlaybackState, modifier: Modifier, preferUslt: Boolean = false) {
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val track = state.track
    val metadata = model.metadata
    var editing by remember(account?.origin, account?.user?.id, account?.session) {
        mutableStateOf<Triple<Track, SongMetadata, Boolean>?>(null)
    }
    val canEdit = track?.name?.endsWith(".mp3", true) == true && metadata?.canEdit == true &&
        account?.user?.let { !it.isShared } == true
    Column(modifier) {
        if (canEdit) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                if (track != null && metadata != null) editing = Triple(track, metadata, preferUslt)
            }, enabled = !model.busy) {
                Icon(Icons.Rounded.Edit, null)
                Spacer(Modifier.width(8.dp))
                Text("Edit lyrics")
            }
        }
        LyricsContent(metadata, state.position, track?.key, model.playback::seek, Modifier.weight(1f),
            model.metadataError, track?.mediaType == "video", preferUslt, model.lyricsTextScale, model::zoomLyrics)
    }
    editing?.takeIf { account?.user?.isShared == false }?.let { (editTrack, editMetadata, editUslt) ->
        key(editTrack.key) {
            LyricsEditorDialog(editMetadata, editUslt, model.busy, dismiss = { editing = null }) { changes ->
                model.saveLyrics(editTrack, changes) { editing = null }
            }
        }
    }
}

@Composable
internal fun LyricsContent(lyrics: SongMetadata?, position: Long, trackKey: String?, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier, error: String? = null, isVideo: Boolean = false, preferUslt: Boolean = false,
    textScale: Float = 1f, onZoom: (Float) -> Unit = {}) {
    val scale = normalizeLyricsTextScale(textScale)
    key(trackKey, lyrics?.sylt, lyrics?.uslt, preferUslt) {
        var pinching by remember { mutableStateOf(false) }
        val zoomModifier = if (lyrics?.sylt?.isNotEmpty() == true || !lyrics?.uslt.isNullOrBlank())
            modifier.lyricsPinchZoom(onZoom) { pinching = it } else modifier
        if (lyrics?.sylt?.isNotEmpty() == true && !(preferUslt && !lyrics.uslt.isNullOrBlank())) {
            val active = lyrics.sylt.indexOfLast { it.time * 1000 <= position }
            val listState = rememberLazyListState()
            val dragging by listState.interactionSource.collectIsDraggedAsState()
            var following by remember { mutableStateOf(true) }
            var manualScroll by remember { mutableStateOf(false) }
            var tap by remember { mutableIntStateOf(0) }
            var seekTarget by remember { mutableStateOf<Int?>(null) }
            val target = seekTarget ?: active
            val currentActive by rememberUpdatedState(target)
            val linePadding = with(LocalDensity.current) { 12.dp.toPx() }
            val scrollConnection = remember(listState, linePadding) {
                object : NestedScrollConnection {
                    private var highlightedWasVisible = false

                    private fun highlightVisible(): Boolean {
                        val layout = listState.layoutInfo
                        val line = layout.visibleItemsInfo.find { it.index == currentActive } ?: return false
                        return line.offset + line.size - linePadding > layout.viewportStartOffset &&
                            line.offset + linePadding < layout.viewportEndOffset
                    }

                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source == NestedScrollSource.UserInput && available.y != 0f) manualScroll = true
                        highlightedWasVisible = highlightVisible()
                        return Offset.Zero
                    }

                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        // Keep tracking the user's fling, but never treat follow animations as manual scrolling.
                        if (manualScroll && consumed.y != 0f && highlightedWasVisible && !highlightVisible()) following = false
                        return Offset.Zero
                    }
                }
            }
            LaunchedEffect(manualScroll, dragging) {
                if (manualScroll) snapshotFlow { listState.isScrollInProgress || dragging }.collectLatest { busy ->
                    if (!busy) {
                        // A drag can hand off to a fling between idle notifications. Wheel input has no fling.
                        withFrameNanos { }
                        if (!listState.isScrollInProgress && !dragging) manualScroll = false
                    }
                }
            }
            LaunchedEffect(tap, active == seekTarget) {
                if (seekTarget != null) {
                    // Playback position is polled asynchronously; do not follow the old line after a tap.
                    if (active != seekTarget) delay(1_500)
                    seekTarget = null
                }
            }
            LaunchedEffect(target, following, manualScroll, dragging, tap, pinching) {
                if (following && !manualScroll && !dragging && !pinching && target >= 0)
                    listState.animateScrollToItem(target)
            }
            LazyColumn(zoomModifier.fillMaxWidth().nestedScroll(scrollConnection), state = listState,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp)) {
                itemsIndexed(lyrics.sylt) { index, line ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(formatLyricTimestamp(line.time), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(line.text, modifier = Modifier.weight(1f).clickable {
                            onSeek((line.time * 1000).toLong())
                            seekTarget = index.takeUnless { it == active }
                            manualScroll = listState.isScrollInProgress || dragging
                            following = true
                            tap++
                        },
                            style = (if (index == active) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                                .scaledLyrics(scale),
                            color = if (index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else Column(zoomModifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp)) {
            Text(lyrics?.uslt?.ifBlank { null } ?: error ?: if (lyrics == null && !isVideo) "Loading lyrics..." else "No lyrics available",
                style = MaterialTheme.typography.bodyLarge.scaledLyrics(if (lyrics?.uslt.isNullOrBlank()) 1f else scale))
        }
    }
}

@Composable
private fun Modifier.lyricsPinchZoom(onZoom: (Float) -> Unit, onPinchingChanged: (Boolean) -> Unit): Modifier {
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnPinchingChanged by rememberUpdatedState(onPinchingChanged)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pinching = false
            try {
                do {
                    // Claim multi-touch before lyric clicks or vertical scrolling can consume it.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } >= 2) {
                        if (!pinching) {
                            pinching = true
                            currentOnPinchingChanged(true)
                        }
                        val zoomChange = event.calculateZoom()
                        if (zoomChange.isFinite() && zoomChange > 0f && zoomChange != 1f) currentOnZoom(zoomChange)
                    }
                    // Keep consuming until all fingers lift, so a pinch cannot finish as a tap or drag.
                    if (pinching) event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            } finally {
                currentOnPinchingChanged(false)
            }
        }
    }
}

private fun TextStyle.scaledLyrics(scale: Float): TextStyle =
    copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)

internal fun formatLyricTimestamp(time: Double): String {
    val totalSeconds = time.toLong().coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val totalMinutes = totalSeconds / 60
    return String.format(Locale.ROOT, "%02d:%02d:%02d",
        totalMinutes / 60, totalMinutes % 60, seconds)
}

@Composable
fun AlbumArtwork(artwork: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, artwork) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                if (artwork == null || artwork.length > 2_800_000 || !artwork.startsWith("data:image/")) return@runCatching null
                val bytes = Base64.decode(artwork.substringAfter(","), Base64.DEFAULT)
                val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (dimensions.outWidth / inSampleSize > 1024 || dimensions.outHeight / inSampleSize > 1024) inSampleSize *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, "Album artwork", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else {
            if (LocalWaveAppearance.current) Image(painterResource(R.drawable.wave_cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Icon(Icons.Rounded.Album, "Album artwork unavailable", Modifier.fillMaxSize(0.32f), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
        }
    }
}

@Composable
fun TrackMenu(model: MusicViewModel, track: Track, index: Int, download: (String, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val user = account?.user
    val canTransfer = user?.isShared == false && track.playlistId != null
    var transfer by remember(canTransfer) { mutableStateOf<String?>(null) }
    val canModify = user != null && !user.isShared &&
        model.library.library.jobs.find { it.id == track.jobId }?.canModify(user) == true
    var transcribe by remember(canModify, track.key) { mutableStateOf(false) }
    var replace by remember(canModify, track.key) { mutableStateOf(false) }
    Box {
        ToolButton(Icons.Rounded.MoreVert, "Options for ${track.displayTitle}", enabled = !model.busy) { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Add to queue") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = { open = false; model.playback.enqueue(track) })
            DropdownMenuItem(text = { Text("Save file") }, leadingIcon = { Icon(Icons.Rounded.Download, null) }, onClick = { open = false; download(track.downloadUrl ?: songPath(track, "download"), track.name) })
            if (canTransfer) {
                DropdownMenuItem(text = { Text("Add to playlist") }, leadingIcon = { Icon(Icons.Rounded.LibraryAdd, null) }, onClick = { open = false; transfer = "link" })
                DropdownMenuItem(text = { Text("Move to playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) }, onClick = { open = false; transfer = "move" })
            }
            val state = model.library
            if (state.selectedId == track.playlistId && state.tracks.totalPages == 1 && state.search.isEmpty()) {
                val (previous, next) = trackReorderNeighbors(state.tracks.files, index)
                if (previous != null) DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) }, onClick = { open = false; model.reorder(track, previous, false) })
                if (next != null) DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) }, onClick = { open = false; model.reorder(track, next, true) })
            }
            if (canModify && track.name.endsWith(".mp3", true)) {
                DropdownMenuItem(text = { Text("Edit song / rating") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { open = false; edit = true })
                TranscribeMenuItem(model.library.transcriptionLocked(track), model.transcriptionAvailable, model.busy) {
                    open = false; transcribe = true
                }
                if (model.canReplaceFile(track)) DropdownMenuItem(text = { Text("Replace File") },
                    leadingIcon = { Icon(Icons.Rounded.UploadFile, null) }, enabled = !model.busy,
                    onClick = { open = false; replace = true })
            }
            if (canModify && track.playlistId != null) DropdownMenuItem(text = { Text("Remove from playlist") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { open = false; remove = true })
        }
    }
    if (edit) MetadataDialog(model, track) { edit = false }
    if (transcribe) TranscribeDialog(model, track) { transcribe = false }
    if (replace) ReplaceFileDialog(model, track) { replace = false }
    if (remove) ConfirmDialog("Remove this song?", "This removes its playlist membership. If no other links remain, the server deletes the media file. This cannot be undone.", { remove = false }) { model.remove(track); remove = false }
    if (canTransfer && transfer != null) DestinationDialog(if (transfer == "link") "Add to playlist" else "Move to playlist",
        model.library.library.playlists.filter { it.id != track.playlistId }.map { it.id to it.playlistTitle }, { transfer = null }) {
        if (it != null) model.transfer(track, it, transfer == "link")
        transfer = null
    }
}

internal fun trackReorderNeighbors(files: List<Track>, index: Int): Pair<Track?, Track?> {
    val track = files.getOrNull(index) ?: return null to null
    val noVocals = track.name.startsWith("[NoVocals]/", ignoreCase = true)
    val sameGroup: (Track) -> Boolean = { it.name.startsWith("[NoVocals]/", ignoreCase = true) == noVocals }
    return files.subList(0, index).lastOrNull(sameGroup) to
        files.subList(index + 1, files.size).firstOrNull(sameGroup)
}

@Composable
internal fun TranscribeMenuItem(locked: Boolean, available: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { if (!available) PlainTooltip { Text(INACTIVE_TRANSCRIPTION_MESSAGE) } },
        state = rememberTooltipState()) {
        DropdownMenuItem(text = { Text("Transcribe lyrics") }, leadingIcon = {
            Icon(if (locked) Icons.Rounded.MicOff else Icons.Rounded.Lyrics, null)
        },
            enabled = available && !busy,
            modifier = Modifier.semantics {
                if (!available) contentDescription = INACTIVE_TRANSCRIPTION_MESSAGE
            }, onClick = onClick)
    }
}

@Composable
fun MetadataDialog(model: MusicViewModel, track: Track, ratingOnly: Boolean = false, dismiss: () -> Unit) {
    var value by remember(track.key) { mutableStateOf<SongMetadata?>(null) }
    var originalLock by remember(track.key) { mutableStateOf(false) }
    var error by remember(track.key) { mutableStateOf<String?>(null) }
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val canEdit = track.name.endsWith(".mp3", true) && account?.user?.let { user ->
        !user.isShared && model.library.library.jobs.find { it.id == track.jobId }?.canModify(user) == true
    } == true
    LaunchedEffect(track.key) {
        try {
            value = ApiJson.decodeFromJsonElement(model.api.request(songPath(track, "lyrics")))
            originalLock = value?.transcriptionLocked == true
        }
        catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = failure.message }
    }
    MetadataDialog(value, { value = it }, error = error, busy = model.busy, ratingOnly = ratingOnly,
        canEdit = canEdit, dismiss = dismiss) { song ->
        model.saveMetadata(track, song, song.transcriptionLocked.takeIf { !ratingOnly && it != originalLock })
        dismiss()
    }
}

@Composable
fun MetadataDialog(value: SongMetadata?, onValueChange: (SongMetadata) -> Unit, error: String? = null,
    busy: Boolean = false, ratingOnly: Boolean = false, canEdit: Boolean = true,
    dismiss: () -> Unit, save: (SongMetadata) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ratingOnly) "Rate song" else "Song information", Modifier.weight(1f))
            if (!ratingOnly && value != null) TranscriptionLockButton(value.transcriptionLocked, canEdit && !busy) {
                onValueChange(value.copy(transcriptionLocked = it))
            }
        }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!canEdit) Text("Rating changes require permission to edit an MP3 file.")
            if (value == null) Text(error ?: "Loading...")
            value?.let { song ->
                if (!ratingOnly) {
                    OutlinedTextField(song.title, { onValueChange(song.copy(title = it.take(500))) }, label = { Text("Title") })
                    OutlinedTextField(song.artist, { onValueChange(song.copy(artist = it.take(500))) }, label = { Text("Artist") })
                    OutlinedTextField(song.album, { onValueChange(song.copy(album = it.take(500))) }, label = { Text("Album") })
                    OutlinedTextField(song.genre, { onValueChange(song.copy(genre = it.take(500))) }, label = { Text("Genre") })
                    OutlinedTextField(song.year, { onValueChange(song.copy(year = it.take(4))) }, label = { Text("Year") })
                }
                Text("Rating: ${song.rating} / 5")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    (1..5).forEach { rating ->
                        IconToggleButton(checked = song.rating >= rating, enabled = canEdit && !busy,
                            onCheckedChange = { onValueChange(song.copy(rating = if (song.rating == rating) 0 else rating)) }, modifier = Modifier.size(44.dp)) {
                            Icon(if (song.rating >= rating) Icons.Rounded.Star else Icons.Rounded.StarBorder, "$rating stars")
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { value?.let(save) }, enabled = value != null && canEdit && !busy) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
fun TranscribeDialog(model: MusicViewModel, track: Track, dismiss: () -> Unit) {
    TranscribeDialog(dismiss, { options -> model.transcribe(track, options); dismiss() }, model.busy,
        available = model.transcriptionAvailable, lock = { model.lockTranscription(track, it); dismiss() },
        transcriptionLocked = model.library.transcriptionLocked(track))
}

@Composable
fun TranscribeDialog(dismiss: () -> Unit, submit: (TranscriptionOptions) -> Unit, busy: Boolean = false,
    available: Boolean = true, lock: (Boolean) -> Unit = {}, transcriptionLocked: Boolean = false) {
    var lockRequested by rememberSaveable(transcriptionLocked) { mutableStateOf(transcriptionLocked) }
    var noVocalsOnly by rememberSaveable { mutableStateOf(false) }
    var language by rememberSaveable { mutableStateOf("") }
    var multilingual by rememberSaveable { mutableStateOf(false) }
    var instrumental by rememberSaveable { mutableStateOf(false) }
    var vietLyricsFallback by rememberSaveable { mutableStateOf(false) }
    var addLyrics by rememberSaveable { mutableStateOf(false) }
    var lyricsMode by rememberSaveable { mutableStateOf("align") }
    var lyrics by rememberSaveable { mutableStateOf("") }
    val lockChanged = lockRequested != transcriptionLocked
    val generateOnly = transcriptionLocked || noVocalsOnly
    val options = TranscriptionOptions(language, multilingual, instrumental, vietLyricsFallback, addLyrics, lyricsMode, lyrics,
        noVocalsOnly = generateOnly)
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Transcribe Song", Modifier.weight(1f))
            TranscriptionLockButton(lockRequested, !busy) { lockRequested = it }
        }
    }, text = {
        if (lockChanged) Text(if (lockRequested) "Confirm to lock transcription for this song without sending a transcription request."
            else "Confirm to unlock transcription for this song without sending a transcription request.")
        else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!available) Text(INACTIVE_TRANSCRIPTION_MESSAGE)
            if (transcriptionLocked) Text("Transcription is locked. Only a no-vocals version can be generated; lyrics and the lock will not change.")
            TranscriptionToggle("Generate NoVocals Only", generateOnly, !busy && !transcriptionLocked) { noVocalsOnly = it }
            if (generateOnly) Text("Generates an instrumental version without transcribing or changing lyrics.",
                style = MaterialTheme.typography.bodySmall)
            else {
                Text("Language (optional)")
                ChoiceField("Language (optional)", transcriptionLanguages.first { it.first == language }.second,
                    transcriptionLanguages.map { it.second }, enabled = !busy && !vietLyricsFallback) { selected ->
                    language = transcriptionLanguages.first { it.second == selected }.first
                }
                TranscriptionToggle("Multilingual", multilingual, !busy) { multilingual = it }
                TranscriptionToggle("Create no-vocals version [Karaoke version]", instrumental, !busy) { instrumental = it }
                TranscriptionToggle("Viet Lyrics Fallback", vietLyricsFallback, !busy) {
                    vietLyricsFallback = it
                    if (it) language = "vi"
                }
                Text("Enable the Viet Lyrics fallback pass when the service's opening retry triggers.",
                    style = MaterialTheme.typography.bodySmall)
                TranscriptionToggle("Add lyrics", addLyrics, !busy) { addLyrics = it }
                if (addLyrics) {
                    Text("Lyrics mode")
                    ChoiceField("Lyrics mode", transcriptionLyricsModes.first { it.first == lyricsMode }.second,
                        transcriptionLyricsModes.map { it.second }, enabled = !busy) { selected ->
                        lyricsMode = transcriptionLyricsModes.first { it.second == selected }.first
                    }
                    Text(when (lyricsMode) {
                        "align" -> "Maps authoritative lyric lines onto ASR timing."
                        "correct" -> "Replaces recognized text while preserving ASR segment timing."
                        else -> "Biases recognition toward known words."
                    }, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(lyrics, { lyrics = it.take(100_000) }, label = { Text("Lyrics") },
                        enabled = !busy, minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = { if (lockChanged) lock(lockRequested) else submit(options) },
            enabled = !busy && (lockChanged || (available && options.isValid))) {
            Text(if (lockChanged) {
                if (lockRequested) "Lock transcription" else "Unlock transcription"
            } else if (generateOnly) "Generate" else "Transcribe")
        }
    },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable
private fun TranscriptionLockButton(locked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    IconToggleButton(checked = locked, enabled = enabled, onCheckedChange = onChange) {
        Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
            if (locked) "Unlock transcription" else "Lock transcription")
    }
}

@Composable
private fun TranscriptionToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 8.dp))
    }
}

fun timestamp(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}