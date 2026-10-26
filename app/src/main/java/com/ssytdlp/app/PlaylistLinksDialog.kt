package com.ssytdlp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.AuthProtocol
import com.ssytdlp.app.core.PlaylistLink
import com.ssytdlp.app.core.PlaylistLinks
import com.ssytdlp.app.core.Track
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.decodeFromJsonElement

internal val Track.canShowPlaylistLinks: Boolean
    get() = isPlayable && mediaType in listOf("audio", "video")

internal suspend fun ServerApi.playlistLinks(track: Track): PlaylistLinks {
    val query = "jobId=${encode(track.jobId)}&name=${encode(track.name)}" +
        (track.libraryOwnerId?.let { "&userId=${encode(it)}" } ?: "")
    return ApiJson.decodeFromJsonElement(request("/api/library/playlist-links?$query"))
}

internal fun playlistLinkUrl(origin: String, ownerId: String, playlist: PlaylistLink): String =
    "${AuthProtocol.normalizeOrigin(origin)}/?playlist=${encode(playlist.id)}&userId=${encode(ownerId)}"

@Composable
internal fun PlaylistLinksDialog(model: MusicViewModel, track: Track, dismiss: () -> Unit) {
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val owner = account ?: return
    val uriHandler = LocalUriHandler.current
    key(track.key, track.libraryOwnerId, owner.origin, owner.user.id, owner.session) {
        var result by remember { mutableStateOf<PlaylistLinks?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var attempt by remember { mutableIntStateOf(0) }
        LaunchedEffect(attempt) {
            result = null
            error = null
            try {
                result = model.api.playlistLinks(track)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Unable to load linked playlists."
            }
        }
        AlertDialog(onDismissRequest = dismiss, title = { Text("Playlist Links") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(track.displayTitle)
                if (result == null && error == null) Text("Loading playlists...")
                result?.let { links ->
                    if (links.playlists.isEmpty()) Text("No playlist links found.")
                    else links.playlists.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }).forEach { playlist ->
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            try {
                                uriHandler.openUri(playlistLinkUrl(owner.origin, links.ownerId, playlist))
                            } catch (failure: Exception) {
                                error = failure.message ?: "Unable to open the playlist."
                            }
                        }) {
                            Text(playlist.title, Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open playlist in browser")
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(onClick = dismiss) { Text("Close") }
        }, dismissButton = {
            if (error != null && result == null) TextButton(onClick = { attempt++ }) { Text("Retry") }
        })
    }
}
