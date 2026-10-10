package com.ssytdlp.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.ssytdlp.app.core.*
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class LibraryState(
    val library: Library = Library(), val tracks: TrackPage = TrackPage(),
    val selectedId: String? = null, val search: String = "", val page: Int = 1,
    val loading: Boolean = false,
    val pendingTranscriptions: Map<String, Transcription> = emptyMap(),
    val transcriptions: Map<String, Transcription?> = emptyMap(),
    val ratings: Map<String, Int> = emptyMap(),
    val transcriptionLocks: Map<String, Boolean> = emptyMap(),
    val artworkUrls: Map<String, String?> = emptyMap(),
    val filePrivacy: Map<String, Boolean> = emptyMap(),
    val privacyJobs: Map<String, Job> = emptyMap(),
    private val privacyFileJobs: Map<String, Job> = emptyMap()
) {
    fun withLibrary(result: Library): LibraryState = copy(
        library = result, selectedId = selectedId?.takeIf { id -> result.entries.any { it.id == id } },
        privacyJobs = privacyJobs + result.jobs.associateBy { it.id })

    fun privacyJob(track: Track): Job? =
        privacyJobs[track.jobId] ?: library.jobs.find { it.id == track.jobId } ?: track.sourceJob

    fun privacy(track: Track, job: Job? = privacyJob(track)): FilePrivacy =
        track.copy(isPrivate = filePrivacy[track.key] ?: track.isPrivate).privacy(job)

    fun withPrivacyFiles(files: List<Track>, job: Job? = null): LibraryState {
        val all = files.flatMap { file ->
            val track = if (job == null) file else file.copy(jobId = job.id, sourceJob = job)
            listOfNotNull(track, track.noVocalsVersion?.copy(jobId = track.jobId, sourceJob = track.sourceJob))
        }
        val sources = all.mapNotNull { it.sourceJob }.associateBy { it.id }
        return copy(filePrivacy = filePrivacy + all.associate { it.key to it.isPrivate },
            privacyJobs = privacyJobs + sources,
            privacyFileJobs = privacyFileJobs + all.mapNotNull { track -> track.sourceJob?.let { track.key to it } })
    }

    fun needsPrivacyRefresh(job: Job, tracks: List<Track>): Boolean {
        return job.privateFiles != null && tracks.any { track ->
            val known = privacyFileJobs[track.key]
            track.key !in filePrivacy || known?.updatedAt != job.updatedAt ||
                known?.isPrivate != job.isPrivate || known?.privateFiles != job.privateFiles
        }
    }

    fun transcription(track: Track): Transcription? = pendingTranscriptions[track.key]
        ?: if (transcriptions.containsKey(track.key)) transcriptions[track.key]
        else track.transcription ?: library.jobs.find { it.id == track.jobId }?.transcriptions?.get(track.name)

    fun rating(track: Track): Int = ratings[track.key] ?: track.rating

    fun artworkUrl(track: Track): String? = if (artworkUrls.containsKey(track.key)) artworkUrls[track.key] else track.artworkUrl

    fun transcriptionLocked(track: Track): Boolean = transcriptionLocks[track.key] ?: track.transcriptionLocked

    fun withTrackPage(result: TrackPage): LibraryState = withPrivacyFiles(result.files).copy(
        tracks = result, page = result.page,
        ratings = ratings + result.files.associate { it.key to it.rating },
        artworkUrls = artworkUrls + result.files.associate { it.key to it.artworkUrl },
        transcriptionLocks = transcriptionLocks + result.files.associate { it.key to it.transcriptionLocked },
        transcriptions = transcriptions + result.files.associate { track ->
            track.key to (track.transcription ?: library.jobs.find { it.id == track.jobId }?.transcriptions?.get(track.name))
        })

    fun withTranscriptions(job: Job, tracks: List<Track>): LibraryState = copy(
        transcriptions = transcriptions + tracks.filter { it.jobId == job.id }
            .associate { it.key to job.transcriptions[it.name] })

    fun withReplacedFile(file: Track): LibraryState = copy(
        tracks = tracks.copy(files = tracks.files.map { it.withReplacedFile(file) }),
        ratings = ratings + (file.key to file.rating),
        artworkUrls = artworkUrls + (file.key to file.artworkUrl),
        transcriptionLocks = transcriptionLocks + (file.key to file.transcriptionLocked),
        pendingTranscriptions = pendingTranscriptions - file.key,
        transcriptions = transcriptions + (file.key to null))

    fun withSongMetadata(track: Track, value: SongMetadata): LibraryState {
        fun update(file: Track): Track = if (file.key == track.key) file.copy(
            title = value.title, artist = value.artist, album = value.album,
            rating = value.rating, transcriptionLocked = value.transcriptionLocked)
        else file.copy(noVocalsVersion = file.noVocalsVersion?.let(::update))
        return copy(tracks = tracks.copy(files = tracks.files.map(::update)),
            ratings = ratings + (track.key to value.rating),
            transcriptionLocks = transcriptionLocks + (track.key to value.transcriptionLocked))
    }

    fun withoutMembership(track: Track): LibraryState {
        val files = tracks.files.filterNot { it.key == track.key && it.playlistId == track.playlistId }
        return copy(tracks = tracks.copy(files = files,
            total = (tracks.total - (tracks.files.size - files.size)).coerceAtLeast(0)))
    }
}

internal fun Track.withReplacedFile(file: Track): Track = when {
    key == file.key -> file.copy(playlistId = playlistId, playlistTitle = playlistTitle,
        noVocalsVersion = file.noVocalsVersion ?: noVocalsVersion, transcription = null)
    noVocalsVersion?.key == file.key -> copy(noVocalsVersion = noVocalsVersion?.withReplacedFile(file))
    else -> this
}

