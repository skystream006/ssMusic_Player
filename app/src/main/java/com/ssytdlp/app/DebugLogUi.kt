package com.ssytdlp.app

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DebugLogSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by DebugLog.enabled.collectAsState()
    val mode by DebugLog.mode.collectAsState()
    var log by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var choosingMode by remember { mutableStateOf(false) }
    LaunchedEffect(context) { withContext(Dispatchers.IO) { DebugLog.initialize(context) } }

    fun enableLogging(selected: DebugLogMode) {
        choosingMode = false
        busy = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) { DebugLog.setEnabled(true, selected) }
            message = if (saved) null else "Could not save the logging preference."
            busy = false
        }
    }

    fun shareSnapshot(text: String) {
        message = if (text.isEmpty()) "No debug logs saved." else {
            runCatching {
                context.startActivity(Intent.createChooser(DebugLog.shareIntent(text), "Share debug log"))
            }.fold({ null }, { "No app available to share logs." })
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Debug logging", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(enabled, onCheckedChange = { value ->
                if (value) choosingMode = true else {
                    busy = true
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { DebugLog.setEnabled(false) }
                        message = if (saved) null else "Could not save the logging preference."
                        busy = false
                    }
                }
            }, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Debug logging" })
        }
        Text("Mode: ${if (mode == DebugLogMode.FULL) "Full" else "Reactive"}")
        Text("Off by default. Saves up to 64 KB privately on this device. Only event types, status numbers and exception classes are recorded—not credentials, URLs or library data.")
        Text("Full rotates bounded log files. Reactive keeps the latest 100 complete events.")
        Text("Turning this off stops collection. Saved logs remain until you clear them. Review before sharing.")
        FlowRow {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    log = withContext(Dispatchers.IO) { DebugLog.read() }
                    busy = false
                }
            }) { Text("View logs") }
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val text = withContext(Dispatchers.IO) { DebugLog.read() }
                    shareSnapshot(text)
                    busy = false
                }
            }) { Text("Share logs") }
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val cleared = withContext(Dispatchers.IO) { DebugLog.clear() }
                    if (cleared) log = log?.let { "" }
                    message = if (cleared) "Debug logs cleared." else "Could not clear debug logs."
                    busy = false
                }
            }) { Text("Clear logs") }
        }
        message?.let { Text(it) }
    }
    if (choosingMode) {
        AlertDialog(
            onDismissRequest = { choosingMode = false },
            title = { Text("Choose debug logging mode") },
            text = { Text("Full keeps rotating logs (up to 64 KB). Reactive keeps only the latest 100 complete events. Neither mode records credentials or personal content.") },
            confirmButton = {
                TextButton(onClick = { enableLogging(DebugLogMode.FULL) }) { Text("Full") }
                TextButton(onClick = { enableLogging(DebugLogMode.REACTIVE) }) { Text("Reactive") }
            },
            dismissButton = { TextButton(onClick = { choosingMode = false }) { Text("Cancel") } }
        )
    }
    log?.let { text ->
        AlertDialog(
            onDismissRequest = { log = null },
            title = { Text("Debug log") },
            text = {
                SelectionContainer {
                    Text(text.ifEmpty { "No debug logs saved." },
                        Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { log = null }) { Text("Close") } },
            dismissButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        log = withContext(Dispatchers.IO) { DebugLog.read() }
                        busy = false
                    }
                }) { Text("Refresh") }
                TextButton(onClick = { shareSnapshot(text) }) { Text("Share snapshot") }
            }
        )
    }
}
