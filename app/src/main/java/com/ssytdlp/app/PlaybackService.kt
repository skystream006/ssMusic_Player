package com.ssytdlp.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.DefaultMediaNotificationProvider
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

internal const val PLAYBACK_CHECKPOINT_INTERVAL_MS = 30_000L

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var persistence: PlaybackPersistence? = null
    private var audioCache: AudioCache.Handle? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playing = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )
        val app = application as MusicApplication
        var owner = app.sessions.account.value
        val callback = CarMediaSessionCallback(this, app.api, scope).apply { setAccount(owner) }
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val library = MediaLibrarySession.Builder(this, createPlayer(app, owner, callback), callback)
            .setSessionActivity(openApp).build()
        session = library
        callback.updateVideoAction(library)
        scope.launch {
            LauncherIcon.artworkChanges.filterNotNull().collect { callback.updateArtwork(library, it) }
        }
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
            app.uiActivity.resumed.collectLatest { resumed -> if (!resumed) persistence?.save() }
        }
        scope.launch {
            app.sessions.account.distinctUntilChangedBy { it.playbackIdentity() }.collect { next ->
                if (next.playbackIdentity() != owner.playbackIdentity()) {
                    // A car can remain bound across phone sign-in/sign-out. Never reuse the old
                    // player's data-source factory or hand its in-flight work the new credentials.
                    callback.setAccount(next)
                    val previous = library.player
                    persistence?.close()
                    persistence = null
                    previous.stop()
                    previous.clearMediaItems()
                    audioCache?.close()
                    playing.value = false
                    owner = next
                    library.player = createPlayer(app, next, callback)
                    callback.updateVideoAction(library)
                    previous.release()
                    library.notifyChildrenChanged(carRoot.mediaId, Int.MAX_VALUE, null)
                    if (next == null) stopSelf()
                }
            }
        }
    }

    private fun createPlayer(app: MusicApplication, account: Account?, callback: CarMediaSessionCallback): ExoPlayer {
        val cache = account?.let { app.sessions.audioCache.acquire(it, app.api.playbackCalls(it)) }
        audioCache = cache
        val source = cache?.factory ?: DataSource.Factory {
            throw IllegalStateException("Sign in before playing music.")
        }
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
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                cache?.cacheCurrent(null)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing.value = isPlaying
                if (isPlaying) cache?.cacheCurrent(player.currentMediaItem?.localConfiguration?.uri, retry = true)
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
                    session?.takeIf { it.player === player }?.let(callback::updateVideoAction)
                }
                if (player.playWhenReady && player.playbackState != Player.STATE_IDLE) {
                    cache?.cacheCurrent(player.currentMediaItem?.localConfiguration?.uri)
                }
            }
        })
        if (account != null) {
            persistence = PlaybackPersistence(player, app.sessions.playback, account, callback::playable)
        }
        return player
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistence?.save(synchronous = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        persistence?.close()
        persistence = null
        session?.run { player.release(); release() }
        audioCache?.close()
        audioCache = null
        (application as MusicApplication).audioLevels.clear()
        session = null
        super.onDestroy()
    }
}

private fun Account?.playbackIdentity() = this?.let { Triple(it.origin, it.user.id, it.session.token) }

internal fun Track.playbackMetadata(): MediaMetadata.Builder = MediaMetadata.Builder()
    .setTitle(displayTitle).setArtist(displayArtist).setAlbumTitle(album).setIsPlayable(true).setIsBrowsable(false)
    .setMediaType(if (mediaType == "video") MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)

fun Track.toMediaItem(api: ServerApi, artworkUri: Uri? = null): MediaItem = MediaItem.Builder()
    .setMediaId(key).setUri(api.url(requireNotNull(streamUrl) { "This file is not playable." }))
    .setMediaMetadata(playbackMetadata().setArtworkUri(artworkUri)
        .setExtras(Bundle().apply { putString("track", ApiJson.encodeToString(this@toMediaItem)) }).build()).build()

fun MediaItem.asTrack(): Track? = mediaMetadata.extras?.getString("track")?.let {
    runCatching { ApiJson.decodeFromString<Track>(it) }.getOrNull()
}
