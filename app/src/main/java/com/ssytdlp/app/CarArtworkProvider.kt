package com.ssytdlp.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.ssytdlp.app.core.Account
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.ServerResource
import com.ssytdlp.app.core.Track
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

internal fun resourceArtwork(id: Int): Uri = Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/$id")

internal class CarArtwork(private val owner: Account, @Volatile var logo: Int) {
    private val scope = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(
        ApiJson.encodeToString(listOf(owner.origin, owner.user.id, owner.session.token)).toByteArray()),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private val base = ROOT.buildUpon().appendPath(scope).build()
    private val fallback get() = resourceArtwork(logo)

    fun track(track: Track): Uri = thumbnailUri(track.artworkUrl) { parts ->
        require(parts.size == 5 && parts[0] == "api" && parts[1] == "jobs" && parts[3] == "artwork")
        base.buildUpon().appendPath("track").appendPath(parts[2]).appendPath(parts[4])
    }

    fun playlist(id: String, version: Long, artworkUrl: String? = null): Uri {
        // Older servers only expose track covers.
        if (artworkUrl.isNullOrBlank()) return base.buildUpon().appendPath("playlist").appendPath(id)
            .appendQueryParameter("version", version.toString()).appendQueryParameter("logo", logo.toString()).build()
        return thumbnailUri(artworkUrl) { parts ->
            require(parts.size == 5 && parts.take(3) == listOf("api", "library", "playlists") && parts[4] == "artwork")
            base.buildUpon().appendPath("playlist-thumbnail").appendPath(parts[3]).appendQueryParameter("logo", logo.toString())
        }
    }

    private fun thumbnailUri(path: String?, local: (List<String>) -> Uri.Builder): Uri {
        if (path.isNullOrBlank()) return fallback
        return try {
            val source = Uri.parse(ServerResource.resolve(owner.origin, path))
            local(source.pathSegments).apply {
                source.getQueryParameter("v")?.let { appendQueryParameter("v", it) }
                source.getQueryParameter("userId")?.let { appendQueryParameter("userId", it) }
            }.build()
        } catch (error: IllegalArgumentException) {
            DebugLog.event(DebugEvent.API_INVALID_RESPONSE, error = error)
            fallback
        }
    }

    fun checkUri(uri: Uri) {
        val parts = uri.pathSegments
        if (uri.scheme != base.scheme || uri.authority != base.authority || parts.take(2) != base.pathSegments ||
            !(parts.size == 4 && parts[2] in listOf("playlist", "playlist-thumbnail") || parts.size == 5 && parts[2] == "track")) {
            throw FileNotFoundException("Unknown or expired car artwork.")
        }
    }

    suspend fun bitmap(uri: Uri, api: ServerApi): Bitmap? {
        checkUri(uri)
        api.requireActiveOwner(owner)
        val target = if (uri.pathSegments[2] == "playlist") {
            api.carPlaylistCover(owner, uri.lastPathSegment!!)?.let(::track)?.takeUnless { it == fallback } ?: return null
        } else uri
        val parts = target.pathSegments
        val revision = target.getQueryParameter("v")
        val userId = target.getQueryParameter("userId")
        val bytes = if (parts[2] == "playlist-thumbnail") api.carPlaylistThumbnail(owner, parts[3], revision, userId)
            else api.carThumbnail(owner, parts[3], parts[4], revision, userId)
        return decodeArtworkBitmap(bytes, thumbnail = true) ?: throw IOException("Invalid car artwork thumbnail.")
    }

    companion object {
        val ROOT: Uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.car-art/covers")
    }
}

/** The host gets a read grant and local image URIs, never a bearer token or an authenticated URL. */
class CarArtworkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Car artwork is read-only.")
        val app = requireNotNull(context).applicationContext as MusicApplication
        val owner = app.sessions.account.value ?: throw FileNotFoundException("Sign in to load artwork.")
        val art = CarArtwork(owner, LauncherIcon.currentArtwork(app))
        art.checkUri(uri)
        return openPipeHelper(uri, getType(uri), null, Unit) { pipe, _, _, _, _ ->
            ParcelFileDescriptor.AutoCloseOutputStream(pipe).use { output ->
                try {
                    val bitmap = try {
                        runBlocking { art.bitmap(uri, app.api) }
                    } catch (error: IOException) {
                        DebugLog.event(DebugEvent.API_FAILURE, status = (error as? ApiException)?.status, error = error)
                        null
                    }
                    try {
                        app.api.requireActiveOwner(owner)
                        if (bitmap == null) app.resources.openRawResource(art.logo).use { it.copyTo(output) }
                        else if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Cannot encode car artwork.")
                    } finally { bitmap?.recycle() }
                } catch (error: IOException) {
                    DebugLog.event(DebugEvent.API_FAILURE, error = error)
                } catch (error: IllegalArgumentException) {
                    DebugLog.event(DebugEvent.API_INVALID_RESPONSE, error = error)
                }
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
