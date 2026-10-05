package com.ssytdlp.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssytdlp.app.core.LibraryEntry
import com.ssytdlp.app.core.LibraryPlaylist

@Composable
internal fun PlaylistEditor(model: MusicViewModel) {
    val target = model.playlistEditTarget ?: return
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val entry = model.library.library.entries.find { it.id == target.id && it.type == "playlist" }
    val playlist = model.library.library.playlists.find { it.id == target.id }
    if (entry == null || playlist == null || account?.user?.isShared != false ||
        account?.origin != target.account.origin || account?.user?.id != target.account.user.id ||
        account?.session != target.account.session) {
        LaunchedEffect(target) { model.closePlaylistEditor() }
        return
    }
    key(target) {
        var sharing by rememberSaveable { mutableStateOf(false) }
        if (sharing) SharePlaylistDialog(model, playlist, model::closePlaylistEditor)
        else EditPlaylistDialog(model, entry, playlist, model::closePlaylistEditor, { sharing = true })
    }
}

@Composable
internal fun EditPlaylistDialog(model: MusicViewModel, entry: LibraryEntry, playlist: LibraryPlaylist,
    dismiss: () -> Unit, share: () -> Unit) {
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val user = account?.user
    val canRename = !entry.protected && playlist.canRename(user)
    val canChangePrivacy = playlist.canChangePrivacy(user)
    val enabled = !model.busy && !playlist.active
    var name by rememberSaveable(playlist.id) { mutableStateOf(playlist.playlistTitle) }
    var isPrivate by rememberSaveable(playlist.id) { mutableStateOf(playlist.isPrivate) }
    var parentId by rememberSaveable(playlist.id) { mutableStateOf(entry.parentId) }
    var locationEdited by rememberSaveable(playlist.id) { mutableStateOf(false) }
    var choosingLocation by remember { mutableStateOf(false) }
    val result = model.playlistSaveResult?.takeIf { it.id == playlist.id }
    val currentDismiss by rememberUpdatedState(dismiss)
    LaunchedEffect(result) {
        if (result != null && result.error == null) currentDismiss()
    }
    val locations = playlistLocations(model.library.library.entries)
    val dirty = (canRename && name.trim() != playlist.playlistTitle) ||
        (canChangePrivacy && isPrivate != playlist.isPrivate) || parentId != entry.parentId
    val validName = name.trim().let { it.length in 1..200 && it.none { char -> char < ' ' || char == '\u007f' } }
    val uriHandler = LocalUriHandler.current
    AlertDialog(onDismissRequest = { if (!model.busy) dismiss() }, title = { Text("Edit playlist") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(200) }, label = { Text("Playlist name") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled && canRename,
                isError = canRename && !validName)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(if (canChangePrivacy) isPrivate else playlist.isPrivate, { isPrivate = it },
                    enabled = enabled && canChangePrivacy)
                Text("Make private")
            }
            Text("Location", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { choosingLocation = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(locations.find { it.first == parentId }?.second ?: "Folder unavailable")
            }
            if (!entry.protected) TextButton(onClick = {
                account?.origin?.let { uriHandler.openUri("$it/job/${encode(playlist.jobId ?: playlist.id)}") }
            }, enabled = enabled) { Text("Open job details") }
            TextButton(onClick = share, enabled = enabled && !dirty && !playlist.isPrivate &&
                playlist.songCount > 0 && playlist.canShare(user)) { Text("Share Playlist") }
            result?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (model.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Saving...")
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            model.savePlaylist(playlist.id, name, isPrivate, parentId, move = locationEdited)
        }, enabled = enabled && (!canRename || validName)) { Text("Save changes") }
    }, dismissButton = { TextButton(onClick = dismiss, enabled = !model.busy) { Text("Cancel") } })
    if (choosingLocation) DestinationDialog("Location", locations, { choosingLocation = false }) {
        parentId = it
        locationEdited = true
        choosingLocation = false
    }
}

internal fun playlistLocations(entries: List<LibraryEntry>): List<Pair<String?, String>> {
    val folders = entries.filter { it.type == "folder" }.associateBy { it.id }
    return listOf(null to "Library") + folders.values.map { folder ->
        val names = mutableListOf(folder.name)
        val visited = mutableSetOf(folder.id)
        var parent = folders[folder.parentId]
        while (parent != null && visited.add(parent.id)) {
            names.add(0, parent.name)
            parent = folders[parent.parentId]
        }
        folder.id to names.joinToString(" / ")
    }.sortedBy { it.second.lowercase() }
}
