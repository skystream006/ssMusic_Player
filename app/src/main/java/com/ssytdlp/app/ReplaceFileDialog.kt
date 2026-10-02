package com.ssytdlp.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ssytdlp.app.core.Job
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun canReplaceFile(user: User?, job: Job?, track: Track): Boolean =
    user != null && !user.isShared && job?.id == track.jobId && job.canModify(user) && !job.active && track.mediaType == "audio"

@Composable
internal fun ReplaceFileDialog(model: MusicViewModel, track: Track, dismiss: () -> Unit) {
    val context = LocalContext.current
    var uri by rememberSaveable(track.key) { mutableStateOf<String?>(null) }
    var document by remember(track.key, uri) { mutableStateOf<SelectedDocument?>(null) }
    var error by remember(track.key, uri) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) uri = selected.toString()
    }
    LaunchedEffect(track.key, uri) {
        val selected = uri ?: return@LaunchedEffect
        try {
            document = withContext(Dispatchers.IO) {
                selectedDocument(context, Uri.parse(selected)).also { validateReplacementDocument(track, it) }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "Cannot read the selected file."
        }
    }
    ReplaceFileDialog(track, document?.name, error, model.busy, dismiss,
        choose = { picker.launch(arrayOf("audio/*", "application/octet-stream")) },
        replace = { document?.let { model.replaceFile(track, it.uri, dismiss) } })
}

@Composable
internal fun ReplaceFileDialog(track: Track, filename: String?, error: String?, busy: Boolean,
    dismiss: () -> Unit, choose: () -> Unit, replace: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Replace File") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(track.name)
            Text("This permanently overwrites the song, including embedded tags, artwork, ratings, and lyrics, in every playlist that links to it.")
            Text("The server filename, playlist links and order, transcription lock, and existing NoVocals files stay unchanged.")
            Text("Choose one non-empty .${track.name.substringAfterLast('.')} audio file, up to 512 MB. Convert other formats first; renaming the extension is not enough.")
            OutlinedButton(onClick = choose, enabled = !busy) { Text(filename ?: "Choose replacement file") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Uploading and replacing file...")
            }
        }
    }, confirmButton = {
        TextButton(onClick = replace, enabled = filename != null && error == null && !busy) { Text("Replace File") }
    }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } })
}
