package com.ssytdlp.app

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.Track

internal class PlaybackPersistence(
    private val player: Player,
    private val store: PlaybackStore,
    private val account: Account,
    mediaItem: (Track) -> MediaItem
) {
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { save() }
    }

    init {
        // Only a new, empty player is restored; reconnecting must not rewind live playback.
        if (player.mediaItemCount == 0) store.playback(account)?.let { saved ->
            val items = saved.queue.mapIndexedNotNull { index, track ->
                if (!track.isPlayable || track.streamUrl == null) null
                else runCatching { index to mediaItem(track) }.getOrNull()
            }
            if (items.isNotEmpty()) {
                val current = saved.index.coerceIn(saved.queue.indices)
                val start = items.indexOfFirst { it.first >= current }.takeIf { it >= 0 } ?: 0
                player.setMediaItems(items.map { it.second }, start,
                    if (items[start].first == current) saved.position.coerceAtLeast(0) else 0)
                player.shuffleModeEnabled = saved.shuffle
                player.repeatMode = saved.repeat.takeIf {
                    it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL
                } ?: Player.REPEAT_MODE_OFF
                player.playWhenReady = false
            }
        }
        player.addListener(listener)
        save()
    }

    fun save(synchronous: Boolean = false) {
        val queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).asTrack() ?: return }
        store.savePlayback(account, SavedPlayback(queue, player.currentMediaItemIndex.coerceAtLeast(0),
            player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode), synchronous)
    }

    fun close() {
        save(synchronous = true)
        player.removeListener(listener)
    }
}
