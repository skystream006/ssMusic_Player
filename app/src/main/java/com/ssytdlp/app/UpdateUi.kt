package com.ssytdlp.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun UpdateNotification(model: AppUpdater = viewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val version by model.notification.collectAsStateWithLifecycle()
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.check(automatic = true) }
    }
    LaunchedEffect(version, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            version?.let {
                Toast.makeText(context, "ssMusic Player $it is available. Open App updates to download.", Toast.LENGTH_LONG).show()
                model.consumeNotification()
            }
        }
    }
}

@Composable
fun UpdateSettings(model: AppUpdater = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var confirm by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.permissionReturned()
    }
    LaunchedEffect(state.installRequested, state.busy, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (state.installRequested && !state.busy) {
                try {
                    if (model.needsInstallPermission()) {
                        model.beginPermissionRequest()
                        permission.launch(updatePermissionIntent(context))
                    } else {
                        model.installerIntent()?.let { context.startActivity(it) }
                    }
                } catch (_: android.content.ActivityNotFoundException) {
                    model.installLaunchFailed()
                } catch (_: SecurityException) {
                    model.installLaunchFailed()
                }
                model.consumeInstallRequest()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("App updates", style = MaterialTheme.typography.titleMedium)
                Text("Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall)
                state.availableVersion?.let { Text("Available: $it", style = MaterialTheme.typography.bodySmall) }
                Text(state.message, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { model.check() }, enabled = !state.busy && !state.installRequested,
                modifier = Modifier.weight(1f)) { Text("Check for updates", textAlign = TextAlign.Center) }
        }
        if (state.downloading) {
            LinearProgressIndicator(progress = { if (state.total > 0) state.downloaded.toFloat() / state.total else 0f },
                modifier = Modifier.fillMaxWidth())
            Text("${state.downloaded / 1024} / ${state.total / 1024} KB",
                style = MaterialTheme.typography.bodySmall)
        } else if (state.busy && !state.automaticCheck) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.availableVersion != null || (state.busy && !state.automaticCheck)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (state.availableVersion != null) {
                    Button(onClick = { if (state.ready) model.requestInstall() else confirm = true }, enabled = !state.busy && !state.installRequested,
                        modifier = Modifier.weight(1f)) {
                        Text(if (state.ready) "Install update" else "Download and install", textAlign = TextAlign.Center)
                    }
                }
                if (state.busy && !state.automaticCheck) TextButton(onClick = model::cancel) { Text("Cancel") }
            }
        }
    }
    if (confirm && state.availableVersion != null) {
        AlertDialog(onDismissRequest = { confirm = false },
            title = { Text("Download and install ${state.availableVersion}?") },
            text = { Text("Download ${state.total / (1024 * 1024)} MB from GitHub. You may need to allow this app to install updates. Android will ask for final confirmation; your library and sign-in are kept.") },
            confirmButton = { TextButton(onClick = { confirm = false; model.download() }) { Text("Download and install") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    }
}
