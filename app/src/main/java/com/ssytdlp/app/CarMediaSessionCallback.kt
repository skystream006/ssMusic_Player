package com.ssytdlp.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.SongVideo
import com.ssytdlp.app.core.Track
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(UnstableApi::class)
internal class CarMediaSessionCallback(private val context: Context, private val api: ServerApi,
    scope: CoroutineScope) : MediaLibrarySession.Callback {
    private var owner: Account? = null
    private var catalog: CarMediaCatalog? = null
    private var artwork: CarArtwork? = null
    private val requests = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    fun setAccount(account: Account?) {
        requests.coroutineContext.cancelChildren()
        owner = account
        artwork = account?.let { CarArtwork(it, LauncherIcon.currentArtwork(context)) }
        catalog = account?.let {
            CarMediaCatalog(checkNotNull(artwork), { api.carLibrary(it) }, { entry, query -> api.carTracks(it, entry, query).files })
        }
    }

    fun playable(track: Track): MediaItem = track.toMediaItem(api, checkNotNull(artwork).track(track))

    fun updateArtwork(session: MediaLibrarySession, logo: Int) {
        val art = artwork ?: return
        if (logo == art.logo) return
        art.logo = logo
        val player = session.player
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            val uri = item.asTrack()?.let(art::track) ?: continue
            if (uri != item.mediaMetadata.artworkUri) player.replaceMediaItem(i, item.buildUpon()
                .setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(uri).build()).build())
        }
        session.notifyChildrenChanged(carRoot.mediaId, Int.MAX_VALUE, null)
    }

    override fun onConnect(session: MediaSession, controller: ControllerInfo): MediaSession.ConnectionResult {
        // Legacy binding initially has no caller identity. Media3 provides the real caller here.
        if (controller.uid != Process.myUid() && !controller.isTrusted) {
            val googleMediaHost = controller.packageName in setOf(AUTO, "com.google.android.googlequicksearchbox") &&
                controller.uid >= 0 && Build.VERSION.SDK_INT >= 28 &&
                context.packageManager.hasSigningCertificate(controller.uid, googleReleaseCert, PackageManager.CERT_INPUT_SHA256)
            if (!googleMediaHost) return MediaSession.ConnectionResult.reject()
        }
        val accepted = super.onConnect(session, controller)
        if (controller.packageName == AUTO) context.grantUriPermission(AUTO, CarArtwork.ROOT,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        // Media3 publishes framework/legacy playback actions through the notification controller.
        if (!canSwitchSource(session, controller)) return accepted
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(accepted.availableSessionCommands.buildUpon().add(toggleVideoAudio).build()).build()
    }

    private fun canSwitchSource(session: MediaSession, controller: ControllerInfo) =
        controller.packageName == AUTO || session.isMediaNotificationController(controller)

    private fun MediaItem.linkedSource(): String? = asTrack()?.let { track ->
        if (track.mediaType == "audio" && SongVideo(track.videoUrl, track.linkedVideo).available)
            track.linkedVideo?.streamUrl else null
    }

    fun updateVideoAction(session: MediaSession) {
        val item = session.player.currentMediaItem
        val buttons = if (item?.linkedSource() == null) emptyList() else {
            val linked = item.mediaMetadata.extras?.getBoolean(VIDEO_AUDIO) == true
            listOf(CommandButton.Builder(if (linked) CommandButton.ICON_ALBUM else CommandButton.ICON_UNDEFINED)
                .apply { if (!linked) setCustomIconResId(R.drawable.ic_car_video_audio) }
                .setSessionCommand(toggleVideoAudio).setDisplayName(if (linked) "Use song audio" else "Use video audio")
                .setSlots(CommandButton.SLOT_OVERFLOW).build())
        }
        if (session.mediaButtonPreferences == buttons) return
        session.setMediaButtonPreferences(buttons)
        session.mediaNotificationControllerInfo?.let { session.setMediaButtonPreferences(it, buttons) }
    }

    override fun onCustomCommand(session: MediaSession, controller: ControllerInfo, customCommand: SessionCommand,
        args: Bundle): ListenableFuture<SessionResult> {
        if (customCommand != toggleVideoAudio) return super.onCustomCommand(session, controller, customCommand, args)
        if (!canSwitchSource(session, controller)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
        val player = session.player
        val item = player.currentMediaItem
        val video = item?.linkedSource() ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        fun failure(error: Exception): ListenableFuture<SessionResult> {
            val result = problem(error)
            if (controller.controllerVersion == ControllerInfo.LEGACY_CONTROLLER_VERSION) session.sendError(result)
            return Futures.immediateFuture(SessionResult(result))
        }
        return try {
            api.requireActiveOwner(owner ?: throw ApiException(401, SIGN_IN))
            val linked = item.mediaMetadata.extras?.getBoolean(VIDEO_AUDIO) != true
            val source = if (linked) video else requireNotNull(item.asTrack()?.streamUrl)
            val metadata = item.mediaMetadata.buildUpon().setExtras(Bundle(item.mediaMetadata.extras).apply {
                putBoolean(VIDEO_AUDIO, linked)
            }).build()
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            player.replaceMediaItem(index, item.buildUpon().setUri(api.url(source)).setMediaMetadata(metadata).build())
            player.seekTo(index, position)
            updateVideoAction(session)
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        } catch (error: IOException) { failure(error) }
        catch (error: IllegalArgumentException) { failure(error) }
    }

    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: ControllerInfo, params: LibraryParams?) =
        Futures.immediateFuture(LibraryResult.ofItem(carRoot, params))

    private suspend fun <T> read(block: suspend CarMediaCatalog.() -> T): T {
        val account = owner ?: throw ApiException(401, SIGN_IN)
        val current = checkNotNull(catalog)
        return withContext(Dispatchers.Default) {
            api.requireActiveOwner(account)
            current.block()
        }.also { api.requireActiveOwner(account) }
    }

    private fun problem(error: Exception): SessionError {
        if (error is CancellationException) throw error
        DebugLog.event(DebugEvent.PLAYBACK_FAILURE, status = (error as? ApiException)?.status, error = error)
        return when {
            error is ApiException && error.status == 401 -> SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, SIGN_IN)
            error is ApiException && error.status == 403 -> SessionError(SessionError.ERROR_PERMISSION_DENIED, "This music is not available to this account.")
            error is IllegalArgumentException -> SessionError(SessionError.ERROR_BAD_VALUE, "Music not found. Choose a song from the library.")
            else -> SessionError(SessionError.ERROR_IO, "Could not load music. Check the server connection and try again.")
        }
    }

    private fun <T : Any> library(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> = requests.future {
        try { block() } catch (error: Exception) { LibraryResult.ofError<T>(problem(error)) }
    }

    override fun onGetItem(session: MediaLibrarySession, browser: ControllerInfo, mediaId: String) = library {
        LibraryResult.ofItem(if (mediaId == carRoot.mediaId) carRoot else read { item(mediaId) }, null)
    }

    override fun onGetChildren(session: MediaLibrarySession, browser: ControllerInfo, parentId: String,
        page: Int, pageSize: Int, params: LibraryParams?) = library {
        read { LibraryResult.ofItemList(children(parentId).carPage(page, pageSize), params) }
    }

    override fun onSearch(session: MediaLibrarySession, browser: ControllerInfo, query: String, params: LibraryParams?) = library {
        // MediaBrowserCompat ignores the count and waits for this notification even on failure.
        // Its subsequent getSearchResult call delivers either the songs or the actual error.
        val count = if (browser.controllerVersion == ControllerInfo.LEGACY_CONTROLLER_VERSION) Int.MAX_VALUE
            else read { search(query).tracks.size }
        session.notifySearchResultChanged(browser, query, count, params)
        LibraryResult.ofVoid(params)
    }

    override fun onGetSearchResult(session: MediaLibrarySession, browser: ControllerInfo, query: String,
        page: Int, pageSize: Int, params: LibraryParams?) = library {
        read { LibraryResult.ofItemList(search(query).rows.carPage(page, pageSize), params) }
    }

    private fun <T> playback(session: MediaSession, controller: ControllerInfo, block: suspend () -> T) = requests.future {
        try { block() } catch (error: Exception) {
            val message = problem(error)
            if (controller.controllerVersion == ControllerInfo.LEGACY_CONTROLLER_VERSION) session.sendError(message)
            else session.sendError(controller, message)
            throw error
        }
    }

    private fun isPhoneController(session: MediaSession, controller: ControllerInfo) =
        controller.uid == Process.myUid() && controller.packageName == context.packageName &&
            !session.isMediaNotificationController(controller)

    override fun onAddMediaItems(mediaSession: MediaSession, controller: ControllerInfo, mediaItems: List<MediaItem>) =
        playback(mediaSession, controller) {
            val phone = isPhoneController(mediaSession, controller)
            read {
                mediaItems.map { item ->
                    if (phone && item.mediaMetadata.extras?.containsKey("track") == true)
                        playable(requireNotNull(item.asTrack()) { "Missing track information." })
                    else select(item).let { it.songs.playable(it.index, api) }
                }
            }
        }

    override fun onSetMediaItems(mediaSession: MediaSession, controller: ControllerInfo, mediaItems: List<MediaItem>,
        startIndex: Int, startPositionMs: Long): ListenableFuture<MediaItemsWithStartPosition> {
        if (mediaItems.size != 1 || (isPhoneController(mediaSession, controller) &&
                mediaItems.single().mediaMetadata.extras?.containsKey("track") == true))
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        return playback(mediaSession, controller) {
            val item = mediaItems.single()
            if (item.requestMetadata.searchQuery != null && item.requestMetadata.voiceTerm().isEmpty() &&
                mediaSession.player.mediaItemCount > 0) currentQueue(mediaSession)
            else read { select(item).queue(api, startPositionMs) }
        }
    }

    override fun onPlaybackResumption(mediaSession: MediaSession, controller: ControllerInfo) = playback(mediaSession, controller) {
        if (mediaSession.player.mediaItemCount > 0) currentQueue(mediaSession)
        else read {
            val songs = search("")
            require(songs.tracks.isNotEmpty()) { "No playable tracks in the library." }
            CarSelection(songs, 0).queue(api, C.TIME_UNSET)
        }
    }

    private fun currentQueue(session: MediaSession): MediaItemsWithStartPosition {
        api.requireActiveOwner(owner ?: throw ApiException(401, SIGN_IN))
        return session.player.run {
            MediaItemsWithStartPosition((0 until mediaItemCount).map(::getMediaItemAt), currentMediaItemIndex, currentPosition)
        }
    }

    private companion object {
        const val AUTO = "com.google.android.projection.gearhead"
        const val VIDEO_AUDIO = "car_video_audio"
        val toggleVideoAudio = SessionCommand("${BuildConfig.APPLICATION_ID}.TOGGLE_VIDEO_AUDIO", Bundle.EMPTY)
        const val SIGN_IN = "When parked, sign in to ssMusic Player on your phone."
        // Release certificate documented at developer.android.com/media/implement/assistant#signatures.
        val googleReleaseCert = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