internal data class PlaylistSaveResult(val id: String, val error: String? = null)
internal data class PlaylistEditTarget(val id: String, val account: Account)
private data class MetadataSelection(val owner: MetadataOwner, val identity: MetadataIdentity)
private data class MetadataUpdate(val selection: MetadataSelection, val canEdit: Boolean?)
private data class MetadataLoad(val owner: MetadataOwner?, val track: Track?, val generation: Long)
private data class SongReorder(val track: Track, val target: Track, val after: Boolean, val before: LibraryState)
private data class MetadataPrefetch(
    val current: MetadataSelection, val selection: MetadataSelection, val track: Track, val queue: List<Track>,
    val index: Int, val repeat: Int, val generation: Long
)

class MusicViewModel @JvmOverloads constructor(application: Application, private val connectPlayback: Boolean = true) : AndroidViewModel(application) {
    private val app = application as MusicApplication
    val sessions = app.sessions
    val api = app.api
    val playback = PlaybackConnection(application, api, viewModelScope)
    var library by mutableStateOf(LibraryState(selectedId = sessions.account.value?.let(sessions.playback::selectedLibrary)))
        private set
    var jobs by mutableStateOf<List<Job>>(emptyList())
        private set
    var preferences by mutableStateOf(Preferences())
        private set
    var waveAppearance by mutableStateOf(application.getSharedPreferences("settings", 0).getBoolean("wave_appearance", true))
        private set
    var skinsEnabled by mutableStateOf(application.getSharedPreferences("settings", 0).getBoolean("skins_enabled", false))
        private set
    var skin by mutableStateOf(AppSkin.fromPreference(
        application.getSharedPreferences("settings", 0).getString("skin", null)))
        private set
    var edgeLightingEnabled by mutableStateOf(application.getSharedPreferences("settings", 0).getBoolean("edge_lighting", true))
        private set
    var edgeLightingStyle by mutableStateOf(EdgeLightingStyle.fromPreference(
        application.getSharedPreferences("settings", 0).getString("edge_lighting_style", null)))
        private set
    internal var audioVisualizerEnabled by mutableStateOf(
        application.getSharedPreferences("settings", 0).getBoolean("audio_visualizer", false))
        private set
    internal var audioVisualizerStyle by mutableStateOf(AudioVisualizerStyle.fromPreference(
        application.getSharedPreferences("settings", 0).getString("audio_visualizer_style", null)))
        private set
    var lyricsTextScale by mutableFloatStateOf(normalizeLyricsTextScale(
        application.getSharedPreferences("settings", 0).getFloat("lyrics_text_scale", 1f)))
        private set
    private lateinit var ownedMetadata: OwnedMetadataState
    var metadata by OwnedMetadataState { currentMetadataOwner }.also { ownedMetadata = it }
        private set
    var metadataError by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    internal var playlistSaveResult by mutableStateOf<PlaylistSaveResult?>(null)
        private set
    internal var playlistEditTarget by mutableStateOf<PlaylistEditTarget?>(null)
        private set
    private var playlistReloadRequired = false
    var signingIn by mutableStateOf(false)
        private set
    var backup by mutableStateOf<JsonObject?>(null)
        private set
    var health by mutableStateOf<JsonObject?>(null)
        private set
    val transcriptionAvailable: Boolean
        get() = sessions.account.value?.user?.isShared == true || health.transcriptionActive
    val serverOrigin = app.serverConfig.origin
    private var trackRequest: CoroutineJob? = null
    private var operation: CoroutineJob? = null
    private var reorderRequest: CoroutineJob? = null
    private val mutations = Mutex()
    private val songRequests = mutableMapOf<String, CoroutineJob>()
    private val songPreviews = mutableMapOf<String, (LibraryState) -> LibraryState>()
    internal var pendingSongs by mutableStateOf<Set<String>>(emptySet())
        private set
    internal fun songBusy(track: Track): Boolean = busy || track.key in pendingSongs
    private val pendingReorders = ArrayDeque<SongReorder>()
    internal var recoveringSongOrder by mutableStateOf(false)
        private set
    private val accountRefresh = Mutex()
    private val transcriptionPoll = Mutex()
    private var transcriptionGeneration = 0L
    private val metadataCache = SongMetadataCache()
    private val metadataGeneration = MutableStateFlow(0L)
    private val metadataReady = MutableStateFlow<MetadataSelection?>(null)
    private var metadataSelection: MetadataSelection? = null
    private val metadataUpdates = mutableSetOf<MetadataSelection>()
    private val metadataOwners = sessions.account.map { it?.let(::MetadataOwner) }.distinctUntilChanged()
    private val currentMetadataOwner get() = sessions.account.value?.let(::MetadataOwner)

