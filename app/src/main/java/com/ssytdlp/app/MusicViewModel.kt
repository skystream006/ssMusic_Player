package com.ssytdlp.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ssytdlp.app.core.*
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class LibraryState(
    val library: Library = Library(), val tracks: TrackPage = TrackPage(),
    val selectedId: String? = null, val search: String = "", val page: Int = 1,
    val loading: Boolean = false
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MusicApplication
    val sessions = app.sessions
    val api = app.api
    val playback = PlaybackConnection(application, api, viewModelScope)
    var library by mutableStateOf(LibraryState())
        private set
    var jobs by mutableStateOf<List<Job>>(emptyList())
        private set
    var preferences by mutableStateOf(Preferences())
        private set
    var waveAppearance by mutableStateOf(application.getSharedPreferences("settings", 0).getBoolean("wave_appearance", true))
        private set
    var metadata by mutableStateOf<SongMetadata?>(null)
        private set
    var metadataError by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var signingIn by mutableStateOf(false)
        private set
    var backup by mutableStateOf<JsonObject?>(null)
        private set
    var health by mutableStateOf<JsonObject?>(null)
        private set
    val serverOrigin = AppServer.origin
    private var trackRequest: CoroutineJob? = null
    private var operation: CoroutineJob? = null

    init {
        viewModelScope.launch {
            sessions.account.collectLatest { account ->
                if (account == null) {
                    operation?.cancel()
                    trackRequest?.cancel()
                    playback.disconnect(stop = true)
                    library = LibraryState()
                    jobs = emptyList()
                    metadata = null
                    backup = null
                    health = null
                } else {
                    playback.connect()
                    runAction {
                        api.request("/api/auth/me")
                        preferences = ApiJson.decodeFromJsonElement(api.request("/api/preferences"))
                        loadLibrary()
                        refreshTracks()
                    }
                }
            }
        }
        viewModelScope.launch {
            playback.state.map { it.track }.distinctUntilChangedBy { it?.key }.collectLatest { track ->
                metadata = null
                metadataError = null
                if (track != null && track.mediaType != "video") {
                    try { metadata = ApiJson.decodeFromJsonElement(api.request(songPath(track, "lyrics"))) }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        metadataError = "Lyrics and artwork are unavailable."
                    }
                }
            }
        }
    }

    fun beginLogin(): String? = try {
        val pending = AppServer.beginLogin()
        sessions.pending = pending
        AuthProtocol.loginUrl(pending)
    } catch (error: Exception) { message(error.message ?: "Unable to start sign-in."); null }

    fun cancelLogin() { sessions.pending = null }

    fun callback(uri: String) {
        if (signingIn) return
        val authorization = try { AppServer.acceptCallback(uri, sessions.pending) }
        catch (error: Exception) { message(error.message ?: "Invalid sign-in callback."); return }
        sessions.pending = null
        signingIn = true
        viewModelScope.launch {
            try {
                val response = ApiJson.decodeFromJsonElement<LoginResponse>(api.exchange(authorization.origin, json(
                    "code" to authorization.code, "codeVerifier" to authorization.verifier, "redirectUri" to AuthProtocol.REDIRECT_URI)))
                require(response.session.tokenType == "Bearer" && Regex("[A-Za-z0-9_-]{43}").matches(response.session.token)) {
                    "The server returned an invalid session."
                }
                require(java.time.Instant.parse(response.session.expiresAt).isAfter(java.time.Instant.now())) {
                    "The server returned an expired session."
                }
                sessions.save(Account(authorization.origin, response.user, response.session))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message(error.message ?: "Sign-in failed. Please try again.")
            } finally { signingIn = false }
        }
    }

    fun logout() {
        if (busy) return
        busy = true
        playback.disconnect(stop = true)
        viewModelScope.launch {
            try { api.request("/api/auth/logout", "POST") }
            catch (_: Exception) { message("Signed out on this device. The server session could not be revoked while offline.") }
            finally { sessions.clear(); busy = false }
        }
    }

    fun message(text: String?) { notice = text }

    fun selectLibrary(entryId: String?) {
        library = library.copy(selectedId = entryId, page = 1, search = "", tracks = TrackPage())
        refreshTracks()
    }

    fun search(value: String) {
        library = library.copy(search = value.take(200), page = 1)
        refreshTracks(debounce = true)
    }

    fun page(value: Int) { library = library.copy(page = value.coerceAtLeast(1)); refreshTracks() }
    fun refresh() = launchAction { loadLibrary(); refreshTracks() }

    fun refreshTracks(debounce: Boolean = false) {
        trackRequest?.cancel()
        trackRequest = viewModelScope.launch {
            library = library.copy(loading = true)
            try {
                if (debounce) delay(300)
                val selection = library
                val query = listOfNotNull("page=${selection.page}", "pageSize=50", "search=${encode(selection.search)}",
                    selection.selectedId?.let { "entryId=${encode(it)}" }).joinToString("&")
                val result = ApiJson.decodeFromJsonElement<TrackPage>(api.request("/api/library/tracks?$query"))
                library = library.copy(tracks = result, page = result.page, library = library.library.copy(version = result.version))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message(error.message ?: "Unable to load music.")
            } finally { library = library.copy(loading = false) }
        }
    }

    private suspend fun loadLibrary() {
        val result = ApiJson.decodeFromJsonElement<Library>(api.request("/api/library"))
        library = library.copy(library = result, selectedId = library.selectedId?.takeIf { id -> result.entries.any { it.id == id } })
    }

    suspend fun pollJobs() { runAction { jobs = ApiJson.decodeFromJsonElement(api.request("/api/jobs")) } }
    suspend fun pollSettings() { runAction { backup = api.request("/api/library/backup").jsonObject; health = api.request("/api/health").jsonObject } }

    fun createJob(url: String, video: Boolean, metadataOnly: Boolean) = launchAction {
        try {
            api.request("/api/jobs", "POST", json("url" to url.trim(), "downloadType" to if (video) "video" else "audio", "metadataOnly" to metadataOnly))
            message("Added to downloads.")
            pollJobs()
            loadLibrary()
            refreshTracks()
        } catch (error: ApiException) {
            if (error.payload?.get("code")?.jsonPrimitive?.content == "JOB_ALREADY_EXISTS") {
                message("This URL already has a job. Open Downloads to view it or rerun it.")
                pollJobs()
            } else throw error
        }
    }

    fun jobAction(job: Job, action: String) = launchAction {
        when (action) {
            "rerun" -> api.request("/api/jobs/${encode(job.id)}/rerun", "POST")
            "delete" -> api.request("/api/jobs/${encode(job.id)}", "DELETE")
            "link" -> api.request("/api/library/links", "POST", json("jobId" to job.id))
        }
        pollJobs()
        loadLibrary()
        refreshTracks()
    }

    fun playJob(job: Job) = launchAction {
        val result = ApiJson.decodeFromJsonElement<TrackPage>(api.request("/api/jobs/${encode(job.id)}/files"))
        val tracks = result.files.filter { it.isPlayable && it.streamUrl != null }.map { it.copy(jobId = job.id, playlistTitle = job.playlistTitle) }
        if (tracks.isEmpty()) message("No playable files are available yet.") else playback.play(tracks)
    }

    fun folder(name: String, entry: LibraryEntry, delete: Boolean = false) = mutate("/api/library/entries", json(
        "action" to if (delete) "delete-folder" else "update-folder",
        "id" to entry.id, "name" to name.trim(), "parentId" to entry.parentId))

    fun createFolder(name: String, parentId: String?) = mutate("/api/library/entries", json(
        "action" to "create-folder", "id" to UUID.randomUUID().toString(), "name" to name.trim(), "parentId" to parentId))

    fun moveEntry(entry: LibraryEntry, parentId: String?) = mutate("/api/library/entries", json(
        "action" to "move", "id" to entry.id, "parentId" to parentId, "targetId" to null, "after" to true))

    fun transfer(track: Track, destination: String, link: Boolean) = mutate("/api/library/songs/transfer", buildJsonObject {
        put("action", if (link) "link" else "move")
        put("sourcePlaylistId", track.playlistId)
        put("playlistId", destination)
        put("keys", buildJsonArray { add(track.key) })
    })

    fun reorder(track: Track, target: Track, after: Boolean) = mutate("/api/library/songs/reorder", json(
        "jobId" to track.jobId, "name" to track.name, "playlistId" to track.playlistId, "target" to target.key, "after" to after))

    fun remove(track: Track) = mutate("/api/library/songs/remove", json(
        "jobId" to track.jobId, "name" to track.name, "playlistId" to track.playlistId))

    private fun mutate(path: String, body: JsonObject) = launchAction {
        try { api.request(path, "POST", JsonObject(body + ("version" to JsonPrimitive(library.library.version)))) }
        catch (error: ApiException) {
            if (error.status == 409) { loadLibrary(); refreshTracks() }
            throw error
        }
        loadLibrary()
        refreshTracks()
    }

    fun saveMetadata(track: Track, value: SongMetadata) = launchAction {
        api.request("/api/jobs/${encode(track.jobId)}/files/${encode(track.name)}/metadata", "PATCH", json(
            "title" to value.title, "artist" to value.artist, "album" to value.album, "genre" to value.genre, "year" to value.year, "rating" to value.rating))
        refreshTracks()
        if (playback.state.value.track?.key == track.key) metadata = ApiJson.decodeFromJsonElement(api.request(songPath(track, "lyrics")))
        message("Song information saved.")
    }

    fun transcribe(track: Track, options: TranscriptionOptions) = launchAction {
        api.request("/api/jobs/${encode(track.jobId)}/files/${encode(track.name)}/transcribe", "POST", options.toRequestBody())
        message("Transcription queued.")
    }

    fun setTheme(theme: String = preferences.theme, mode: String = preferences.mode ?: "light") = launchAction {
        preferences = ApiJson.decodeFromJsonElement(api.request("/api/preferences", "PUT", json("theme" to theme, "mode" to mode)))
    }

    fun chooseWaveAppearance(enabled: Boolean) {
        waveAppearance = enabled
        getApplication<Application>().getSharedPreferences("settings", 0).edit().putBoolean("wave_appearance", enabled).apply()
    }

    fun startBackup(format: String, destination: String) = launchAction {
        backup = api.request("/api/library/backup", "POST", json("format" to format, "destination" to destination)).jsonObject
        message("Backup started.")
    }

    fun backupSchedule(enabled: Boolean, frequency: String, time: String, weekday: Int, format: String, destination: String) = launchAction {
        backup = api.request("/api/library/backup/schedule", "PUT", json(
            "enabled" to enabled, "frequency" to frequency, "time" to time, "weekday" to weekday, "format" to format, "destination" to destination)).jsonObject
        message("Backup schedule saved.")
    }

    fun importFiles(files: List<Uri>, title: String, playlistId: String?, itunes: Boolean = false, onSuccess: () -> Unit) = launchAction {
        val body = buildImportBody(getApplication(), files, title, playlistId, itunes)
        val result = api.upload(body).jsonObject
        message("Imported ${result["importedFiles"]?.jsonPrimitive?.content ?: "0"} files.")
        onSuccess()
        loadLibrary()
        refreshTracks()
    }

    fun importLocal(xmlName: String, zipName: String, onSuccess: () -> Unit) = launchAction {
        val result = api.request("/api/jobs/import", "POST", json("mode" to "itunes", "source" to "local", "xmlName" to xmlName, "zipName" to zipName)).jsonObject
        message("Imported ${result["importedFiles"]?.jsonPrimitive?.content ?: "0"} files.")
        onSuccess()
        loadLibrary()
        refreshTracks()
    }

    fun renameJob(job: Job, title: String) = launchAction {
        api.request("/api/jobs/${encode(job.id)}/title", "PATCH", json("playlistTitle" to title.trim()))
        pollJobs()
        loadLibrary()
        refreshTracks()
    }

    fun saveContributors(job: Job, ids: Set<String>) = launchAction {
        api.request("/api/jobs/${encode(job.id)}/contributors", "PUT", buildJsonObject { put("userIds", buildJsonArray { ids.forEach { add(it) } }) })
        pollJobs()
        loadLibrary()
    }

    fun addJobToPlaylist(job: Job, playlistId: String) = mutate("/api/library/jobs/add", json("jobId" to job.id, "playlistId" to playlistId))

    fun saveDownload(path: String, uri: Uri) = launchAction {
        api.download(path) { input ->
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) { "Cannot open the selected document." }.use { input.copyTo(it) }
        }
        message("Download saved.")
    }

    fun launchAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        operation = viewModelScope.launch { try { runAction(block) } finally { busy = false } }
    }

    fun cancelOperation() {
        operation?.cancel()
        message("Request canceled. Server changes may already have completed; refresh before retrying.")
    }

    private suspend fun runAction(block: suspend () -> Unit) {
        try { block() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            message(when (error) {
                is javax.net.ssl.SSLException -> "The server certificate is not trusted. Install your server CA in Android settings or use a trusted HTTPS certificate."
                else -> error.message ?: "Unable to contact the server."
            })
        }
    }

    override fun onCleared() { playback.disconnect(); super.onCleared() }
}

fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
fun songPath(track: Track, action: String) = "/api/jobs/${encode(track.jobId)}/$action/${encode(track.name)}"
fun json(vararg values: Pair<String, Any?>): JsonObject = buildJsonObject {
    values.forEach { (key, value) ->
        when (value) {
            null -> put(key, JsonNull)
            is Boolean -> put(key, value)
            is Number -> put(key, value)
            else -> put(key, value.toString())
        }
    }
}