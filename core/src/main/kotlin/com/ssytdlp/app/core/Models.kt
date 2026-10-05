package com.ssytdlp.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

val ApiJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

@Serializable
data class User(
    val id: String = "", val name: String = "", val role: String = "user", val status: String = "approved",
    val sharedUserIds: List<String> = emptyList()
) {
    val isShared: Boolean get() = role.equals("shared", ignoreCase = true)
}

@Serializable
data class Session(val token: String, val expiresAt: String, val tokenType: String = "Bearer")

@Serializable
data class LoginResponse(val user: User, val session: Session)

@Serializable
data class Account(val origin: String, val user: User, val session: Session)

@Serializable
data class UserResponse(val user: User)

@Serializable
data class LibraryEntry(
    val id: String, val type: String, val name: String = "", val parentId: String? = null,
    val protected: Boolean = false, @SerialName("private") val isPrivate: Boolean = false
)

@Serializable
data class LibraryPlaylist(
    val id: String, val playlistTitle: String = "", val songCount: Int = 0, val jobId: String? = null,
    @SerialName("private") val isPrivate: Boolean = false,
    val initiatedBy: User? = null, val status: String = "",
    val contributors: List<User> = emptyList()
) {
    fun canChangePrivacy(user: User?) = ownsMedia(user, initiatedBy)
    fun canRename(user: User?) = user != null && !user.isShared && user.id.isNotBlank() &&
        (user.role == "admin" || initiatedBy?.id == user.id)
    fun canShare(user: User?) = user != null && !user.isShared && user.id.isNotBlank() &&
        (user.role == "admin" || initiatedBy?.id == user.id || contributors.any { it.id == user.id })
    val active: Boolean get() = status == "queued" || status == "running"
}

@Serializable
data class Library(
    val version: Long = 0,
    val songCount: Int = 0,
    val entries: List<LibraryEntry> = emptyList(),
    val playlists: List<LibraryPlaylist> = emptyList(),
    val jobs: List<Job> = emptyList()
)

@Serializable
data class Track(
    val jobId: String = "", val name: String, val title: String = "", val artist: String = "",
    val album: String = "", val rating: Int = 0, val playlistId: String? = null,
    val playlistTitle: String = "", val streamUrl: String? = null, val downloadUrl: String? = null,
    val isPlayable: Boolean = true, val mediaType: String = "audio", val sizeBytes: Long = 0,
    val noVocalsVersion: Track? = null, val transcription: Transcription? = null,
    val transcriptionLocked: Boolean = false, val artworkUrl: String? = null,
    @SerialName("private") val isPrivate: Boolean = false, val sourceJob: Job? = null
) {
    val key: String get() = ApiJson.encodeToString(listOf(jobId, name))
    val displayTitle: String get() = title.ifBlank { name.substringAfterLast('/').substringBeforeLast('.') }
    val displayArtist: String get() = artist.ifBlank { "Unknown artist" }
}

@Serializable
data class TrackPage(
    val files: List<Track> = emptyList(), val version: Long = 0, val page: Int = 1,
    val pageSize: Int = 50, val total: Int = 0, val totalPages: Int = 1
)

@Serializable
data class LyricLine(val time: Double, val text: String)

@Serializable
data class SongMetadata(
    val title: String = "", val artist: String = "", val album: String = "", val genre: String = "",
    val year: String = "", val rating: Int = 0, val artwork: String? = null,
    val sylt: List<LyricLine> = emptyList(), val uslt: String = "",
    val transcriptionLocked: Boolean = false, val canEdit: Boolean = false
)

@Serializable
data class Job(
    val id: String, val playlistTitle: String = "", val status: String = "", val source: String = "",
    val url: String = "", val error: String? = null, val files: List<String> = emptyList(),
    val initiatedBy: User? = null, val contributors: List<User> = emptyList(), val updatedAt: String = "",
    val isPlaylist: Boolean = true,
    val transcriptions: Map<String, Transcription> = emptyMap(),
    @SerialName("private") val isPrivate: Boolean = false, val privateFiles: List<String>? = null
) {
    val active: Boolean get() = status == "queued" || status == "running"
    fun canModify(user: User) = user.role == "admin" || isMember(user)
    fun isMember(user: User) = initiatedBy?.id == user.id || contributors.any { it.id == user.id }
    fun canChangePrivacy(user: User?) = ownsMedia(user, initiatedBy)
}

private fun ownsMedia(user: User?, owner: User?) =
    user != null && !user.isShared && user.id.isNotBlank() && user.id == owner?.id

data class FilePrivacy(val isPrivate: Boolean, val inherited: Boolean)

fun Track.privacy(job: Job? = sourceJob): FilePrivacy {
    val inherited = job?.isPrivate == true || job?.inheritsCompanionPrivacy(name) == true ||
        (isPrivate && job?.privateFiles != null && name !in job.privateFiles)
    return FilePrivacy(inherited || isPrivate || job?.privateFiles?.contains(name) == true, inherited)
}

private fun Job.inheritsCompanionPrivacy(name: String): Boolean {
    if (!name.lowercase().startsWith("[novocals]/")) return false
    val stem = songStem(name)
    return privateFiles?.any { original ->
        original != name &&
            (transcriptions[original]?.noVocalsName == name || songStem(original) == stem)
    } == true
}

private val songExtension = Regex("""\.[^.]+$""")
private val noVocalsMarker = Regex(
    """(?:\[no[ _-]?vocals\]|[ _-]+no[ _-]?vocals)""", RegexOption.IGNORE_CASE)

private fun songStem(name: String) = name.substringAfterLast('/')
    .replace(songExtension, "").replace(noVocalsMarker, "").trim().lowercase()

@Serializable
data class Transcription(
    val status: String = "", val requestedAt: String? = null, val completedAt: String? = null,
    val lyricsIncluded: Boolean = false, val options: SavedTranscriptionOptions? = null,
    val error: String? = null, val noVocalsName: String? = null
)

@Serializable
data class SavedTranscriptionOptions(
    val language: String = "",
    @SerialName("Multilingual") val multilingual: Boolean? = null,
    @SerialName("NoVocals") val noVocals: Boolean? = null,
    @SerialName("VietLyricsFallback") val vietLyricsFallback: Boolean? = null,
    @SerialName("lyrics_mode") val lyricsMode: String? = null,
    @SerialName("NoVocalsOnly") val noVocalsOnly: Boolean? = null
)

@Serializable
data class Preferences(val theme: String = "green", val mode: String? = null) {
    val effectiveTheme: String get() = if (theme == "light") "green" else theme
}

object ServerResource {
    fun resolve(origin: String, path: String): String {
        val base = URI(AuthProtocol.normalizeOrigin(origin))
        val resource = base.resolve(path)
        require(resource.scheme == base.scheme && resource.rawAuthority == base.rawAuthority &&
            resource.rawUserInfo == null && resource.fragment == null && resource.path.startsWith("/api/") &&
            resource.normalize().path.startsWith("/api/")) {
            "Refused a resource outside the selected server."
        }
        return resource.toASCIIString()
    }
}
