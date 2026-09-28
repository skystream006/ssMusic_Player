@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ssytdlp.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ssytdlp.app.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

@Composable
fun JobsScreen(model: MusicViewModel, requestNotifications: () -> Unit, download: (String, String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var add by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var mine by rememberSaveable { mutableStateOf(false) }
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val user = account?.user ?: return
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { model.pollJobs(); delay(5_000) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Jobs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ToolButton(Icons.Rounded.UploadFile, "Import music", enabled = !model.busy) { importing = true }
            ToolButton(Icons.Rounded.Add, "Add YouTube URL", enabled = !model.busy) { add = true }
        }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search downloads") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true)
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(mine, { mine = !mine }, label = { Text("My downloads") })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            val filtered = model.jobs.filter { (!mine || it.isMember(user)) && "${it.playlistTitle} ${it.url}".contains(search, true) }.sortedByDescending { it.updatedAt }
            if (filtered.isEmpty()) item { EmptyState(Icons.Rounded.Download, "No downloads") }
            items(filtered, key = { it.id }) { job ->
                Column {
                    ListItem(headlineContent = { Text(job.playlistTitle.ifBlank { job.url.ifBlank { job.id } }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${job.status}  /  ${job.files.size} files  /  ${job.initiatedBy?.name.orEmpty()}", maxLines = 2) },
                        leadingContent = { Icon(if (job.active) Icons.Rounded.Downloading else if (job.status == "failed") Icons.Rounded.ErrorOutline else Icons.Rounded.Album, null) },
                        trailingContent = { JobMenu(model, job, requestNotifications, download) })
                    if (job.active) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                    if (!job.error.isNullOrBlank()) Text(job.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
    if (add) AddJobDialog(model) { add = false }
    if (importing) ImportDialog(model) { importing = false }
}

@Composable
fun JobMenu(model: MusicViewModel, job: Job, requestNotifications: () -> Unit, download: (String, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var rerun by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var contributors by remember { mutableStateOf(false) }
    var playlist by remember { mutableStateOf(false) }
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val user = account?.user ?: return
    Box {
        ToolButton(Icons.Rounded.MoreVert, "Download options", enabled = !model.busy) { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Play files") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) }, enabled = job.files.isNotEmpty(), onClick = { open = false; requestNotifications(); model.playJob(job) })
            DropdownMenuItem(text = { Text("Save ZIP") }, leadingIcon = { Icon(Icons.Rounded.Download, null) }, enabled = job.files.isNotEmpty(), onClick = { open = false; download("/api/jobs/${encode(job.id)}/download-all", "${job.playlistTitle.ifBlank { "music" }}.zip") })
            if (job.isMember(user)) {
                DropdownMenuItem(text = { Text("Show in library") }, leadingIcon = { Icon(Icons.Rounded.LibraryAdd, null) }, onClick = { open = false; model.jobAction(job, "link") })
                DropdownMenuItem(text = { Text("Add files to playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = { open = false; playlist = true })
            }
            if (job.canModify(user)) {
                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { open = false; rename = true })
                if (!job.active && job.source !in listOf("files", "itunes")) DropdownMenuItem(text = { Text("Rerun download") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, onClick = { open = false; rerun = true })
                if (user.role == "admin" || job.initiatedBy?.id == user.id) DropdownMenuItem(text = { Text("Contributors") }, leadingIcon = { Icon(Icons.Rounded.Group, null) }, onClick = { open = false; contributors = true })
                if (!job.active) DropdownMenuItem(text = { Text("Delete job and files") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { open = false; deleting = true })
            }
        }
    }
    if (deleting) ConfirmDialog("Delete this job?", "All of its media files and library links will be deleted for every user. This cannot be undone.", { deleting = false }) { model.jobAction(job, "delete"); deleting = false }
    if (rerun) ConfirmDialog("Rerun download?", "Existing files are retained; missing files will be downloaded again.", { rerun = false }) { model.jobAction(job, "rerun"); rerun = false }
    if (rename) NameDialog("Rename playlist", job.playlistTitle, { rename = false }) { model.renameJob(job, it); rename = false }
    if (contributors) ContributorsDialog(model, job) { contributors = false }
    if (playlist) DestinationDialog("Add files to playlist", model.library.library.playlists.map { it.id to it.playlistTitle }, { playlist = false }) {
        if (it != null) model.addJobToPlaylist(job, it)
        playlist = false
    }
}

@Composable
fun AddJobDialog(model: MusicViewModel, dismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var video by rememberSaveable { mutableStateOf(false) }
    var metadataOnly by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("New download") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(url, { url = it }, label = { Text("YouTube or YouTube Music URL") }, maxLines = 3)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Audio", "Video").forEachIndexed { index, text -> SegmentedButton(selected = video == (index == 1), onClick = { video = index == 1 }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(text) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(metadataOnly, { metadataOnly = it }); Text("Metadata only") }
        }
    }, confirmButton = { TextButton(onClick = { model.createJob(url, video, metadataOnly); dismiss() }, enabled = url.isNotBlank() && !model.busy) { Text("Add") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
fun ContributorsDialog(model: MusicViewModel, job: Job, dismiss: () -> Unit) {
    var users by remember { mutableStateOf<List<User>?>(null) }
    var selected by remember { mutableStateOf(job.contributors.map { it.id }.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(job.id) {
        try { users = ApiJson.decodeFromJsonElement(model.api.request("/api/jobs/${encode(job.id)}/contributors/users").jsonObject.getValue("users")) }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Contributors") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            if (users == null) item { Text(error ?: "Loading...") }
            items(users.orEmpty(), key = { it.id }) { user ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(user.id in selected, { selected = if (it) selected + user.id else selected - user.id }); Text(user.name)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { model.saveContributors(job, selected); dismiss() }, enabled = users != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
fun ImportDialog(model: MusicViewModel, dismiss: () -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var uris by rememberSaveable { mutableStateOf(listOf<String>()) }
    var xml by rememberSaveable { mutableStateOf<String?>(null) }
    var archive by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var playlistId by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingPlaylist by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf<JsonObject?>(null) }
    var localXml by rememberSaveable { mutableStateOf("") }
    var localZip by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { values -> uris = values.map { it.toString() } }
    val xmlPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> xml = uri.toString() } }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> archive = uri.toString() } }
    LaunchedEffect(mode) {
        if (mode == 2) try { local = model.api.request("/api/jobs/import/local").jsonObject }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
    }
    AlertDialog(onDismissRequest = { if (!model.busy) dismiss() }, title = { Text("Import music") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Files", "iTunes", "Server").forEachIndexed { index, text -> SegmentedButton(selected = mode == index, onClick = { mode = index }, enabled = !model.busy, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(text) } }
            }
            when (mode) {
                0 -> {
                    OutlinedButton(onClick = { filesPicker.launch(arrayOf("audio/*", "video/*")) }, enabled = !model.busy) {
                        Icon(Icons.Rounded.AudioFile, null); Spacer(Modifier.width(8.dp)); Text(if (uris.isEmpty()) "Choose media files" else "${uris.size} files selected")
                    }
                    TextButton(onClick = { pickingPlaylist = true }, enabled = !model.busy) {
                        Text(model.library.library.playlists.find { it.id == playlistId }?.playlistTitle ?: "New playlist")
                        Icon(Icons.Rounded.ExpandMore, null)
                    }
                    if (playlistId == null) OutlinedTextField(title, { title = it.take(200) }, label = { Text("Playlist name") }, enabled = !model.busy)
                }
                1 -> {
                    OutlinedButton(onClick = { xmlPicker.launch(arrayOf("text/xml", "application/xml", "application/octet-stream")) }, enabled = !model.busy) { Text(if (xml == null) "Choose library XML" else "Library XML selected") }
                    OutlinedButton(onClick = { archivePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, enabled = !model.busy) { Text(if (archive == null) "Choose media ZIP" else "Media ZIP selected") }
                }
                else -> {
                    ChoiceField("Library XML", localXml, local?.get("xmlFiles")?.jsonArray.orEmpty().map { it.jsonObject.getValue("name").jsonPrimitive.content }, !model.busy) { localXml = it }
                    ChoiceField("Media ZIP", localZip, local?.get("zipFiles")?.jsonArray.orEmpty().map { it.jsonObject.getValue("name").jsonPrimitive.content }, !model.busy) { localZip = it }
                    if (local != null && local?.get("xmlFiles")?.jsonArray?.isEmpty() == true) Text("No XML files in server import storage.")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (model.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Importing...") }
        }
    }, confirmButton = { TextButton(onClick = {
        when (mode) {
            0 -> model.importFiles(uris.map(Uri::parse), title, playlistId, onSuccess = dismiss)
            1 -> model.importFiles(listOfNotNull(xml, archive).map(Uri::parse), "", null, itunes = true, onSuccess = dismiss)
            else -> model.importLocal(localXml, localZip, onSuccess = dismiss)
        }
    }, enabled = !model.busy && when (mode) {
        0 -> uris.isNotEmpty() && (title.isNotBlank() || playlistId != null)
        1 -> xml != null && archive != null
        else -> localXml.isNotBlank() && localZip.isNotBlank()
    }) { Text("Import") } }, dismissButton = {
        TextButton(onClick = { if (model.busy) model.cancelOperation() else dismiss() }) { Text(if (model.busy) "Cancel request" else "Close") }
    })
    if (pickingPlaylist) DestinationDialog("Destination playlist", listOf(null to "New playlist") + model.library.library.playlists.map { it.id to it.playlistTitle },
        { pickingPlaylist = false }) { playlistId = it; pickingPlaylist = false }
}

@Composable
fun ChoiceField(label: String, value: String, options: List<String>, enabled: Boolean = true, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(value.ifBlank { label }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ExpandMore, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onChange(option) }) }
        }
    }
}