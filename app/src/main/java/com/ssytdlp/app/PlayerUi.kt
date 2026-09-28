@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ssytdlp.app

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.ssytdlp.app.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.decodeFromJsonElement

@Composable
fun PlayerDock(state: PlaybackState, model: MusicViewModel, expand: () -> Unit, requestNotifications: () -> Unit) {
    MiniPlayer(state, model.metadata?.artwork, expand, { requestNotifications(); model.playback.toggle() }, model.playback::next)
}

@Composable
fun MiniPlayer(state: PlaybackState, artwork: String?, expand: () -> Unit, toggle: () -> Unit, next: () -> Unit) {
    val track = state.track ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumArtwork(artwork, Modifier.size(48.dp).clickable(onClick = expand))
                Column(Modifier.weight(1f).clickable(onClick = expand).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(track.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(track.displayArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledIconButton(onClick = toggle, modifier = Modifier.size(44.dp), shape = CircleShape) {
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(22.dp))
                }
                ToolButton(Icons.Rounded.SkipNext, "Next track", enabled = state.queue.size > 1, onClick = next)
            }
            LinearProgressIndicator(progress = { if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp), trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerSheet(model: MusicViewModel, state: PlaybackState, dismiss: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxHeight(0.94f).playerTrackSwipes(
            enabled = state.track != null, nextEnabled = state.queue.size > 1,
            previous = model.playback::previousTrack, next = model.playback::next
        )) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(state.track?.playlistTitle?.ifBlank { null } ?: "Your queue", style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ToolButton(Icons.Rounded.Close, "Close player", onClick = dismiss)
            }
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                listOf("Player", "Lyrics", "Queue").forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }) }
            }
            when (tab) {
                0 -> PlayerArtwork(state, model.metadata, Modifier.weight(1f), model.playback.controller) {
                    model.playback.play(listOfNotNull(state.track?.noVocalsVersion))
                }
                1 -> Lyrics(model, state, Modifier.weight(1f))
                else -> LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(state.queue, key = { index, item -> "${item.key}:$index" }) { index, queued ->
                        TrackRow(queued, active = index == state.index, onClick = { model.playback.select(index) }) {
                            ToolButton(Icons.Rounded.Close, "Remove from queue") { model.playback.remove(index) }
                        }
                    }
                }
            }
            PlayerTransport(state, model.playback::seek, model.playback::previous, model.playback::toggle,
                model.playback::next, model.playback::shuffle, model.playback::repeat)
        }
    }
}

