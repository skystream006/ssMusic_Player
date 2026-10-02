package com.ssytdlp.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ssytdlp.app.core.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReplaceFileDialog(model: MusicViewModel, track: Track, dismiss: () -> Unit) {
    val context = LocalContext.current
    val account by model.sessions.account.collectAsStateWithLifecycle()
    var uri by rememberSaveable(track.key, account?.origin, account?.user?.id, account?.session) { mutableStateOf<String?>(null) }
    var document by remember(uri) { mutableStateOf<SelectedDocument?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) uri = selected.toString()
    }
    LaunchedEffect(uri) {
        uri?.let {
            try {
                document = withContext(Dispatchers.IO) {
                    selectedDocument(context, Uri.parse(it)).also { value -> validateReplacement(track, value) }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Cannot read the selected file."
            }
        }
    }
    if (!model.canReplaceFile(track)) {
        LaunchedEffect(Unit) { dismiss() }
        return
    }
    ReplaceFileDialog(track, document?.name, model.busy, error, choose = { picker.launch(arrayOf("*/*")) },
        dismiss = dismiss, replace = { document?.let {
            error = null
            model.replaceFile(track, it.uri, onError = { failure -> error = failure }, onSuccess = dismiss)
        } })
}

@Composable
internal fun ReplaceFileDialog(track: Track, filename: String?, busy: Boolean, error: String?,
    choose: () -> Unit, dismiss: () -> Unit, replace: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Replace File") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(track.name)
            Text("This permanently overwrites the audio and embedded tags, artwork, ratings, and lyrics in every playlist linking to this song.")
            Text("The server filename, playlist links and order, transcription lock, and other files (including NoVocals versions) stay unchanged.")
            Text("Choose one non-empty .${track.name.substringAfterLast('.')} file, up to 512 MB. Convert other formats first; renaming the extension does not convert audio.")
            TextButton(onClick = choose, enabled = !busy) { Text("Choose replacement file") }
            filename?.let { Text(it) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) Text("Uploading and replacing file…")
        }
    }, confirmButton = {
        TextButton(onClick = replace, enabled = filename != null && !busy) { Text("Replace File") }
    }, dismissButton = {
        TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") }
    })
}
