package com.ssytdlp.app

import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.Library
import com.ssytdlp.app.core.LibraryEntry
import com.ssytdlp.app.core.Track
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private const val ROOT = "auto:root"
private const val ALL = "auto:all"
private const val LIBRARY = "auto:library"
private const val FOLDER = "auto:folder:"
private const val TRACKS = "auto:tracks:"
private const val SEARCH = "auto:search:"
private const val SONG = "auto:song:"
private const val GROUP = "auto:group:"
private const val GROUP_SIZE = 50
private val folderIcon = resourceArtwork(R.drawable.ic_car_folder)

@Serializable private data class SongId(val source: String, val key: String, val occurrence: Int)
@Serializable private data class GroupId(val parent: String, val start: Int)

private fun folder(id: String, title: String, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    artwork: Uri = folderIcon): MediaItem =
    MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(title)
        .setIsBrowsable(true).setIsPlayable(false).setMediaType(type).setArtworkUri(artwork).build()).build()

internal val carRoot = folder(ROOT, "ssMusic Player", artwork = resourceArtwork(R.mipmap.ic_launcher))
private val libraryFolder = folder(LIBRARY, "Folders and playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)

internal fun <T> List<T>.carPage(page: Int, size: Int): List<T> {
    require(page >= 0 && size > 0) { "Invalid library page." }
    val start = (page.toLong() * size).coerceAtMost(this.size.toLong()).toInt()
    return subList(start, (start.toLong() + size).coerceAtMost(this.size.toLong()).toInt())
}

internal val Track.carPlayable: Boolean get() =
    isPlayable && !streamUrl.isNullOrBlank() && (mediaType == "audio" || mediaType == "video")

internal class CarSongs(source: String, files: List<Track>, private val artwork: CarArtwork) {
    val tracks = files.filter { it.carPlayable }
    private val ids = buildList {
        val occurrences = mutableMapOf<String, Int>()
        for (track in tracks) {
            val key = track.key
            val occurrence = occurrences.getOrDefault(key, 0)
            add(SONG + ApiJson.encodeToString(SongId(source, key, occurrence)))
            occurrences[key] = occurrence + 1
        }
    }

    fun indexOf(id: String): Int = ids.indexOf(id).also { require(it >= 0) { "This song is no longer available." } }

    fun row(index: Int): MediaItem = MediaItem.Builder().setMediaId(ids[index])
        .setMediaMetadata(tracks[index].playbackMetadata().setArtworkUri(artwork.track(tracks[index])).build()).build()

    val rows = object : AbstractList<MediaItem>() {
        override val size: Int get() = tracks.size
        override fun get(index: Int) = row(index)
    }

    fun playable(index: Int, api: ServerApi): MediaItem {
        val track = tracks[index]
        return track.toMediaItem(api, artwork.track(track)).buildUpon().setMediaId(ids[index]).build()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal data class CarSelection(val songs: CarSongs, val index: Int) {
    fun queue(api: ServerApi, position: Long): MediaItemsWithStartPosition {
        val playable = songs.tracks.queueFor(songs.tracks[index])
        return MediaItemsWithStartPosition(playable.map { songs.playable(it.index, api) },
            playable.indexOfFirst { it.index == index }, position)
    }
}

internal fun MediaItem.RequestMetadata.voiceTerm(): String = listOf(extras?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST),
    extras?.getString(MediaStore.EXTRA_MEDIA_TITLE), extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
    extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST), searchQuery).firstOrNull { !it.isNullOrBlank() }.orEmpty().trim()

/** Scoped to one playback account. IDs describe library selections, never stream URLs or credentials. */
internal class CarMediaCatalog(
    private val artwork: CarArtwork,
    private val loadLibrary: suspend () -> Library,
    private val loadTracks: suspend (entryId: String?, query: String) -> List<Track>
) {
    private val lock = Mutex()
    private var lastSource: String? = null
    private var lastSongs: CarSongs? = null
    private var loadedAt = 0L

    private suspend fun songs(source: String): CarSongs = lock.withLock {
        // Reuse the selection for subscribe -> browse -> play (or search -> results), not indefinitely.
        lastSongs?.let { if (lastSource == source && SystemClock.elapsedRealtime() - loadedAt < 30_000) return it }
        val files = when {
            source == ALL -> loadTracks(null, "")
            source.startsWith(SEARCH) -> {
                val query = source.removePrefix(SEARCH)
                require(searchSource(query) == source) { "Invalid search selection." }
                loadTracks(null, query)
            }
            source.startsWith(TRACKS) -> {
                val id = source.removePrefix(TRACKS)
                loadLibrary().entry(id)
                loadTracks(id, "")
            }
            else -> throw IllegalArgumentException("Unknown song collection.")
        }
        CarSongs(source, files, artwork).also {
            lastSource = source
            lastSongs = it
            loadedAt = SystemClock.elapsedRealtime()
        }
    }

    private fun Library.entry(id: String): LibraryEntry =
        requireNotNull(entries.find { it.id == id }) { "This library entry is no longer available." }

    private fun Library.entryItem(entry: LibraryEntry): MediaItem = if (entry.type == "folder") {
        folder(FOLDER + entry.id, entry.name)
    } else {
        val title = playlists.find { it.id == entry.id }?.playlistTitle.orEmpty().ifBlank { entry.name }
        folder(TRACKS + entry.id, title, MediaMetadata.MEDIA_TYPE_PLAYLIST, artwork.playlist(entry.id, version))
    }

    private suspend fun rows(parent: String): List<MediaItem> = when {
        parent == ROOT -> listOf(libraryFolder)
        parent == LIBRARY || parent.startsWith(FOLDER) -> {
            val library = loadLibrary()
            val folderId = if (parent == LIBRARY) null else parent.removePrefix(FOLDER).also {
                require(library.entry(it).type == "folder") { "Not a library folder." }
            }
            buildList {
                if (folderId != null) add(folder(TRACKS + folderId, "All music in this folder"))
                library.entries.filter { it.parentId == folderId }.forEach { add(library.entryItem(it)) }
            }
        }
        else -> songs(parent).rows
    }

    private fun group(parent: String, start: Int, size: Int): MediaItem {
        require(start in 0 until size && start % GROUP_SIZE == 0) { "This library group is no longer available." }
        return folder(GROUP + ApiJson.encodeToString(GroupId(parent, start)), "${start + 1} - ${minOf(start + GROUP_SIZE, size)}")
    }

    suspend fun children(parent: String): List<MediaItem> {
        if (parent.startsWith(GROUP)) {
            val ref = ApiJson.decodeFromString<GroupId>(parent.removePrefix(GROUP))
            val rows = rows(ref.parent)
            group(ref.parent, ref.start, rows.size)
            return rows.subList(ref.start, minOf(ref.start + GROUP_SIZE, rows.size))
        }
        val rows = rows(parent)
        // Android Auto doesn't request successive MediaBrowser pages. Expose large lists as folders.
        return if (rows.size <= GROUP_SIZE) rows else (rows.indices step GROUP_SIZE).map { group(parent, it, rows.size) }
    }

    suspend fun item(id: String): MediaItem = when {
        id == ROOT -> carRoot
        id == LIBRARY -> libraryFolder
        id.startsWith(FOLDER) || id.startsWith(TRACKS) -> {
            val library = loadLibrary()
            val entry = library.entry(if (id.startsWith(FOLDER)) id.removePrefix(FOLDER) else id.removePrefix(TRACKS))
            if (entry.type == "folder" && id.startsWith(TRACKS)) folder(id, "All music in this folder")
            else library.entryItem(entry).also { require(it.mediaId == id) { "Unknown library entry." } }
        }
        id.startsWith(GROUP) -> ApiJson.decodeFromString<GroupId>(id.removePrefix(GROUP)).let {
            group(it.parent, it.start, rows(it.parent).size)
        }
        else -> selectId(id).let { it.songs.row(it.index) }
    }

    suspend fun search(query: String): CarSongs = songs(searchSource(query))

    private fun searchSource(query: String): String {
        val term = query.trim()
        require(term.length <= 200) { "Search is too long." }
        return if (term.isEmpty()) ALL else SEARCH + term
    }

    private suspend fun selectId(id: String): CarSelection {
        require(id.startsWith(SONG)) { "Choose a song from the library." }
        val ref = ApiJson.decodeFromString<SongId>(id.removePrefix(SONG))
        val songs = songs(ref.source)
        return CarSelection(songs, songs.indexOf(id))
    }

    suspend fun select(request: MediaItem): CarSelection {
        if (request.requestMetadata.searchQuery == null) return selectId(request.mediaId)
        val hints = request.requestMetadata.extras
        val playlist = hints?.getString(MediaStore.EXTRA_MEDIA_PLAYLIST)
        val term = request.requestMetadata.voiceTerm()
        var source = searchSource(term)
        val songHint = listOf(MediaStore.EXTRA_MEDIA_TITLE, MediaStore.EXTRA_MEDIA_ALBUM, MediaStore.EXTRA_MEDIA_ARTIST)
            .any { !hints?.getString(it).isNullOrBlank() }
        if (term.isNotEmpty() && (!playlist.isNullOrBlank() || !songHint)) {
            val library = loadLibrary()
            val match = library.entries.firstOrNull {
                it.type != "folder" && library.entryItem(it).mediaMetadata.title.toString().equals(term, true)
            }
            if (match != null) source = TRACKS + match.id
            else require(playlist.isNullOrBlank()) { "Playlist not found." }
        }
        val songs = songs(source)
        require(songs.tracks.isNotEmpty()) { "No matching playable tracks." }
        val artist = hints?.getString(MediaStore.EXTRA_MEDIA_ARTIST)
        val exact = songs.tracks.indexOfFirst { it.displayTitle.equals(term, true) &&
            (artist.isNullOrBlank() || it.artist.equals(artist, true)) }
        return CarSelection(songs, if (source.startsWith(SEARCH) && exact >= 0) exact else 0)
    }
}
