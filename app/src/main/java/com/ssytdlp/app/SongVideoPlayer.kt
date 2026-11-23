package com.ssytdlp.app

import android.content.Context
import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ServerResource
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class PlayerDisplay(val label: String) {
    ALBUM_ART("Album art"), AUDIO_VISUALIZER("Audio visualizer"), VIDEO("Song video");

    companion object {
        fun fromPreference(value: String?, legacyVisualizer: Boolean = false): PlayerDisplay =
            entries.firstOrNull { it.name == value }
                ?: if (legacyVisualizer) AUDIO_VISUALIZER else ALBUM_ART
    }
}

internal data class VideoZoomState(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    fun transform(zoom: Float, pan: Offset, centroid: Offset, size: IntSize): VideoZoomState {
        val nextScale = (scale * zoom).coerceIn(1f, 5f)
        if (nextScale == 1f) return VideoZoomState()
        val focus = centroid - Offset(size.width / 2f, size.height / 2f)
        val nextOffset = (offset - focus) * (nextScale / scale) + focus + pan
        val limitX = size.width * (nextScale - 1f) / 2f
        val limitY = size.height * (nextScale - 1f) / 2f
        return VideoZoomState(nextScale, Offset(nextOffset.x.coerceIn(-limitX, limitX),
            nextOffset.y.coerceIn(-limitY, limitY)))
    }
}

