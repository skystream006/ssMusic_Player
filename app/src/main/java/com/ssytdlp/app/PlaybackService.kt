package com.ssytdlp.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

internal const val PLAYBACK_CHECKPOINT_INTERVAL_MS = 30_000L

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var persistence: PlaybackPersistence? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val app = application as MusicApplication
        val source = OkHttpDataSource.Factory(app.api.authenticatedClient)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(app.audioLevels)))
                    .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val account = app.sessions.account.value
        if (account != null) {
            persistence = PlaybackPersistence(player, app.sessions.playback, account) { it.toMediaItem(app.api) }
            val playing = MutableStateFlow(player.isPlaying)
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing.value = isPlaying }
            })
            scope.launch {
                playing.collectLatest { active ->
                    if (!active) return@collectLatest
                    while (true) {
                        delay(PLAYBACK_CHECKPOINT_INTERVAL_MS)
                        persistence?.save()
                    }
                }
            }
            scope.launch {
                app.uiActivity.resumed.collectLatest { resumed ->
                    if (!resumed) persistence?.save()
                }
            }
        }
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                    if (controller.packageName != packageName) return Futures.immediateFailedFuture(SecurityException("Use the app to choose music."))
                    return try {
                        Futures.immediateFuture(mediaItems.map { item ->
                            val track = item.asTrack() ?: throw IllegalArgumentException("Missing track information.")
                            track.toMediaItem(app.api)
                        })
                    } catch (error: Exception) { Futures.immediateFailedFuture(error) }
                }
            }).build()
        val owner = account?.let { it.origin to it.user.id }
        scope.launch {
            app.sessions.account.map { it?.let { account -> account.origin to account.user.id } }.distinctUntilChanged().collect { identity ->
                if (identity == null || identity != owner) {
                    player.stop()
                    player.clearMediaItems()
                    stopSelf()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session.takeIf { controllerInfo.packageName == packageName || controllerInfo.isTrusted }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistence?.save(synchronous = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        persistence?.close()
        persistence = null
        session?.run { player.release(); release() }
        (application as MusicApplication).audioLevels.clear()
        session = null
        super.onDestroy()
    }
}

fun Track.toMediaItem(api: ServerApi): MediaItem = MediaItem.Builder()
    .setMediaId(key).setUri(api.url(requireNotNull(streamUrl) { "This file is not playable." }))
    .setMediaMetadata(MediaMetadata.Builder().setTitle(displayTitle).setArtist(displayArtist).setAlbumTitle(album)
        .setExtras(Bundle().apply { putString("track", ApiJson.encodeToString(this@toMediaItem)) }).build()).build()

fun MediaItem.asTrack(): Track? = mediaMetadata.extras?.getString("track")?.let {
    runCatching { ApiJson.decodeFromString<Track>(it) }.getOrNull()
}
