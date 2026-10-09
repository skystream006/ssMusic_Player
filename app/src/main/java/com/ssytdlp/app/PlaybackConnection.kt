package com.ssytdlp.app

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.ssytdlp.app.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PlaybackState(
    val connected: Boolean = false, val track: Track? = null, val queue: List<Track> = emptyList(),
    val index: Int = 0, val playing: Boolean = false, val buffering: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF, val error: String? = null
)

class PlaybackConnection(
    private val context: Context,
    private val api: ServerApi,
    private val scope: CoroutineScope,
    private val uiResumed: StateFlow<Boolean> =
        (context.applicationContext as? MusicApplication)?.uiActivity?.resumed ?: MutableStateFlow(true)
) {
    private val mutableState = MutableStateFlow(PlaybackState())
    val state = mutableState.asStateFlow()
    var controller: MediaController? = null
        private set
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var positionJob: Job? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { snapshot() }
        override fun onPlayerError(error: PlaybackException) {
            DebugLog.event(DebugEvent.PLAYBACK_FAILURE, status = error.errorCode, error = error)
            mutableState.value = mutableState.value.copy(error = "Playback failed. Check your connection or sign in again, then retry.")
        }
    }

    fun connect() {
        if (future != null) return
        DebugLog.event(DebugEvent.PLAYBACK_CONNECTING)
        val connection = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    DebugLog.event(DebugEvent.PLAYBACK_DISCONNECTED)
                    if (this@PlaybackConnection.controller !== controller) return
                    positionJob?.cancel()
                    this@PlaybackConnection.controller = null
                    future = null
                    mutableState.value = PlaybackState()
                }
            }).buildAsync()
        future = connection
        connection.addListener({
            if (future !== connection) return@addListener
            try {
                controller = connection.get()
                DebugLog.event(DebugEvent.PLAYBACK_CONNECTED)
                controller?.addListener(listener)
                snapshot()
                positionJob = scope.launch {
                    pollPlaybackPosition(uiResumed) {
                        controller?.let { player ->
                            mutableState.value = mutableState.value.copy(position = player.currentPosition.coerceAtLeast(0), duration = player.duration.coerceAtLeast(0))
                        }
                    }
                }
            } catch (error: Exception) {
                DebugLog.event(DebugEvent.PLAYBACK_FAILURE, error = error)
                future = null
                mutableState.value = mutableState.value.copy(error = "Could not connect to the playback service. Try again.")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(tracks: List<Track>, index: Int = 0) {
        val playable = tracks.filter { it.isPlayable && it.streamUrl != null }
        val selectedKey = tracks.getOrNull(index)?.key ?: return
        val selectedIndex = playable.indexOfFirst { it.key == selectedKey }
        if (selectedIndex < 0) return
        val player = controller ?: run { connect(); return }
        mutableState.value = mutableState.value.copy(error = null)
        player.setMediaItems(playable.map { it.toMediaItem(api) }, selectedIndex, 0)
        player.prepare()
        player.play()
    }

    fun enqueue(track: Track) {
        controller?.let { player ->
            player.addMediaItem(track.toMediaItem(api))
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }
    }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else { if (it.playerError != null || it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun seek(position: Long) { controller?.seekTo(position.coerceAtLeast(0)) }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun previousTrack() { controller?.seekToPreviousMediaItem() }
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() { controller?.let { it.repeatMode = (it.repeatMode + 1) % 3 } }
    fun select(index: Int) { controller?.let { it.seekToDefaultPosition(index); if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    fun remove(index: Int, expectedQueue: List<Track> = state.value.queue) {
        controller?.let { player ->
            if (index in expectedQueue.indices && player.matchesQueue(expectedQueue)) {
                player.removeMediaItem(index)
                snapshot()
            }
        }
    }
    fun move(from: Int, to: Int, expectedQueue: List<Track> = state.value.queue) {
        controller?.let { player ->
            if (player.moveQueueItem(from, to, expectedQueue)) snapshot()
        }
    }
    fun replaceFile(file: Track) {
        controller?.let { player ->
            player.replaceSongFile(file, api)
            snapshot()
        }
    }
    fun disconnect(stop: Boolean = false) {
        DebugLog.event(DebugEvent.PLAYBACK_DISCONNECTED)
        positionJob?.cancel()
        controller?.let { if (stop) { it.stop(); it.clearMediaItems() }; it.removeListener(listener) }
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
        mutableState.value = PlaybackState()
    }

    private fun snapshot() {
        val player = controller ?: return
        mutableState.value = PlaybackState(true, player.currentMediaItem?.asTrack(),
            (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).asTrack() },
            player.currentMediaItemIndex, player.isPlaying, player.playbackState == Player.STATE_BUFFERING,
            player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0), player.shuffleModeEnabled,
            player.repeatMode, if (player.playerError != null) mutableState.value.error else null)
    }
}

internal fun Player.matchesQueue(queue: List<Track>): Boolean =
    mediaItemCount == queue.size && queue.indices.all { getMediaItemAt(it).asTrack() == queue[it] }

internal fun Player.moveQueueItem(from: Int, to: Int, expectedQueue: List<Track>): Boolean {
    if (from == to || from !in expectedQueue.indices || to !in expectedQueue.indices || !matchesQueue(expectedQueue)) return false
    moveMediaItem(from, to)
    return true
}

internal fun Player.replaceSongFile(file: Track, api: ServerApi) {
    val current = currentMediaItem?.asTrack()
    val currentIndex = currentMediaItemIndex
    val position = currentPosition
    for (index in 0 until mediaItemCount) {
        val track = getMediaItemAt(index).asTrack() ?: continue
        val updated = track.withReplacedFile(file)
        if (updated != track) replaceMediaItem(index, updated.toMediaItem(api))
    }
    val reload = current?.key == file.key && current.streamUrl != file.streamUrl
    if (reload || currentMediaItemIndex != currentIndex) seekTo(currentIndex, if (reload) 0 else position)
}
