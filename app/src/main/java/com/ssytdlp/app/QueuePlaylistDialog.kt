package com.ssytdlp.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun QueuePlaylistDialog(model: MusicViewModel) {
    val draft = model.queuePlaylistDraft ?: return
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val currentAccount = account
    if (currentAccount == null || currentAccount.user.isShared || currentAccount.user.id.isBlank() ||
        currentAccount.origin != draft.account.origin || currentAccount.user.id != draft.account.user.id ||
        currentAccount.session != draft.account.session) {
        LaunchedEffect(draft) { model.closeQueuePlaylist() }
        return
    }
    key(draft.account.origin, draft.account.user.id, draft.account.session, draft.tracks) {
        var name by rememberSaveable { mutableStateOf(draft.job?.playlistTitle.orEmpty()) }
        var parentId by rememberSaveable { mutableStateOf<String?>(null) }
        var choosingLocation by remember { mutableStateOf(false) }
        val locations = playlistLocations(model.library.library.entries)
        val validName = isValidPlaylistTitle(name)
        LaunchedEffect(draft.saved) {
            if (draft.saved) model.closeQueuePlaylist()
        }
        AlertDialog(onDismissRequest = { if (!model.busy) model.closeQueuePlaylist() },
            title = { Text("Save As New Playlist") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it.take(200) }, label = { Text("Playlist name") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        enabled = !model.busy && draft.job == null,
                        isError = name.isNotEmpty() && !validName)
                    Text("Destination folder", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { choosingLocation = true }, enabled = !model.busy,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(locations.find { it.first == parentId }?.second ?: "Folder unavailable")
                    }
                    Text("${draft.tracks.size} queued tracks. Source files and the queue are unchanged.")
                    draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }, confirmButton = {
                TextButton(onClick = { model.saveQueuePlaylist(name, parentId) },
                    enabled = !model.busy && validName && locations.any { it.first == parentId }) {
                    Text("Save playlist")
                }
            }, dismissButton = {
                TextButton(onClick = model::closeQueuePlaylist, enabled = !model.busy) { Text("Cancel") }
            })
        if (choosingLocation) DestinationDialog("Destination folder", locations, { choosingLocation = false }) {
            parentId = it
            choosingLocation = false
        }
    }
}
