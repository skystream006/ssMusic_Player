@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ssytdlp.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.ssytdlp.app.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import java.util.Locale

@Composable
fun PlayerDock(state: PlaybackState, model: MusicViewModel, expand: () -> Unit, requestNotifications: () -> Unit,
    download: (String, String) -> Unit) {
    MiniPlayer(state, model.metadata?.artwork, expand, { requestNotifications(); model.playback.toggle() },
        model.playback::next, model.playback::seek, model.playback::previous, model.playback::repeat,
        model.playback::shuffle, model.playback::previousTrack) {
        SongActionsMenu(model, state.track, download)
    }
}

@Composable
fun MiniPlayer(state: PlaybackState, artwork: String?, expand: () -> Unit, toggle: () -> Unit,
    next: () -> Unit, onSeek: (Long) -> Unit, previous: () -> Unit = {}, repeat: () -> Unit = {},
    shuffle: () -> Unit = {}, previousTrack: () -> Unit = previous, actions: @Composable () -> Unit = {}) {
    val track = state.track ?: return
    var trackDrag by remember(track.key) { mutableFloatStateOf(0f) }
    val trackOffset by key(track.key) {
        animateFloatAsState(trackDrag, animationSpec = if (trackDrag == 0f) spring() else snap(),
            label = "Mini-player swipe")
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 440.dp
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                Row(Modifier.fillMaxWidth().playerTrackSwipes(
                    enabled = true, nextEnabled = state.queue.size > 1,
                    previous = previousTrack, next = next, trackKey = track.key,
                    onDragDistanceChanged = { trackDrag = it }
                ).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).clipToBounds()) {
                        key(track.key) {
                            MiniPlayerTrack(track, artwork, expand,
                                Modifier.fillMaxWidth().absoluteOffset { IntOffset(trackOffset.roundToInt(), 0) }, compact)
                        }
                    }
                    MiniPlayerControls(state, previous, toggle, next, repeat)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShuffleButton(state.shuffle, shuffle)
                    key(track.key) {
                        var seeking by remember(state.duration) { mutableStateOf<Float?>(null) }
                        Slider(value = seeking ?: state.position.toFloat().coerceIn(0f, state.duration.toFloat().coerceAtLeast(1f)),
                            onValueChange = { seeking = it },
                            onValueChangeFinished = { seeking?.let { onSeek(it.toLong()) }; seeking = null },
                            valueRange = 0f..state.duration.toFloat().coerceAtLeast(1f), enabled = state.duration > 0,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { contentDescription = "Playback position" })
                    }
                    actions()
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
private fun ShuffleButton(shuffled: Boolean, shuffle: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(if (shuffled) "Turn shuffle off" else "Turn shuffle on") } },
        state = rememberTooltipState()) {
        IconToggleButton(checked = shuffled, onCheckedChange = { shuffle() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Shuffle, "Shuffle", Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RepeatButton(mode: Int, repeat: () -> Unit) {
    val description = when (mode) {
        Player.REPEAT_MODE_ONE -> "Repeat current song"
        Player.REPEAT_MODE_ALL -> "Repeat entire queue"
        else -> "Repeat off"
    }
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } }, state = rememberTooltipState()) {
        IconToggleButton(checked = mode != Player.REPEAT_MODE_OFF, onCheckedChange = { repeat() },
            modifier = Modifier.size(48.dp).semantics { stateDescription = description }) {
            Icon(if (mode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                "Repeat: ${if (mode == Player.REPEAT_MODE_OFF) "off" else if (mode == Player.REPEAT_MODE_ONE) "one" else "all"}", Modifier.size(20.dp))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun NowPlayingScreen(model: MusicViewModel, state: PlaybackState, onBack: () -> Unit = {},
    onSettings: () -> Unit = {}, snackbar: SnackbarHostState? = null, download: (String, String) -> Unit) {
    val landscape = isLandscape()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val visibleTab = if (landscape && tab == 2) 0 else tab
    var ratingTrack by remember { mutableStateOf<Track?>(null) }
    val account by model.sessions.account.collectAsStateWithLifecycle()
    var editingLyrics by remember(visibleTab, state.track == null, account?.origin, account?.user?.id, account?.session) {
        mutableStateOf<Triple<Track, SongMetadata, Boolean>?>(null)
    }
    var preferUslt by rememberSaveable(state.track?.key) { mutableStateOf(false) }
    val metadata = model.metadata
    val hasSylt = metadata?.sylt?.isNotEmpty() == true
    val hasUslt = !metadata?.uslt.isNullOrBlank()
    val showUslt = hasUslt && (preferUslt || !hasSylt)
    val canEditLyrics = visibleTab == 1 && state.track?.name?.endsWith(".mp3", true) == true &&
        metadata?.canEdit == true && account?.user?.isShared == false
    var artworkDrag by remember(state.track?.key, visibleTab) { mutableFloatStateOf(0f) }
    val artworkOffset by key(state.track?.key, visibleTab) {
        animateFloatAsState(artworkDrag, animationSpec = if (artworkDrag == 0f) spring() else snap(),
            label = "Artwork swipe")
    }
    val queue: @Composable (Modifier) -> Unit = { modifier ->
        QueueContent(state, model.library, modifier, onSelect = model.playback::select,
            onMove = { from, to -> model.playback.move(from, to, state.queue) },
            onRemove = { model.playback.remove(it, state.queue) },
            onRating = { ratingTrack = it },
            artwork = { rememberTrackArtwork(it, model.api, account) }) { queued, index ->
            TrackMenu(model, queued, index, onRemoveFromQueue = { model.playback.remove(index, state.queue) }, download = download)
        }
    }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
      Box(Modifier.fillMaxSize().background(appBackgroundColor()).windowInsetsPadding(
          ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
        Column(Modifier.fillMaxSize()) {
            NowPlayingTopBar(state.track, !model.busy, model::refresh, onBack, onSettings) {
                SongActionsMenu(model, state.track, download, menuKey = visibleTab,
                    editLyrics = if (canEditLyrics && metadata != null) ({ track ->
                        editingLyrics = Triple(track, metadata, showUslt)
                    }) else null)
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(if (landscape) 0.65f else 1f).fillMaxHeight().clipToBounds().testTag("now-playing-pane").playerTrackSwipes(
                    enabled = visibleTab == 0 && state.track != null, nextEnabled = state.queue.size > 1,
                    previous = model.playback::previousTrack, next = model.playback::next,
                    trackKey = state.track?.key, onDragDistanceChanged = { artworkDrag = it }
                )) {
                    if (state.track == null) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Choose a song from Library to start playing.", Modifier.padding(24.dp),
                                textAlign = TextAlign.Center)
                        }
                    } else {
                        val lyricsTabTitle = when {
                            showUslt -> "USLT Lyrics"
                            hasSylt -> "SYLT Lyrics"
                            else -> "Lyrics"
                        }
                        PrimaryTabRow(selectedTabIndex = visibleTab, containerColor = MaterialTheme.colorScheme.surface) {
                            val tabs = if (landscape) listOf("Player", lyricsTabTitle) else listOf("Player", lyricsTabTitle, "Queue")
                            tabs.forEachIndexed { index, title ->
                                Tab(selected = visibleTab == index, onClick = {
                                    if (index == 1 && visibleTab == 1 && hasSylt && hasUslt) preferUslt = !preferUslt
                                    tab = index
                                }, text = { Text(title) })
                            }
                        }
                        when (visibleTab) {
                            0 -> PlayerArtwork(state, model.metadata, Modifier.weight(1f), model.playback.controller,
                                artworkOffset = { artworkOffset }, transcription = model.library.transcription(state.track),
                                showVisualizer = model.audioVisualizerEnabled, onShowVisualizer = model::chooseAudioVisualizer,
                                visualizerStyle = model.audioVisualizerStyle, onVisualizerStyle = model::chooseAudioVisualizerStyle) {
                                model.playback.play(listOfNotNull(state.track?.noVocalsVersion))
                            }
                            1 -> Lyrics(model, state, Modifier.weight(1f), showUslt,
                                toggleSource = if (hasSylt && hasUslt) ({ preferUslt = !preferUslt }) else null)
                            else -> queue(Modifier.weight(1f))
                        }
                        if (visibleTab == 0) {
                            val rating = model.library.rating(state.track)
                            Row(Modifier.align(Alignment.CenterHorizontally)
                                .clickable(role = Role.Button, onClickLabel = "Rate song") { ratingTrack = state.track }
                                .heightIn(min = 48.dp).padding(horizontal = 12.dp).clearAndSetSemantics {
                                    contentDescription = "Rating: $rating out of 5"
                                }, verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                (1..5).forEach { star ->
                                    Icon(if (rating >= star) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                        null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        PlayerTransport(state, model.playback::seek, model.playback::previous, model.playback::toggle,
                            model.playback::next, model.playback::shuffle, model.playback::repeat, compact = landscape)
                    }
                }
                if (landscape) Column(Modifier.weight(0.35f).fillMaxHeight().testTag("now-playing-queue-pane")) {
                    Text("Queue", Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider()
                    if (state.queue.isEmpty()) Text("Your queue is empty.", Modifier.padding(16.dp))
                    else queue(Modifier.weight(1f))
                }
            }
        }
        snackbar?.let { SnackbarHost(it, Modifier.align(Alignment.BottomCenter)) }
      }
    }
    ratingTrack?.let { track -> MetadataDialog(model, track, ratingOnly = true) { ratingTrack = null } }
    editingLyrics?.takeIf { account?.user?.isShared == false }?.let { (editTrack, editMetadata, editUslt) ->
        key(editTrack.key) {
            LyricsEditorDialog(editMetadata, editUslt, model.busy, dismiss = { editingLyrics = null }) { changes ->
                model.saveLyrics(editTrack, changes) { editingLyrics = null }
            }
        }
    }
}

@Composable
private fun NowPlayingTopBar(track: Track?, refreshEnabled: Boolean, onRefresh: () -> Unit,
    onBack: () -> Unit, onSettings: () -> Unit, actions: @Composable () -> Unit) {
    val landscape = isLandscape()
    Surface(color = appBackgroundColor()) {
        Row(Modifier.fillMaxWidth().testTag("now-playing-top-bar")
            .windowInsetsPadding(TopAppBarDefaults.windowInsets).heightIn(min = 64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ToolButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                track?.let {
                    key(track.key) {
                        Text(track.displayTitle,
                            modifier = Modifier.fillMaxWidth().semantics { heading() }.basicMarquee(iterations = Int.MAX_VALUE),
                            style = if (landscape) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                    }
                }
                if (!landscape || track == null) Text(track?.playlistTitle?.ifBlank { null } ?: "Your queue",
                    style = if (track == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ToolButton(Icons.Rounded.Refresh, "Refresh", enabled = refreshEnabled, onClick = onRefresh)
            ToolButton(Icons.Rounded.Settings, "Settings", onClick = onSettings)
            actions()
        }
    }
}

@Composable
private fun SongActionsMenu(model: MusicViewModel, track: Track?, download: (String, String) -> Unit,
    menuKey: Int = 0, editLyrics: ((Track) -> Unit)? = null) {
    val account by model.sessions.account.collectAsStateWithLifecycle()
    var editingTrack by remember { mutableStateOf<Track?>(null) }
    var sharingTrack by remember { mutableStateOf<Track?>(null) }
    var replacingTrack by remember(account?.origin, account?.user?.id, account?.user?.role) { mutableStateOf<Track?>(null) }
    track?.let { track ->
        val job = model.library.library.jobs.find { it.id == track.jobId }
            ?: model.jobs.find { it.id == track.jobId }
        val canShare = track.mediaType == "audio" && account?.user?.let { user ->
            !user.isShared && job?.canModify(user) == true
        } == true
        val canEdit = track.name.endsWith(".mp3", true) && account?.user?.let { user ->
            !user.isShared && model.library.library.jobs.find { it.id == track.jobId }?.canModify(user) == true
        } == true
        var transcribe by remember(canEdit, track.key, account?.origin, account?.user?.id) { mutableStateOf(false) }
        var moreActions by remember(track.key, menuKey, account?.origin, account?.user?.id, account?.user?.role) {
            mutableStateOf(false)
        }
        Box {
            ToolButton(Icons.Rounded.MoreVert, "More song actions", modifier = Modifier.size(48.dp)) { moreActions = true }
            DropdownMenu(expanded = moreActions, onDismissRequest = { moreActions = false }) {
                if (editLyrics != null) {
                    DropdownMenuItem(text = { Text("Edit lyrics") }, enabled = !model.busy,
                        leadingIcon = { Icon(Icons.Rounded.EditNote, null) }, onClick = {
                            moreActions = false
                            editLyrics(track)
                        })
                }
                if (canEdit) {
                    DropdownMenuItem(text = { Text("Edit metadata") }, enabled = !model.busy,
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { moreActions = false; editingTrack = track })
                    TranscribeMenuItem(model.library.transcriptionLocked(track), model.transcriptionAvailable, model.busy) {
                        moreActions = false; transcribe = true
                    }
                } else if (account?.user?.isShared == true && track.mediaType == "audio") {
                    DropdownMenuItem(text = { Text("View song metadata") }, enabled = !model.busy,
                        leadingIcon = { Icon(Icons.Rounded.Info, null) },
                        onClick = { moreActions = false; editingTrack = track })
                }
                if (canReplaceFile(account?.user, job, track)) {
                    DropdownMenuItem(text = { Text("Replace File") }, enabled = !model.busy,
                        leadingIcon = { Icon(Icons.Rounded.UploadFile, null) },
                        onClick = { moreActions = false; replacingTrack = track })
                }
                SongPrivacyMenuItem(model, track) { moreActions = false }
                if (canShare) {
                    DropdownMenuItem(text = { Text("Share Media") }, enabled = !model.busy && !model.songPrivacy(track).isPrivate,
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = { moreActions = false; sharingTrack = track })
                }
                DropdownMenuItem(text = { Text("Save file") }, enabled = !model.busy,
                    leadingIcon = { Icon(Icons.Rounded.Download, null) }, onClick = {
                        moreActions = false
                        download(track.downloadUrl ?: songPath(track, "download"), track.name)
                    })
            }
        }
        if (canEdit && transcribe) TranscribeDialog(model, track) { transcribe = false }
    }
    editingTrack?.let { track -> MetadataDialog(model, track) { editingTrack = null } }
    sharingTrack?.let { track ->
        val job = model.library.library.jobs.find { it.id == track.jobId } ?: model.jobs.find { it.id == track.jobId }
        val canShare = track.mediaType == "audio" && account?.user?.let { user ->
            !user.isShared && job?.canModify(user) == true
        } == true
        if (canShare) ShareMediaDialog(model, track) { sharingTrack = null }
    }
    replacingTrack?.let { track ->
        if (canReplaceFile(account?.user,
            model.library.library.jobs.find { it.id == track.jobId } ?: model.jobs.find { it.id == track.jobId }, track)) {
            ReplaceFileDialog(model, track) { replacingTrack = null }
        }
    }
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
internal fun PlayerArtwork(state: PlaybackState, metadata: SongMetadata?, modifier: Modifier = Modifier,
    controller: MediaController? = null, artworkOffset: () -> Float = { 0f },
    transcription: Transcription? = null, showVisualizer: Boolean = false,
    onShowVisualizer: (Boolean) -> Unit = {}, visualizerStyle: AudioVisualizerStyle = AudioVisualizerStyle.WAVEFORM,
    onVisualizerStyle: (AudioVisualizerStyle) -> Unit = {}, onInstrumental: () -> Unit = {}) {
    val track = state.track
    val landscape = isLandscape()
    val options: @Composable () -> Unit = {
        if (track != null && track.mediaType != "video") {
            Row(Modifier.widthIn(max = 320.dp).fillMaxWidth()
                .semantics { contentDescription = "Audio visualizer" }
                .toggleable(value = showVisualizer, role = Role.Switch, onValueChange = onShowVisualizer)
                .padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (showVisualizer) "Audio visualizer" else "Album artwork", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge)
                Switch(checked = showVisualizer, onCheckedChange = null)
            }
            if (showVisualizer) {
                var expanded by remember { mutableStateOf(false) }
                Box(Modifier.padding(bottom = 12.dp)) {
                    OutlinedButton(onClick = { expanded = true },
                        modifier = Modifier.semantics { contentDescription = "Visualizer style" }) {
                        Text(visualizerStyle.label)
                        Icon(Icons.Rounded.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        AudioVisualizerStyle.entries.forEach { style ->
                            DropdownMenuItem(text = { Text(style.label) },
                                trailingIcon = {
                                    if (style == visualizerStyle) Icon(Icons.Rounded.Check, "Selected")
                                }, onClick = { onVisualizerStyle(style); expanded = false })
                        }
                    }
                }
            }
        }
    }
    val visual: @Composable (Modifier) -> Unit = { visualModifier ->
        if (track?.mediaType == "video") AndroidView(factory = { context -> PlayerView(context).apply { useController = false; player = controller } },
            update = { it.player = controller }, onRelease = { it.player = null }, modifier = visualModifier)
        else {
            val artworkModifier = visualModifier.absoluteOffset { IntOffset(artworkOffset().roundToInt(), 0) }
            if (showVisualizer && track != null) key(track.key) {
                PlaybackAudioVisualizer(state.playing && !state.buffering && state.error == null,
                    visualizerStyle, artworkModifier)
            } else AlbumArtwork(metadata?.artwork, artworkModifier)
        }
    }
    val details: @Composable () -> Unit = {
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
            Icon(Icons.Rounded.MicExternalOn, null); Spacer(Modifier.width(8.dp)); Text("Instrumental version")
        }
    }
    if (landscape) BoxWithConstraints(modifier.fillMaxWidth().padding(8.dp)) {
        val visualSize = minOf(maxHeight, maxWidth * 0.4f, 200.dp)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            visual(Modifier.size(visualSize).testTag("landscape-player-artwork"))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) {
                options()
                details()
            }
        }
    } else Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        options()
        visual(if (track?.mediaType == "video") Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            else Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(28.dp))
        details()
    }
}

@Composable
fun PlayerTransport(state: PlaybackState, onSeek: (Long) -> Unit, previous: () -> Unit, toggle: () -> Unit,
    next: () -> Unit, shuffle: () -> Unit, repeat: () -> Unit, compact: Boolean = false) {
    var seeking by remember(state.track?.key) { mutableStateOf<Float?>(null) }
    val slider: @Composable (Modifier) -> Unit = { modifier ->
        Slider(value = seeking ?: state.position.toFloat().coerceIn(0f, state.duration.toFloat().coerceAtLeast(1f)),
            onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { onSeek(it.toLong()) }; seeking = null },
            valueRange = 0f..state.duration.toFloat().coerceAtLeast(1f), enabled = state.duration > 0,
            modifier = modifier.semantics { contentDescription = "Playback position" })
    }
    val position: @Composable () -> Unit = {
        BoxWithConstraints {
            val inline = compact && maxWidth >= 160.dp
            Column {
                if (!inline) slider(Modifier)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (inline) Arrangement.spacedBy(4.dp) else Arrangement.SpaceBetween) {
                    Text(timestamp(seeking?.toLong() ?: state.position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (inline) slider(Modifier.weight(1f))
                    Text(timestamp(state.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Row(if (compact) Modifier else Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            ShuffleButton(state.shuffle, shuffle)
            ToolButton(Icons.Rounded.SkipPrevious, "Previous track", onClick = previous)
            FilledIconButton(onClick = toggle, modifier = Modifier.size(if (compact) 48.dp else 64.dp), shape = CircleShape) {
                Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(32.dp))
            }
            ToolButton(Icons.Rounded.SkipNext, "Next track", enabled = state.queue.size > 1, onClick = next)
            RepeatButton(state.repeat, repeat)
        }
    }
    if (compact) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) { position() }
        controls()
    } else Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
        position()
        controls()
    }
}

@Composable
fun Lyrics(model: MusicViewModel, state: PlaybackState, modifier: Modifier, preferUslt: Boolean = false,
    toggleSource: (() -> Unit)? = null) {
    val track = state.track
    val metadata = model.metadata
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        LyricsContent(metadata, state.position, track?.key, model.playback::seek, Modifier.fillMaxSize(),
            model.metadataError, track?.mediaType == "video", preferUslt, model.lyricsTextScale, model::zoomLyrics,
            endPadding = 56.dp, compact = isLandscape())
        Box(Modifier.align(Alignment.TopEnd)) {
            ToolButton(Icons.Rounded.Fullscreen, "Expand lyrics to full screen",
                modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), CircleShape)) {
                expanded = true
            }
        }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(track?.displayTitle ?: "Lyrics", style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        track?.let {
                            Text(it.displayArtist, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (toggleSource != null) TextButton(onClick = toggleSource,
                        modifier = Modifier.semantics {
                            contentDescription = if (preferUslt) "Switch to SYLT lyrics" else "Switch to USLT lyrics"
                        }) {
                        Text(if (preferUslt) "USLT" else "SYLT")
                    }
                    ToolButton(Icons.Rounded.FullscreenExit, "Exit full screen lyrics") { expanded = false }
                }
                LyricsContent(metadata, state.position, track?.key, model.playback::seek, Modifier.weight(1f),
                    model.metadataError, track?.mediaType == "video", preferUslt, model.lyricsTextScale, model::zoomLyrics)
            }
        }
    }
}

@Composable
internal fun LyricsContent(lyrics: SongMetadata?, position: Long, trackKey: String?, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier, error: String? = null, isVideo: Boolean = false, preferUslt: Boolean = false,
    textScale: Float = 1f, onZoom: (Float) -> Unit = {}, endPadding: Dp = 28.dp, compact: Boolean = false) {
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
                contentPadding = PaddingValues(start = 28.dp, top = if (compact) 4.dp else 24.dp,
                    end = endPadding, bottom = if (compact) 4.dp else 24.dp)) {
                itemsIndexed(lyrics.sylt) { index, line ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else 12.dp),
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
        } else Column(zoomModifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 28.dp, top = if (compact) 8.dp else 28.dp,
                end = endPadding, bottom = if (compact) 8.dp else 28.dp)) {
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
    val app = LocalContext.current.applicationContext as? MusicApplication
    val scope = rememberCoroutineScope()
    val cache = LocalMetadataArtworkCache.current ?: app?.artworkCache ?: remember { MetadataArtworkCache(scope) }
    val generation by cache.generation.collectAsState()
    val owner = LocalMetadataArtworkOwner.current
    val source = artwork.takeIf { owner == null || cache.belongsTo(owner) }
    var pixels by remember { mutableIntStateOf(0) }
    key(cache, generation, source) {
        val display = remember { ArtworkDisplayTiming() }
        val bitmap by produceState(cache.peek(source, pixels, generation)?.asImageBitmap(), pixels) {
            if (pixels > 0) value = cache.load(source, pixels, generation)?.asImageBitmap()
        }
        Box(modifier.onSizeChanged { pixels = artworkSizeBucket(maxOf(it.width, it.height)) }
            .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .drawWithContent {
                drawContent()
                if (bitmap != null && !display.drawn) {
                    display.drawn = true
                    DebugLog.timing(DebugEvent.ARTWORK_DISPLAYED, display.started)
                }
            }, contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap!!, "Album artwork", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else {
                if (LocalWaveAppearance.current) Image(painterResource(R.drawable.wave_cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Icon(Icons.Rounded.Album, "Album artwork unavailable", Modifier.fillMaxSize(0.32f), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
            }
        }
    }
}

internal val LocalMetadataArtworkCache = staticCompositionLocalOf<MetadataArtworkCache?> { null }
internal val LocalMetadataArtworkOwner = compositionLocalOf<MetadataOwner?> { null }

private class ArtworkDisplayTiming(val started: Long = System.nanoTime(), var drawn: Boolean = false)

@Composable
fun TrackMenu(model: MusicViewModel, track: Track, index: Int, onRemoveFromQueue: (() -> Unit)? = null,
    download: (String, String) -> Unit) {
    val inQueue = onRemoveFromQueue != null
    var open by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val user = account?.user
    val canTransfer = user?.isShared == false && track.playlistId != null
    var transfer by remember(canTransfer) { mutableStateOf<String?>(null) }
    val canModify = user != null && !user.isShared &&
        model.library.library.jobs.find { it.id == track.jobId }?.canModify(user) == true
    val canReplace = canReplaceFile(user,
        model.library.library.jobs.find { it.id == track.jobId } ?: model.jobs.find { it.id == track.jobId }, track)
    var replace by remember(canReplace, track.key, account?.origin, user?.id) { mutableStateOf(false) }
    var transcribe by remember(canModify, track.key) { mutableStateOf(false) }
    Box {
        ToolButton(Icons.Rounded.MoreVert, "Options for ${track.displayTitle}", enabled = !model.busy) { open = true }
        DropdownMenu(open, { open = false }) {
            if (!inQueue) DropdownMenuItem(text = { Text("Add to queue") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = { open = false; model.playback.enqueue(track) })
            DropdownMenuItem(text = { Text("Save file") }, leadingIcon = { Icon(Icons.Rounded.Download, null) }, onClick = { open = false; download(track.downloadUrl ?: songPath(track, "download"), track.name) })
            if (canModify && track.mediaType == "audio") DropdownMenuItem(text = { Text("Share Media") },
                enabled = !model.busy && !model.songPrivacy(track).isPrivate,
                leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { open = false; share = true })
            SongPrivacyMenuItem(model, track) { open = false }
            if (canTransfer) {
                DropdownMenuItem(text = { Text("Add to playlist") }, leadingIcon = { Icon(Icons.Rounded.LibraryAdd, null) }, onClick = { open = false; transfer = "link" })
                if (!inQueue) DropdownMenuItem(text = { Text("Move to playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) }, onClick = { open = false; transfer = "move" })
            }
            if (canModify && track.name.endsWith(".mp3", true)) {
                DropdownMenuItem(text = { Text("Edit song / rating") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { open = false; edit = true })
                TranscribeMenuItem(model.library.transcriptionLocked(track), model.transcriptionAvailable, model.busy) {
                    open = false; transcribe = true
                }
            } else if (user?.isShared == true && track.mediaType == "audio") {
                DropdownMenuItem(text = { Text("View song metadata") }, leadingIcon = { Icon(Icons.Rounded.Info, null) },
                    onClick = { open = false; edit = true })
            }
            if (canReplace) DropdownMenuItem(text = { Text("Replace File") },
                leadingIcon = { Icon(Icons.Rounded.UploadFile, null) }, enabled = !model.busy,
                onClick = { open = false; replace = true })
            if (!inQueue && canModify && track.playlistId != null) DropdownMenuItem(text = { Text("Remove from playlist") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { open = false; remove = true })
            if (onRemoveFromQueue != null) DropdownMenuItem(text = { Text("Remove from queue") },
                leadingIcon = { Icon(Icons.Rounded.Close, null) }, onClick = { open = false; onRemoveFromQueue() })
        }
    }
    if (edit) MetadataDialog(model, track) { edit = false }
    if (share && canModify && track.mediaType == "audio") ShareMediaDialog(model, track) { share = false }
    if (canReplace && replace) ReplaceFileDialog(model, track) { replace = false }
    if (transcribe) TranscribeDialog(model, track) { transcribe = false }
    if (remove) ConfirmDialog("Remove this song?", "This removes its playlist membership. If no other links remain, the server deletes the media file. This cannot be undone.", { remove = false }) { model.remove(track); remove = false }
    if (canTransfer && transfer != null) DestinationDialog(if (transfer == "link") "Add to playlist" else "Move to playlist",
        model.library.library.playlists.filter { it.id != track.playlistId }.map { it.id to it.playlistTitle }, { transfer = null }) {
        if (it != null) model.transfer(track, it, transfer == "link")
        transfer = null
    }
}

@Composable
private fun SongPrivacyMenuItem(model: MusicViewModel, track: Track, close: () -> Unit) {
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val job = model.privacyJob(track)
    val privacy = model.songPrivacy(track)
    if (job?.canChangePrivacy(account?.user) == true) {
        DropdownMenuItem(text = { Text(when {
            privacy.inherited -> "Private — inherited from source"
            privacy.isPrivate -> "Make song public"
            else -> "Make song private"
        }) }, leadingIcon = { Icon(if (privacy.isPrivate) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null) },
            enabled = !model.busy && !job.active && !privacy.inherited, onClick = {
                close()
                model.setSongPrivate(track, !privacy.isPrivate)
            })
    }
}

@Composable
internal fun ShareMediaDialog(model: MusicViewModel, track: Track, dismiss: () -> Unit) {
    ShareLinkDialog(model, title = "Share Media",
        description = "${track.displayTitle}\n\nAnyone with this link can listen, read lyrics and metadata, and save this file without signing in. They cannot edit it. Only share content you have permission to share.",
        requestPath = shareMediaPath(track), publicPath = "/share/", linkLabel = "Public media link",
        unavailableMessage = if (model.songPrivacy(track).isPrivate)
            "Private songs cannot be shared. Only the owner can access this song." else null,
        dismiss = dismiss)
}

@Composable
internal fun SharePlaylistDialog(model: MusicViewModel, playlist: LibraryPlaylist, dismiss: () -> Unit) {
    ShareLinkDialog(model, title = "Share Playlist",
        description = "${playlist.playlistTitle}\n\nAnyone with this link can listen to this playlist, read lyrics and metadata, and download its public audio files without signing in. They cannot edit it. Private and unshareable songs are excluded. Only share content you have permission to share.",
        requestPath = "/api/library/playlists/${encode(playlist.id)}/share",
        publicPath = "/share/playlist/", linkLabel = "Public playlist link",
        unavailableMessage = when {
            playlist.isPrivate -> "Private playlists cannot be shared. Only the owner can access this playlist."
            playlist.songCount == 0 -> "This playlist has no songs to share."
            else -> null
        }, dismiss = dismiss)
}

@Composable
private fun ShareLinkDialog(model: MusicViewModel, title: String, description: String,
    requestPath: String, publicPath: String, linkLabel: String, unavailableMessage: String?, dismiss: () -> Unit) {
    val context = LocalContext.current
    val account by model.sessions.account.collectAsStateWithLifecycle()
    key(requestPath, account?.origin, account?.user?.id, account?.session) {
        var generating by remember { mutableStateOf(false) }
        var url by remember { mutableStateOf<String?>(null) }
        var copied by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = { if (!generating) dismiss() }, title = { Text(title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(description)
                unavailableMessage?.let { Text(it) }
                url?.takeIf { unavailableMessage == null }?.let {
                    OutlinedTextField(value = it, onValueChange = {}, readOnly = true, label = { Text(linkLabel) })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = !generating && !model.busy && unavailableMessage == null, onClick = {
                val link = url
                if (link != null) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(linkLabel, link))
                    copied = true
                } else {
                    scope.launch {
                        generating = true
                        error = null
                        try {
                            val owner = requireNotNull(account) { "Sign in to share media." }
                            val response = model.api.request(requestPath, method = "POST").jsonObject
                            val path = response["url"]?.jsonPrimitive?.content
                                ?: throw IllegalStateException("The server did not return a share link.")
                            require(Regex("${Regex.escape(publicPath)}[A-Za-z0-9_-]{43}").matches(path)) {
                                "The server returned an invalid share link."
                            }
                            url = "${AuthProtocol.normalizeOrigin(owner.origin)}$path"
                        } catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            error = failure.message ?: "Unable to create a public link."
                        } finally {
                            generating = false
                        }
                    }
                }
            }) {
                Text(when {
                    generating -> "Generating..."
                    url == null -> "Generate public link"
                    copied -> "Copied"
                    else -> "Copy link"
                })
            }
        }, dismissButton = {
            TextButton(enabled = !generating, onClick = dismiss) { Text(if (url == null) "Cancel" else "Done") }
        })
    }
}

internal fun shareMediaPath(track: Track) =
    "/api/jobs/${encode(track.jobId)}/files/${encode(track.name)}/share"

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
    val account by model.sessions.account.collectAsStateWithLifecycle()
    var value by remember(track.key, account) { mutableStateOf<SongMetadata?>(null) }
    var originalLock by remember(track.key, account) { mutableStateOf(false) }
    var error by remember(track.key, account) { mutableStateOf<String?>(null) }
    val canEdit = track.name.endsWith(".mp3", true) && account?.user?.let { user ->
        !user.isShared && model.library.library.jobs.find { it.id == track.jobId }?.canModify(user) == true
    } == true && value?.canEdit != false
    LaunchedEffect(track.key, account) {
        try {
            value = ApiJson.decodeFromJsonElement(model.api.request(songPath(track, "lyrics")))
            originalLock = value?.transcriptionLocked == true
        }
        catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = failure.message }
    }
    MetadataDialog(value, { value = it }, error = error, busy = model.busy, ratingOnly = ratingOnly,
        canEdit = canEdit, dismiss = dismiss) { song ->
        if (canEdit) {
            model.saveMetadata(track, song, song.transcriptionLocked.takeIf { !ratingOnly && it != originalLock })
            dismiss()
        }
    }
}

@Composable
fun MetadataDialog(value: SongMetadata?, onValueChange: (SongMetadata) -> Unit, error: String? = null,
    busy: Boolean = false, ratingOnly: Boolean = false, canEdit: Boolean = true,
    dismiss: () -> Unit, save: (SongMetadata) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ratingOnly) { if (canEdit) "Rate song" else "Song rating" }
                else if (canEdit) "Song information" else "View song metadata", Modifier.weight(1f))
            if (!ratingOnly && value != null) {
                if (canEdit) TranscriptionLockButton(value.transcriptionLocked, !busy) {
                    onValueChange(value.copy(transcriptionLocked = it))
                } else Icon(if (value.transcriptionLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    if (value.transcriptionLocked) "Transcription locked" else "Transcription unlocked")
            }
        }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (value == null) Text(error ?: "Loading...")
            value?.let { song ->
                if (!ratingOnly) {
                    if (!canEdit) AlbumArtwork(song.artwork, Modifier.size(120.dp).align(Alignment.CenterHorizontally))
                    OutlinedTextField(song.title, { if (canEdit) onValueChange(song.copy(title = it.take(500))) }, readOnly = !canEdit, label = { Text("Title") })
                    OutlinedTextField(song.artist, { if (canEdit) onValueChange(song.copy(artist = it.take(500))) }, readOnly = !canEdit, label = { Text("Artist") })
                    OutlinedTextField(song.album, { if (canEdit) onValueChange(song.copy(album = it.take(500))) }, readOnly = !canEdit, label = { Text("Album") })
                    OutlinedTextField(song.genre, { if (canEdit) onValueChange(song.copy(genre = it.take(500))) }, readOnly = !canEdit, label = { Text("Genre") })
                    OutlinedTextField(song.year, { if (canEdit) onValueChange(song.copy(year = it.take(4))) }, readOnly = !canEdit, label = { Text("Year") })
                }
                Text("Rating: ${song.rating} / 5")
                if (canEdit) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    (1..5).forEach { rating ->
                        IconToggleButton(checked = song.rating >= rating, enabled = canEdit && !busy,
                            onCheckedChange = { onValueChange(song.copy(rating = if (song.rating == rating) 0 else rating)) }, modifier = Modifier.size(44.dp)) {
                            Icon(if (song.rating >= rating) Icons.Rounded.Star else Icons.Rounded.StarBorder, "$rating stars")
                        }
                    }
                }
            }
        }
    }, confirmButton = {
        if (canEdit) TextButton(onClick = { if (canEdit && !busy) value?.let(save) }, enabled = value != null && !busy) { Text("Save") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(if (canEdit) "Cancel" else "Close") } })
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