@Composable
internal fun Modifier.playerTrackSwipes(enabled: Boolean, nextEnabled: Boolean,
    previous: () -> Unit, next: () -> Unit): Modifier {
    val currentPrevious by rememberUpdatedState(previous)
    val currentNext by rememberUpdatedState(next)
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    return if (!enabled) this else pointerInput(nextEnabled, threshold) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragCancel = { distance = 0f },
            onDragEnd = {
                if (distance <= -threshold && nextEnabled) currentNext()
                else if (distance >= threshold) currentPrevious()
                distance = 0f
            },
            onHorizontalDrag = { change, amount ->
                change.consume()
                distance += amount
            }
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerArtwork(state: PlaybackState, metadata: SongMetadata?, modifier: Modifier = Modifier,
    controller: MediaController? = null, onInstrumental: () -> Unit = {}) {
    val track = state.track
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (track?.mediaType == "video") AndroidView(factory = { context -> PlayerView(context).apply { useController = false; player = controller } },
            update = { it.player = controller }, onRelease = { it.player = null }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        else AlbumArtwork(metadata?.artwork, Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(28.dp))
        Text(metadata?.title?.ifBlank { null } ?: track?.displayTitle.orEmpty(), style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(metadata?.artist?.ifBlank { null } ?: track?.displayArtist.orEmpty(), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
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
            IconToggleButton(checked = state.repeat != Player.REPEAT_MODE_OFF, onCheckedChange = { repeat() }) {
                Icon(if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repeat: ${if (state.repeat == Player.REPEAT_MODE_OFF) "off" else if (state.repeat == Player.REPEAT_MODE_ONE) "one" else "all"}", Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun Lyrics(model: MusicViewModel, state: PlaybackState, modifier: Modifier) {
    val lyrics = model.metadata
    val active = lyrics?.sylt?.indexOfLast { it.time * 1000 <= state.position } ?: -1
    if (lyrics?.sylt?.isNotEmpty() == true) LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp)) {
        itemsIndexed(lyrics.sylt) { index, line ->
            Text(line.text, modifier = Modifier.fillMaxWidth().clickable { model.playback.seek((line.time * 1000).toLong()) }.padding(vertical = 12.dp),
                style = if (index == active) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                color = if (index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp)) {
        Text(lyrics?.uslt?.ifBlank { null } ?: model.metadataError ?: if (lyrics == null && state.track?.mediaType != "video") "Loading lyrics..." else "No lyrics available",
            style = MaterialTheme.typography.bodyLarge)
    }
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
    var transfer by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf(false) }
    var transcribe by remember { mutableStateOf(false) }
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val user = account?.user
    val canModify = user != null && model.library.library.jobs.find { it.id == track.jobId }?.canModify(user) == true
    Box {
        ToolButton(Icons.Rounded.MoreVert, "Options for ${track.displayTitle}", enabled = !model.busy) { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Add to queue") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = { open = false; model.playback.enqueue(track) })
            DropdownMenuItem(text = { Text("Save file") }, leadingIcon = { Icon(Icons.Rounded.Download, null) }, onClick = { open = false; download(track.downloadUrl ?: songPath(track, "download"), track.name) })
            if (track.playlistId != null) {
                DropdownMenuItem(text = { Text("Add to playlist") }, leadingIcon = { Icon(Icons.Rounded.LibraryAdd, null) }, onClick = { open = false; transfer = "link" })
                DropdownMenuItem(text = { Text("Move to playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) }, onClick = { open = false; transfer = "move" })
            }
            val state = model.library
            if (state.selectedId == track.playlistId && state.tracks.totalPages == 1 && state.search.isEmpty()) {
                if (index > 0) DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) }, onClick = { open = false; model.reorder(track, state.tracks.files[index - 1], false) })
                if (index < state.tracks.files.lastIndex) DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) }, onClick = { open = false; model.reorder(track, state.tracks.files[index + 1], true) })
            }
            if (canModify && track.name.endsWith(".mp3", true)) {
                DropdownMenuItem(text = { Text("Edit song / rating") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { open = false; edit = true })
                DropdownMenuItem(text = { Text("Transcribe lyrics") }, leadingIcon = { Icon(Icons.Rounded.Lyrics, null) }, onClick = { open = false; transcribe = true })
            }
            if (canModify && track.playlistId != null) DropdownMenuItem(text = { Text("Remove from playlist") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { open = false; remove = true })
        }
    }
    if (edit) MetadataDialog(model, track) { edit = false }
    if (transcribe) TranscribeDialog(model, track) { transcribe = false }
    if (remove) ConfirmDialog("Remove this song?", "This removes its playlist membership. If no other links remain, the server deletes the media file. This cannot be undone.", { remove = false }) { model.remove(track); remove = false }
    if (transfer != null) DestinationDialog(if (transfer == "link") "Add to playlist" else "Move to playlist",
        model.library.library.playlists.filter { it.id != track.playlistId }.map { it.id to it.playlistTitle }, { transfer = null }) {
        if (it != null) model.transfer(track, it, transfer == "link")
        transfer = null
    }
}

@Composable
fun MetadataDialog(model: MusicViewModel, track: Track, dismiss: () -> Unit) {
    var value by remember { mutableStateOf<SongMetadata?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(track.key) {
        try { value = ApiJson.decodeFromJsonElement(model.api.request(songPath(track, "lyrics"))) }
        catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = failure.message }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Song information") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (value == null) Text(error ?: "Loading...")
            value?.let { song ->
                OutlinedTextField(song.title, { value = song.copy(title = it.take(500)) }, label = { Text("Title") })
                OutlinedTextField(song.artist, { value = song.copy(artist = it.take(500)) }, label = { Text("Artist") })
                OutlinedTextField(song.album, { value = song.copy(album = it.take(500)) }, label = { Text("Album") })
                OutlinedTextField(song.genre, { value = song.copy(genre = it.take(500)) }, label = { Text("Genre") })
                OutlinedTextField(song.year, { value = song.copy(year = it.take(4)) }, label = { Text("Year") })
                Text("Rating: ${song.rating} / 5")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    (1..5).forEach { rating ->
                        IconToggleButton(checked = song.rating >= rating, onCheckedChange = { value = song.copy(rating = if (song.rating == rating) 0 else rating) }, modifier = Modifier.size(44.dp)) {
                            Icon(if (song.rating >= rating) Icons.Rounded.Star else Icons.Rounded.StarBorder, "$rating stars")
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { value?.let { model.saveMetadata(track, it); dismiss() } }, enabled = value != null && !model.busy) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
fun TranscribeDialog(model: MusicViewModel, track: Track, dismiss: () -> Unit) {
    var language by rememberSaveable { mutableStateOf("en") }
    var instrumental by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Transcribe lyrics") }, text = {
        Column {
            OutlinedTextField(language, { language = it.take(8) }, label = { Text("Language code") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(instrumental, { instrumental = it }); Text("Create instrumental version", modifier = Modifier.weight(1f))
            }
        }
    }, confirmButton = { TextButton(onClick = { model.transcribe(track, language, instrumental); dismiss() }, enabled = language.isNotBlank()) { Text("Transcribe") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

fun timestamp(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}