internal fun synchronizeSongVideo(video: Player, state: PlaybackState, audio: Player? = null, forceSeek: Boolean = false) {
    video.volume = 0f
    if (video.playerError != null || audio != null && audio.currentMediaItem?.asTrack()?.key != state.track?.key) {
        video.pause()
        return
    }
    val position = (audio?.currentPosition ?: state.position).coerceAtLeast(0)
    val playing = audio?.isPlaying ?: state.playing
    val buffering = audio?.let { it.playbackState == Player.STATE_BUFFERING } ?: state.buffering
    val speed = audio?.playbackParameters?.speed ?: state.speed
    if (video.playbackParameters.speed != speed) video.setPlaybackSpeed(speed)
    val end = video.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
    val target = position.coerceAtMost(end)
    if (video.currentPosition != target && (forceSeek || !playing || abs(video.currentPosition - target) > 200)) {
        video.seekTo(target)
    }
    video.playWhenReady = playing && !buffering && state.error == null && position < end
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun createSongVideoPlayer(context: Context, cache: AudioCache.Handle, account: Account, streamUrl: String): ExoPlayer {
    val url = ServerResource.resolve(account.origin, streamUrl)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(cache.factory))
        .build().apply {
            val video = this
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) cache.cacheCurrent(video.currentMediaItem?.localConfiguration?.uri, retry = true)
                }
                override fun onEvents(player: Player, events: Player.Events) {
                    if (player.playWhenReady && player.playbackState != Player.STATE_IDLE) {
                        cache.cacheCurrent(player.currentMediaItem?.localConfiguration?.uri)
                    }
                }
            })
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            setMediaItem(MediaItem.fromUri(url))
        }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun SongVideoPlayer(state: PlaybackState, api: ServerApi?, account: Account?, streamUrl: String,
    audio: Player?, cache: AudioCache, modifier: Modifier = Modifier) {
    if (api == null || account == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Sign in to watch the song video.") }
        return
    }
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val owner = remember(account.origin, account.user.id, account.session) { account }
    val trackKey = state.track?.key
    val latestState by rememberUpdatedState(state)
    var player by remember(api, owner, trackKey, streamUrl) { mutableStateOf<ExoPlayer?>(null) }
    var error by remember(api, owner, trackKey, streamUrl) { mutableStateOf<String?>(null) }
    var buffering by remember(api, owner, trackKey, streamUrl) { mutableStateOf(true) }
    var attempt by remember(api, owner, trackKey, streamUrl) { mutableIntStateOf(0) }
    DisposableEffect(context, api, owner, trackKey, streamUrl, audio, cache, resumed, attempt) {
        if (!resumed) onDispose {}
        else {
            error = null
            buffering = true
            val videoCache = cache.acquire(owner, api.playbackCalls(owner))
            val instance = try {
                createSongVideoPlayer(context, videoCache, owner, streamUrl)
            } catch (failure: IllegalArgumentException) {
                videoCache.close()
                DebugLog.event(DebugEvent.PLAYBACK_FAILURE, error = failure)
                error = "The server returned an invalid song video stream. Song audio is unchanged."
                null
            }
            val audioListener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    instance?.let {
                        synchronizeSongVideo(it, latestState, audio, events.contains(Player.EVENT_POSITION_DISCONTINUITY))
                    }
                }
            }
            val videoListener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    buffering = playbackState == Player.STATE_BUFFERING
                    if (playbackState == Player.STATE_READY) instance?.let { synchronizeSongVideo(it, latestState, audio) }
                }
                override fun onPlayerError(failure: PlaybackException) {
                    DebugLog.event(DebugEvent.PLAYBACK_FAILURE, status = failure.errorCode, error = failure)
                    error = "The song video could not be played. Song audio is unchanged."
                    instance?.pause()
                }
            }
            player = instance
            instance?.let {
                it.addListener(videoListener)
                audio?.addListener(audioListener)
                synchronizeSongVideo(it, latestState, audio, forceSeek = true)
                it.prepare()
            }
            onDispose {
                audio?.removeListener(audioListener)
                instance?.removeListener(videoListener)
                player = null
                try {
                    instance?.release()
                } finally {
                    videoCache.close()
                }
            }
        }
    }
    SideEffect { player?.let { synchronizeSongVideo(it, state, audio) } }
    key(api, owner, trackKey, streamUrl) {
        VideoPlayerSurface(player, state.track?.displayTitle.orEmpty(), modifier.testTag("linked-song-video")) {
            if (resumed && buffering && error == null) CircularProgressIndicator(Modifier.size(32.dp))
            error?.let {
                Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { attempt++ }) { Text("Retry video") }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoPlayerSurface(player: Player?, title: String, modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {}) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var zoom by remember(fullscreen) { mutableStateOf(VideoZoomState()) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val content: @Composable BoxScope.(Boolean) -> Unit = { expanded ->
        AndroidView(factory = {
            if (expanded) LayoutInflater.from(it).inflate(R.layout.fullscreen_video_player, null) as PlayerView
            else PlayerView(it).apply { useController = false }
        }, update = {
            it.player = player
            it.scaleX = if (expanded) zoom.scale else 1f
            it.scaleY = if (expanded) zoom.scale else 1f
            it.translationX = if (expanded) zoom.offset.x else 0f
            it.translationY = if (expanded) zoom.offset.y else 0f
        }, onRelease = { it.player = null },
            modifier = Modifier.matchParentSize())
        if (expanded) {
            Box(Modifier.matchParentSize().testTag("video-zoom-surface").pointerInput(Unit) {
                detectTransformGestures { centroid, pan, scale, _ ->
                    zoom = zoom.transform(scale, pan, centroid, viewportSize)
                }
            })
        }
        overlay()
        if (expanded) {
            Row(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp)
                .background(Color.Black.copy(alpha = 0.65f), CircleShape),
                verticalAlignment = Alignment.CenterVertically) {
                fun zoomBy(factor: Float) {
                    zoom = zoom.transform(factor, Offset.Zero,
                        Offset(viewportSize.width / 2f, viewportSize.height / 2f), viewportSize)
                }
                IconButton(onClick = { zoomBy(0.8f) }, enabled = zoom.scale > 1f) {
                    Icon(Icons.Rounded.ZoomOut, "Zoom out video", tint = Color.White)
                }
                TextButton(onClick = { zoom = VideoZoomState() },
                    modifier = Modifier.semantics { contentDescription = "Reset video zoom" }) {
                    Text("${(zoom.scale * 100).roundToInt()}%", color = Color.White)
                }
                IconButton(onClick = { zoomBy(1.25f) }, enabled = zoom.scale < 5f) {
                    Icon(Icons.Rounded.ZoomIn, "Zoom in video", tint = Color.White)
                }
            }
        }
        IconButton(onClick = { fullscreen = !expanded },
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)
                .background(Color.Black.copy(alpha = 0.65f), CircleShape)) {
            Icon(if (expanded) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                if (expanded) "Exit full screen video" else "Expand video to full screen", tint = Color.White)
        }
    }
    Box(modifier.background(Color.Black).semantics { contentDescription = "Video for $title" },
        contentAlignment = Alignment.Center) {
        if (!fullscreen) content(false)
    }
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        LaunchedEffect(view) {
            val window = (view.parent as DialogWindowProvider).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds().testTag("fullscreen-video")
            .onSizeChanged {
                viewportSize = it
                zoom = zoom.transform(1f, Offset.Zero, Offset.Zero, it)
            }.semantics {
                contentDescription = "Video for $title"
                stateDescription = "Zoom ${(zoom.scale * 100).roundToInt()}%"
            }, contentAlignment = Alignment.Center) {
            content(true)
        }
    }
}