    init {
        viewModelScope.launch {
            sessions.account.distinctUntilChangedBy { account ->
                account?.let { Triple(it.origin, it.user.id, it.session) }
            }.collectLatest { account ->
                reorderRequest?.cancel()
                songRequests.values.toList().forEach { it.cancel() }
                songRequests.clear()
                songPreviews.clear()
                pendingSongs = emptySet()
                pendingReorders.clear()
                recoveringSongOrder = false
                transcriptionGeneration++
                playlistSaveResult = null
                playlistEditTarget = null
                playlistReloadRequired = false
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
                    health = null
                    library = LibraryState(selectedId = sessions.playback.selectedLibrary(account))
                    if (connectPlayback) playback.connect()
                    runAction {
                        if (!refreshAccount()) return@runAction
                        preferences = ApiJson.decodeFromJsonElement(api.request("/api/preferences"))
                        loadLibrary()
                        refreshTracks()
                        refreshHealth()
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(metadataOwners, playback.state.map { it.track }, metadataGeneration, ::MetadataLoad)
                .distinctUntilChangedBy { load ->
                    Triple(load.owner, load.track?.let { MetadataIdentity(it) to it.mediaType }, load.generation)
                }.collectLatest { load ->
                    if (currentMetadataOwner != load.owner) return@collectLatest
                    metadataCache.setOwner(load.owner)
                    val selection = load.owner?.let { owner ->
                        load.track?.takeIf { it.mediaType != "video" }?.let { MetadataSelection(owner, MetadataIdentity(it)) }
                    }
                    if (metadataSelection != selection) {
                        metadataSelection = selection
                        metadataReady.value = null
                        metadata = null
                        metadataError = null
                    }
                    if (selection == null || isMetadataUpdating(selection.owner, selection.identity.key) ||
                        metadataReady.value == selection) return@collectLatest
                    try {
                        val cached = metadataCache.get(selection.owner, selection.identity)
                        val result = cached ?: downloadMetadata(requireNotNull(load.track))
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentMetadata(selection) || metadataGeneration.value != load.generation) return@collectLatest
                        if (cached != null) DebugLog.event(DebugEvent.METADATA_CACHE_HIT)
                        else metadataCache.put(selection.owner, selection.identity, result)
                        ownedMetadata.publish(selection.owner, result)
                        metadataError = null
                        metadataReady.value = selection
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (isCurrentMetadata(selection) && metadataGeneration.value == load.generation) {
                            metadataError = "Lyrics and artwork are unavailable."
                        }
                    }
                }
        }
        viewModelScope.launch {
            combine(playback.state, metadataOwners, app.uiActivity.resumed, metadataReady, metadataGeneration) {
                    state, owner, resumed, ready, generation ->
                val current = state.track
                if (!resumed || state.buffering || state.shuffle || state.repeat == Player.REPEAT_MODE_ONE ||
                    owner == null || current == null || ready == null ||
                    ready != MetadataSelection(owner, MetadataIdentity(current))) null
                else {
                    val nextIndex = playback.controller?.nextMediaItemIndex
                        ?: if (state.index + 1 < state.queue.size) state.index + 1
                        else if (state.repeat == Player.REPEAT_MODE_ALL) 0 else -1
                    val next = state.queue.getOrNull(nextIndex)
                    if (next == null || next.mediaType == "video" || MetadataIdentity(next) == ready.identity ||
                        isMetadataUpdating(owner, next.key) || state.queue.getOrNull(state.index)?.key != current.key) null
                    else MetadataPrefetch(ready, MetadataSelection(owner, MetadataIdentity(next)), next, state.queue,
                        state.index, state.repeat, generation)
                }
            }.distinctUntilChanged().collectLatest { request ->
                if (request == null) return@collectLatest
                // Give playback and the current song's visible work priority; never walk the queue.
                delay(350)
                if (!canPrefetch(request) || metadataCache.get(request.selection.owner, request.selection.identity) != null) {
                    return@collectLatest
                }
                try {
                    val result = app.uiActivity.onceWhileResumed {
                        if (!canPrefetch(request)) throw CancellationException()
                        downloadMetadata(request.track)
                    }
                    currentCoroutineContext().ensureActive()
                    if (canPrefetch(request)) metadataCache.put(request.selection.owner, request.selection.identity, result)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Speculative failures are neither cached nor shown as current-song failures.
                }
            }
        }
    }

    private suspend fun downloadMetadata(track: Track): SongMetadata = convertMetadata(api.request(songPath(track, "lyrics")))

    private suspend fun convertMetadata(value: JsonElement): SongMetadata = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        try { ApiJson.decodeFromJsonElement<SongMetadata>(value) }
        finally { DebugLog.timing(DebugEvent.METADATA_CONVERTED, started) }
    }

    private fun isCurrentMetadata(selection: MetadataSelection): Boolean =
        currentMetadataOwner == selection.owner &&
            playback.state.value.track?.takeIf { it.mediaType != "video" }?.let(::MetadataIdentity) == selection.identity

    private fun canPrefetch(request: MetadataPrefetch): Boolean {
        val state = playback.state.value
        return currentMetadataOwner == request.selection.owner && app.uiActivity.resumed.value &&
            !state.buffering && !state.shuffle && state.repeat == request.repeat && state.index == request.index &&
            state.queue == request.queue && metadataGeneration.value == request.generation &&
            metadataReady.value == request.current && isCurrentMetadata(request.current)
    }

    private fun invalidateMetadata(key: String) {
        metadataCache.invalidate(key)
        if (metadataSelection?.identity?.key == key) metadataReady.value = null
        metadataGeneration.value++
    }

    private fun isMetadataUpdating(owner: MetadataOwner, key: String): Boolean =
        metadataUpdates.any { it.owner == owner && it.identity.key == key }

    private fun beginMetadataUpdate(track: Track): MetadataUpdate {
        val owner = requireNotNull(currentMetadataOwner)
        metadataCache.setOwner(owner)
        val selection = MetadataSelection(owner, MetadataIdentity(track))
        val known = if (metadataReady.value == selection) metadata else metadataCache.get(owner, selection.identity)
        metadataUpdates.add(selection)
        invalidateMetadata(track.key)
        return MetadataUpdate(selection, known?.canEdit)
    }

    private fun finishMetadataUpdate(update: MetadataUpdate, value: SongMetadata? = null, reload: Boolean = false) {
        val selection = update.selection
        metadataUpdates.remove(selection)
        if (currentMetadataOwner == selection.owner) {
            metadataCache.invalidate(selection.identity.key)
            val result = value?.copy(canEdit = update.canEdit == true)
            if (result != null && update.canEdit != null) {
                metadataCache.put(selection.owner, selection.identity, result)
            }
            if (isCurrentMetadata(selection)) {
                if (result != null) {
                    ownedMetadata.publish(selection.owner, result)
                    metadataSelection = selection
                    metadataError = null
                }
                metadataReady.value = if (!reload && update.canEdit != null &&
                    metadataSelection == selection && metadata != null) selection else null
            }
        }
        metadataGeneration.value++
    }

    fun setServerOrigin(input: String): String? = try {
        app.serverConfig.set(input)
        null
    } catch (error: Exception) { error.message ?: "Enter a valid server address." }

    fun changeServer() {
        if (signingIn) return
        sessions.pending = null
        app.serverConfig.clear()
    }

    fun beginLogin(): String? {
        val origin = serverOrigin.value ?: return null
        return try {
            val pending = AppServer.beginLogin(origin)
            sessions.pending = pending
            AuthProtocol.loginUrl(pending)
        } catch (error: Exception) { message(error.message ?: "Unable to start sign-in."); null }
    }

    fun cancelLogin() { sessions.pending = null }

    fun callback(uri: String) {
        if (signingIn) return
        val authorization = try {
            val origin = requireNotNull(serverOrigin.value) { "Configure the server before signing in." }
            AppServer.acceptCallback(uri, sessions.pending, origin)
        }
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
        sessions.account.value?.let { sessions.playback.saveSelectedLibrary(it, entryId) }
        refreshTracks()
    }

    fun search(value: String) {
        library = library.copy(search = value.take(200), page = 1)
        refreshTracks(debounce = true)
    }

    fun page(value: Int) { library = library.copy(page = value.coerceAtLeast(1)); refreshTracks() }
    fun refresh() = launchAction { loadLibrary(); refreshTracks(); refreshHealth() }

    fun refreshTracks(debounce: Boolean = false, background: Boolean = false) {
        val showLoading = !background || library.loading
        trackRequest?.cancel()
        trackRequest = viewModelScope.launch {
            if (showLoading) library = library.copy(loading = true)
            try {
                if (debounce) delay(300)
                do {
                    reorderRequest?.join()
                    val generation = transcriptionGeneration
                    val result = api.trackPage(library)
                    if (generation == transcriptionGeneration && reorderRequest?.isActive != true) {
                        acceptTrackPage(result)
                        break
                    }
                } while (true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message(error.message ?: "Unable to load music.")
            } finally { library = library.copy(loading = false) }
        }
    }

    private suspend fun loadLibrary() {
        val account = sessions.account.value ?: return
        val result = ApiJson.decodeFromJsonElement<Library>(api.request("/api/library"))
        if (sessions.account.value != account) return
        library = library.withLibrary(result)
        sessions.playback.saveSelectedLibrary(account, library.selectedId)
    }

    private fun acceptTrackPage(result: TrackPage) {
        val before = library
        library = library.withTrackPage(result)
        songPreviews.values.forEach { library = it(library) }
        result.files.forEach { track ->
            val previous = before.tracks.files.find { it.key == track.key }
            if (before.transcriptions.containsKey(track.key) &&
                before.transcriptions[track.key] != library.transcriptions[track.key] ||
                before.artworkUrls.containsKey(track.key) && before.artworkUrls[track.key] != track.artworkUrl ||
                previous != null && MetadataIdentity(previous) != MetadataIdentity(track)) {
                invalidateMetadata(track.key)
            }
        }
    }

    private fun acceptTranscriptions(job: Job, tracks: List<Track>) {
        val before = library
        library = library.withTranscriptions(job, tracks)
        tracks.filter { it.jobId == job.id }.forEach { track ->
            if (before.transcription(track) != library.transcription(track)) invalidateMetadata(track.key)
        }
    }

    suspend fun pollJobs() { runAction { jobs = ApiJson.decodeFromJsonElement(api.request("/api/jobs")) } }
    suspend fun pollTranscriptions(extraTracks: List<Track> = emptyList()) {
        val account = sessions.account.value ?: return
        transcriptionPoll.withLock {
            if (sessions.account.value != account) return@withLock
            val generation = transcriptionGeneration
            val selection = library
            val request = trackRequest
            var refreshedKeys = emptySet<String>()
            if (!selection.loading && reorderRequest?.isActive != true && pendingSongs.isEmpty()) runAction {
                val result = api.trackPage(selection)
                if (sessions.account.value == account && generation == transcriptionGeneration &&
                    trackRequest === request) {
                    acceptTrackPage(result)
                    refreshedKeys = result.files.map { it.key }.toSet()
                }
            }
            val queued = (playback.state.value.queue + extraTracks).distinctBy { it.key }
                .filter { it.key !in refreshedKeys }
            for ((jobId, tracks) in queued.groupBy { it.jobId }) {
                if (sessions.account.value != account || generation != transcriptionGeneration) return@withLock
                runAction {
                    val job = ApiJson.decodeFromJsonElement<Job>(api.request("/api/jobs/${encode(jobId)}"))
                    val privacyFiles = if (library.needsPrivacyRefresh(job, tracks)) {
                        ApiJson.decodeFromJsonElement<TrackPage>(api.request("/api/jobs/${encode(jobId)}/files"))
                    } else null
                    if (sessions.account.value == account && generation == transcriptionGeneration) {
                        acceptTranscriptions(job, tracks)
                        if (privacyFiles != null) library = library.withPrivacyFiles(privacyFiles.files, job)
                    }
                }
            }
        }
    }
    private suspend fun refreshAccount(): Boolean = accountRefresh.withLock {
        val account = sessions.account.value ?: return@withLock false
        val response = ApiJson.decodeFromJsonElement<UserResponse>(api.request("/api/auth/me"))
        sessions.updateUser(account, response.user)
    }

    suspend fun pollSettings() { runAction {
        if (!refreshAccount()) return@runAction
        if (sessions.account.value?.user?.isShared == true) {
            backup = null
            health = null
            return@runAction
        }
        backup = api.request("/api/library/backup").jsonObject
        refreshHealth()
    } }

    private suspend fun refreshHealth() {
        val account = sessions.account.value ?: return
        health = null
        if (account.user.isShared) return
        try {
            val result = api.request("/api/health").jsonObject
            if (sessions.account.value == account) health = result
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Missing or unreachable health data must not enable transcription.
        }
    }

    fun createJob(url: String, video: Boolean, metadataOnly: Boolean) = launchAction {
        try {
            api.request("/api/jobs", "POST", json("url" to url.trim(), "downloadType" to if (video) "video" else "audio", "metadataOnly" to metadataOnly))
            message("Added to downloads.")
            pollJobs()
            loadLibrary()
            refreshTracks()
        } catch (error: ApiException) {
            if (error.payload?.get("code")?.jsonPrimitive?.content == "JOB_ALREADY_EXISTS") {
                message("This URL already has a job. Open Jobs to view it or rerun it.")
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

    fun transfer(track: Track, destination: String, link: Boolean) = mutateSong(track, "/api/library/songs/transfer", buildJsonObject {
        put("action", if (link) "link" else "move")
        put("sourcePlaylistId", track.playlistId)
        put("playlistId", destination)
        put("keys", buildJsonArray { add(track.key) })
    })

    fun reorder(track: Track, target: Track, after: Boolean) {
        val account = sessions.account.value ?: return
        val before = library
        if (busy || pendingSongs.isNotEmpty() || recoveringSongOrder || account.user.isShared || before.loading || before.search.isNotEmpty() ||
            before.selectedId == null || track.playlistId != before.selectedId || target.playlistId != before.selectedId ||
            track.name.startsWith("[NoVocals]/", true) != target.name.startsWith("[NoVocals]/", true)) return
        val files = before.tracks.files.toMutableList()
        val from = files.indexOfFirst { it.key == track.key }
        if (from < 0 || track.key == target.key) return
        val moved = files.removeAt(from)
        val to = files.indexOfFirst { it.key == target.key }
        if (to < 0) return
        files.add(to + if (after) 1 else 0, moved)
        if (files == before.tracks.files) return
        pendingReorders.addLast(SongReorder(moved, target, after, before))
        transcriptionGeneration++
        library = before.copy(tracks = before.tracks.copy(files = files))
        if (reorderRequest?.isActive == true) return
        reorderRequest = viewModelScope.launch {
            mutations.withLock { try {
                while (pendingReorders.isNotEmpty() && sameAccount(account)) {
                    val move = pendingReorders.first()
                    try {
                        val result = api.request("/api/library/songs/reorder", "POST", json(
                            "jobId" to move.track.jobId, "name" to move.track.name, "playlistId" to move.track.playlistId,
                            "target" to move.target.key, "after" to move.after, "version" to library.library.version))
                        if (!sameAccount(account)) return@launch
                        val version = (result as? JsonObject)?.get("version")?.jsonPrimitive?.longOrNull
                        if (version != null) library = library.copy(library = library.library.copy(version = version))
                        else loadLibrary()
                        pendingReorders.removeFirst()
                        transcriptionGeneration++
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (!sameAccount(account)) return@launch
                        pendingReorders.clear()
                        recoveringSongOrder = true
                        transcriptionGeneration++
                        val previous = move.before
                        if (library.selectedId == previous.selectedId && library.search == previous.search &&
                            library.page == previous.page) {
                            val positions = previous.tracks.files.mapIndexed { index, file -> file.key to index }.toMap()
                            library = library.copy(tracks = library.tracks.copy(
                                files = library.tracks.files.sortedBy { positions[it.key] ?: Int.MAX_VALUE }))
                        }
                        message("Unable to save song order: ${error.message ?: "Unable to contact the server."}")
                        // A conflict or lost response may have changed the server order. Reconcile without a loading UI.
                        try {
                            loadLibrary()
                            val selection = library
                            val request = trackRequest
                            val result = api.trackPage(selection)
                            if (sameAccount(account) && trackRequest === request) acceptTrackPage(result)
                        } catch (refreshError: Exception) {
                            if (refreshError is CancellationException) throw refreshError
                        }
                        return@launch
                    }
                }
            } finally {
                if (sameAccount(account)) recoveringSongOrder = false
            } }
        }
    }

    fun remove(track: Track) = mutateSong(track, "/api/library/songs/remove", json(
        "jobId" to track.jobId, "name" to track.name, "playlistId" to track.playlistId)) { result ->
        message(if ((result as? JsonObject)?.get("fileDeleted")?.jsonPrimitive?.booleanOrNull == true)
            "Song deleted: ${track.displayTitle} after removing the last playlist link."
        else "Removed ${track.displayTitle} from the playlist.")
    }

    fun privacyJob(track: Track): Job? = library.privacyJob(track) ?: jobs.find { it.id == track.jobId }

    fun songPrivacy(track: Track): FilePrivacy = library.privacy(track, privacyJob(track))

    fun setSongPrivate(track: Track, value: Boolean) = launchSongAction(track, validate = {
        require(privacyJob(track)?.let { job ->
            job.canChangePrivacy(sessions.account.value?.user) && !job.active
        } == true) { "Only the owner of an idle song can change its privacy." }
        require(!songPrivacy(track).inherited) { "Change privacy on the source playlist or original song." }
    }, preview = {
        it.copy(filePrivacy = it.filePrivacy + (track.key to value))
    }) {
        val job = privacyJob(track)
        require(job?.canChangePrivacy(sessions.account.value?.user) == true && !job.active) {
            "Only the owner of an idle song can change its privacy."
        }
        transcriptionGeneration++
        val updated = ApiJson.decodeFromJsonElement<Job>(api.request(
            "/api/jobs/${encode(track.jobId)}/files/${encode(track.name)}/privacy", "PATCH", json("private" to value)))
        library = library.copy(privacyJobs = library.privacyJobs + (updated.id to updated),
            filePrivacy = library.filePrivacy + (track.key to value))
        jobs = jobs.map { if (it.id == updated.id) updated else it }
        commitSongChange(track)
        refreshPrivacyFiles(updated)
        loadLibrary()
        message(when {
            value -> "Song is private. Only the owner can access it."
            songPrivacy(track).isPrivate -> "Song remains private because it inherits source privacy."
            else -> "Song is no longer private."
        })
    }

    fun setPlaylistPrivate(playlist: LibraryPlaylist, value: Boolean) = launchAction {
        val current = library.library.playlists.find { it.id == playlist.id }
        require(current?.canChangePrivacy(sessions.account.value?.user) == true && !current.active) {
            "Only the owner of an idle playlist can change its privacy."
        }
        updatePlaylistPrivacy(current, value)
        refreshTracks()
        message(if (value) "Playlist is private. Only the owner can access it." else "Playlist is no longer private.")
    }

    private suspend fun updatePlaylistPrivacy(playlist: LibraryPlaylist, value: Boolean) {
        transcriptionGeneration++
        trackRequest?.cancel()
        val updated = ApiJson.decodeFromJsonElement<Library>(api.request(
            "/api/library/playlists/${encode(playlist.id)}/privacy", "PATCH", json("private" to value)))
        library = library.withLibrary(updated)
        updated.jobs.find { it.id == playlist.jobId }?.let { job ->
            jobs = jobs.map { if (it.id == job.id) job else it }
            refreshPrivacyFiles(job)
        }
    }

    internal fun openPlaylistEditor(id: String) {
        val account = sessions.account.value ?: return
        if (busy || account.user.isShared) return
        playlistSaveResult = null
        playlistEditTarget = PlaylistEditTarget(id, account)
    }

    internal fun closePlaylistEditor() { playlistEditTarget = null }

    fun savePlaylist(id: String, title: String, isPrivate: Boolean, parentId: String?,
        onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}, move: Boolean = false) = launchAction {
        val account = sessions.account.value
        playlistSaveResult = null
        try {
            val user = account?.user
            require(user != null && !user.isShared && user.id.isNotBlank()) { "This library is read-only." }
            if (playlistReloadRequired) {
                loadLibrary()
                require(sessions.account.value == account) { "Account changed. Reopen the playlist editor." }
                playlistReloadRequired = false
            }
            val entry = library.library.entries.find { it.id == id && it.type == "playlist" }
            val playlist = library.library.playlists.find { it.id == id }
            require(entry != null && playlist != null) { "Playlist not found." }
            require(!playlist.active) { "Wait for the playlist's active job to finish." }
            if (!entry.protected && playlist.canRename(user)) {
                val name = title.trim()
                require(name.length in 1..200 && name.none { it < ' ' || it == '\u007f' }) {
                    "Playlist title must be between 1 and 200 characters without control characters."
                }
                if (name != playlist.playlistTitle) {
                    val job = ApiJson.decodeFromJsonElement<Job>(api.request(
                        "/api/jobs/${encode(playlist.jobId ?: id)}/title", "PATCH", json("playlistTitle" to name)))
                    jobs = jobs.map { if (it.id == job.id) job else it }
                }
            }
            if (playlist.canChangePrivacy(user) && isPrivate != playlist.isPrivate) {
                updatePlaylistPrivacy(playlist, isPrivate)
            }
            loadLibrary()
            require(sessions.account.value == account) { "Account changed. Reopen the playlist editor." }
            val current = library.library.entries.find { it.id == id && it.type == "playlist" }
            require(current != null) { "Playlist not found." }
            if (move && parentId != current.parentId) {
                require(parentId == null || library.library.entries.any { it.id == parentId && it.type == "folder" }) {
                    "Destination folder not found."
                }
                val moved = ApiJson.decodeFromJsonElement<Library>(api.request("/api/library/entries", "POST", json(
                    "version" to library.library.version, "action" to "move", "id" to id,
                    "parentId" to parentId, "targetId" to null, "after" to false)))
                library = library.withLibrary(library.library.copy(version = moved.version, entries = moved.entries))
            }
            refreshTracks()
            playlistSaveResult = PlaylistSaveResult(id)
            onSuccess()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // Separate server mutations can partially succeed. Reload before allowing a retry.
            if (sessions.account.value == account) {
                playlistReloadRequired = true
                try {
                    loadLibrary()
                    if (sessions.account.value == account) playlistReloadRequired = false
                    refreshTracks()
                }
                catch (refreshFailure: Exception) { if (refreshFailure is CancellationException) throw refreshFailure }
            }
            val error = "Unable to save all playlist changes: ${failure.message ?: "Unable to contact the server."}"
            if (sessions.account.value == account) playlistSaveResult = PlaylistSaveResult(id, error)
            onError(error)
        }
    }

    private suspend fun refreshPrivacyFiles(job: Job) {
        val result = ApiJson.decodeFromJsonElement<TrackPage>(api.request("/api/jobs/${encode(job.id)}/files"))
        transcriptionGeneration++
        library = library.withPrivacyFiles(result.files, job)
    }

    private fun mutate(path: String, body: JsonObject, onSuccess: (JsonElement) -> Unit = {}) = launchAction {
        val result = try { api.request(path, "POST", JsonObject(body + ("version" to JsonPrimitive(library.library.version)))) }
        catch (error: ApiException) {
            if (error.status == 409) { loadLibrary(); refreshTracks() }
            throw error
        }
        onSuccess(result)
        loadLibrary()
        refreshTracks()
    }

    private fun mutateSong(track: Track, path: String, body: JsonObject,
        onSuccess: (JsonElement) -> Unit = {}) {
        val removesMembership = path.endsWith("/remove") || body["action"]?.jsonPrimitive?.content == "move"
        launchSongAction(track, preview = if (removesMembership) ({ it.withoutMembership(track) }) else null,
            rollback = { current, before ->
                if (current.selectedId != before.selectedId || current.search != before.search ||
                    current.page != before.page) current
                else {
                    val index = before.tracks.files.indexOfFirst { it.key == track.key && it.playlistId == track.playlistId }
                    if (index < 0 || current.tracks.files.any { it.key == track.key }) current
                    else current.copy(tracks = current.tracks.copy(
                        files = current.tracks.files.toMutableList().apply {
                            add(index.coerceAtMost(size), before.tracks.files[index])
                        }, total = current.tracks.total + (before.tracks.total -
                            before.withoutMembership(track).tracks.total)))
                }
            }) {
            val result = try {
                api.request(path, "POST", JsonObject(body + ("version" to JsonPrimitive(library.library.version))))
            } catch (error: ApiException) {
                if (error.status == 409) {
                    loadLibrary()
                    refreshTracks(background = true)
                }
                throw error
            }
            commitSongChange(track)
            onSuccess(result)
            loadLibrary()
            refreshTracks(background = true)
        }
    }

    fun saveRating(track: Track, rating: Int) = launchSongAction(track, preview = {
        require(rating in 0..5) { "Rating must be between 0 and 5." }
        it.copy(ratings = it.ratings + (track.key to rating))
    }) {
        require(rating in 0..5) { "Rating must be between 0 and 5." }
        patchMetadata(track, json("rating" to rating))
        message("Rating saved.")
    }

    fun saveMetadata(track: Track, value: SongMetadata, transcriptionLocked: Boolean? = null) = launchSongAction(track,
        preview = { it.withSongMetadata(track, value.copy(
            transcriptionLocked = transcriptionLocked ?: it.transcriptionLocked(track))) }) {
        val body = json("title" to value.title, "artist" to value.artist, "album" to value.album,
            "genre" to value.genre, "year" to value.year, "rating" to value.rating)
        patchMetadata(track, if (transcriptionLocked == null) body
            else JsonObject(body + ("transcriptionLocked" to JsonPrimitive(transcriptionLocked))))
        message("Song information saved.")
    }

    private suspend fun patchMetadata(track: Track, body: JsonObject): SongMetadata {
        val update = beginMetadataUpdate(track)
        var updated: SongMetadata? = null
        var reload = false
        try {
            val result = convertMetadata(api.request(
                "/api/jobs/${encode(track.jobId)}/files/${encode(track.name)}/metadata", "PATCH", body))
            require(currentMetadataOwner == update.selection.owner) { "Account changed. Refresh before retrying." }
            updated = result
            library = library.withSongMetadata(track, result)
            commitSongChange(track)
            return result
        } catch (error: Exception) {
            reload = error !is ApiException || error.status !in 400..499
            throw error
        } finally { finishMetadataUpdate(update, updated, reload) }
    }

    fun lockTranscription(track: Track, locked: Boolean) = launchSongAction(track, preview = {
        it.copy(transcriptionLocks = it.transcriptionLocks + (track.key to locked))
    }) {
        patchMetadata(track, json("transcriptionLocked" to locked))
        message(if (locked) "Transcription locked for ${track.displayTitle}." else "Transcription unlocked for ${track.displayTitle}.")
    }

    fun saveLyrics(track: Track, body: JsonObject, onSuccess: () -> Unit) = launchSongAction(track) {
        require(body.isNotEmpty() && body.keys.all { it == "sylt" || it == "uslt" }) { "No lyric changes to save." }
        patchMetadata(track, body)
        message("Lyrics saved.")
        onSuccess()
    }

    fun replaceFile(track: Track, uri: Uri, onSuccess: () -> Unit) = launchSongAction(track) {
        val account = sessions.account.value
        require(canReplaceFile(account?.user,
            library.library.jobs.find { it.id == track.jobId } ?: jobs.find { it.id == track.jobId }, track)) {
            "You do not have permission to replace this song, or its job is active."
        }
        val body = buildReplacementBody(getApplication(), track, uri)
        require(sessions.account.value == account) { "The account changed. Choose the file again." }
        val update = beginMetadataUpdate(track)
        var reload = false
        try {
            val result = api.upload(body, "/api/jobs/${encode(track.jobId)}/files/${encode(track.name)}/replace").jsonObject
            val song = convertMetadata(result.getValue("metadata"))
            val file = ApiJson.decodeFromJsonElement<Track>(result.getValue("file")).copy(
                jobId = track.jobId, transcription = null, transcriptionLocked = song.transcriptionLocked)
            require(sessions.account.value == account) { "Account changed. Refresh before retrying." }
            require(file.name == track.name) { "The server returned a different song. Refresh before retrying." }
            transcriptionGeneration++
            trackRequest?.cancel()
            library = library.withReplacedFile(file)
            playback.replaceFile(file)
            reload = true
        } catch (error: Exception) {
            reload = error !is ApiException || error.status !in 400..499
            throw error
        } finally { finishMetadataUpdate(update, reload = reload) }
        message("Replaced ${track.displayTitle}.")
        onSuccess()
        loadLibrary()
        refreshTracks(background = true)
    }

    fun transcribe(track: Track, options: TranscriptionOptions) = launchSongAction(track, allowShared = true) {
        require(options.noVocalsOnly || !library.transcriptionLocked(track)) { "Transcription is locked for this song." }
        require(transcriptionAvailable) { INACTIVE_TRANSCRIPTION_MESSAGE }
        val body = options.toRequestBody()
        val account = sessions.account.value
        val update = beginMetadataUpdate(track)
        transcriptionGeneration++
        val pending = Transcription(status = "sent", requestedAt = java.time.Instant.now().toString(),
            lyricsIncluded = options.addLyrics && !options.noVocalsOnly,
            options = ApiJson.decodeFromJsonElement<SavedTranscriptionOptions>(body))
        library = library.copy(pendingTranscriptions = library.pendingTranscriptions + (track.key to pending))
        try {
            val job = ApiJson.decodeFromJsonElement<Job>(
                api.request("/api/jobs/${encode(track.jobId)}/files/${encode(track.name)}/transcribe", "POST", body))
            transcriptionGeneration++
            require(sessions.account.value == account) { "Account changed. Refresh before retrying." }
            acceptTranscriptions(job, listOf(track))
            message(if (options.noVocalsOnly) "NoVocals version generated." else "Transcription complete.")
            refreshTracks(background = true)
        } finally {
            finishMetadataUpdate(update, reload = true)
            track.noVocalsVersion?.let { invalidateMetadata(it.key) }
            transcriptionGeneration++
            if (sameAccount(account)) library = library.copy(pendingTranscriptions = library.pendingTranscriptions - track.key)
            if (account != null && sessions.account.value == account) viewModelScope.launch {
                if (sessions.account.value == account) pollTranscriptions(listOf(track))
            }
        }
    }

    fun setTheme(theme: String = preferences.effectiveTheme, mode: String = preferences.mode ?: "light") = launchAction {
        val effectiveTheme = Preferences(theme = theme).effectiveTheme
        preferences = ApiJson.decodeFromJsonElement(api.request("/api/preferences", "PUT", json("theme" to effectiveTheme, "mode" to mode)))
    }

    fun chooseWaveAppearance(enabled: Boolean) {
        waveAppearance = enabled
        getApplication<Application>().getSharedPreferences("settings", 0).edit().putBoolean("wave_appearance", enabled).apply()
    }

    fun chooseSkins(enabled: Boolean) {
        skinsEnabled = enabled
        getApplication<Application>().getSharedPreferences("settings", 0).edit().putBoolean("skins_enabled", enabled).apply()
    }

    fun chooseSkin(value: AppSkin) {
        skin = value
        getApplication<Application>().getSharedPreferences("settings", 0).edit().putString("skin", value.name).apply()
    }

    fun chooseEdgeLighting(enabled: Boolean) {
        edgeLightingEnabled = enabled
        getApplication<Application>().getSharedPreferences("settings", 0).edit().putBoolean("edge_lighting", enabled).apply()
    }

    fun chooseEdgeLightingStyle(style: EdgeLightingStyle) {
        edgeLightingStyle = style
        getApplication<Application>().getSharedPreferences("settings", 0).edit().putString("edge_lighting_style", style.name).apply()
    }

    internal fun chooseAudioVisualizer(enabled: Boolean) {
        audioVisualizerEnabled = enabled
        getApplication<Application>().getSharedPreferences("settings", 0).edit().putBoolean("audio_visualizer", enabled).apply()
    }

    internal fun chooseAudioVisualizerStyle(style: AudioVisualizerStyle) {
        audioVisualizerStyle = style
        getApplication<Application>().getSharedPreferences("settings", 0).edit().putString("audio_visualizer_style", style.name).apply()
    }

    fun zoomLyrics(zoomChange: Float) {
        if (!zoomChange.isFinite() || zoomChange <= 0f) return
        val scale = (lyricsTextScale * zoomChange).coerceIn(MIN_LYRICS_TEXT_SCALE, MAX_LYRICS_TEXT_SCALE)
        if (scale == lyricsTextScale) return
        lyricsTextScale = scale
        getApplication<Application>().getSharedPreferences("settings", 0).edit().putFloat("lyrics_text_scale", scale).apply()
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

    fun saveDownload(path: String, uri: Uri) = launchSongAction(
        Track(name = path), allowShared = true) {
        api.download(path) { input ->
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) { "Cannot open the selected document." }.use { input.copyTo(it) }
        }
        message("Download saved.")
    }

    fun launchAction(block: suspend () -> Unit) {
        if (busy) return
        val account = sessions.account.value
        busy = true
        operation = viewModelScope.launch {
            try {
                mutations.withLock { if (sameAccount(account)) runAction(block) }
            } finally { busy = false }
        }
    }

    private fun launchSongAction(track: Track, allowShared: Boolean = false,
        validate: () -> Unit = {},
        preview: ((LibraryState) -> LibraryState)? = null,
        rollback: ((LibraryState, LibraryState) -> LibraryState)? = null, block: suspend () -> Unit) {
        val account = sessions.account.value ?: return
        if (songBusy(track)) return
        if (!allowShared && account.user.isShared) { message("This library is read-only."); return }
        val before = library
        try { validate() }
        catch (error: IllegalArgumentException) { message(error.message ?: "Invalid song change."); return }
        if (preview != null) {
            try { library = preview(library) }
            catch (error: IllegalArgumentException) { message(error.message ?: "Invalid song change."); return }
            songPreviews[track.key] = preview
        }
        pendingSongs = pendingSongs + track.key
        transcriptionGeneration++
        val request = viewModelScope.launch {
            try {
                mutations.withLock {
                    if (!sameAccount(account)) return@withLock
                    runAction {
                        try {
                            require(allowShared || sessions.account.value?.user?.isShared == false) {
                                "This library is read-only."
                            }
                            block()
                        }
                        catch (error: Exception) {
                            if (sameAccount(account) && track.key in songPreviews && rollback != null) {
                                library = rollback(library, before)
                            } else if (sameAccount(account) && track.key in songPreviews) {
                                val previous = before.tracks.files.find { it.key == track.key } ?: track
                                fun restore(file: Track): Track = if (file.key == track.key) file.copy(
                                    title = previous.title, artist = previous.artist, album = previous.album,
                                    rating = previous.rating, transcriptionLocked = previous.transcriptionLocked)
                                else file.copy(noVocalsVersion = file.noVocalsVersion?.let(::restore))
                                library = library.copy(
                                    tracks = library.tracks.copy(files = library.tracks.files.map(::restore)),
                                    ratings = library.ratings - track.key + before.ratings.filterKeys { it == track.key },
                                    transcriptionLocks = library.transcriptionLocks - track.key +
                                        before.transcriptionLocks.filterKeys { it == track.key },
                                    filePrivacy = library.filePrivacy - track.key + before.filePrivacy.filterKeys { it == track.key })
                            }
                            throw error
                        }
                    }
                }
            } finally {
                if (sameAccount(account)) {
                    songPreviews.remove(track.key)
                    pendingSongs = pendingSongs - track.key
                    songRequests.remove(track.key)
                    transcriptionGeneration++
                }
            }
        }
        if (request.isActive) songRequests[track.key] = request
    }

    private fun commitSongChange(track: Track) {
        songPreviews.remove(track.key)
        transcriptionGeneration++
    }

    private fun sameAccount(account: Account?): Boolean {
        val current = sessions.account.value
        return current?.origin == account?.origin && current?.user?.id == account?.user?.id &&
            current?.session == account?.session
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

    override fun onCleared() { metadataCache.setOwner(null); playback.disconnect(); super.onCleared() }
}

internal const val MIN_LYRICS_TEXT_SCALE = 0.75f
internal const val MAX_LYRICS_TEXT_SCALE = 2f

internal fun normalizeLyricsTextScale(scale: Float): Float =
    if (scale.isFinite()) scale.coerceIn(MIN_LYRICS_TEXT_SCALE, MAX_LYRICS_TEXT_SCALE) else 1f

internal const val INACTIVE_TRANSCRIPTION_MESSAGE =
    "Transciption service is currently inactive. Refresh the app when transcription service is available"

internal val JsonObject?.transcriptionActive: Boolean
    get() = ((this?.get("transcription") as? JsonObject)?.get("status") as? JsonPrimitive)?.content == "active"

internal suspend fun ServerApi.trackPage(selection: LibraryState): TrackPage {
    val query = listOfNotNull("page=${selection.page}", "pageSize=50", "search=${encode(selection.search)}",
        selection.selectedId?.let { "entryId=${encode(it)}" }).joinToString("&")
    return ApiJson.decodeFromJsonElement(request("/api/library/tracks?$query"))
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