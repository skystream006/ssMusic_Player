@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ssytdlp.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

@Composable
fun SettingsScreen(model: MusicViewModel, download: (String, String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentAccount by model.sessions.account.collectAsStateWithLifecycle()
    val account = currentAccount ?: return
    var logout by remember { mutableStateOf(false) }
    var schedule by remember { mutableStateOf(false) }
    var backupFormat by rememberSaveable { mutableStateOf("android") }
    var destination by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { model.pollSettings(); delay(5_000) }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text(account.user.name, style = MaterialTheme.typography.titleMedium)
        Text(account.origin, style = MaterialTheme.typography.bodyMedium)
        Text("Session expires ${account.session.expiresAt.substringBefore('T')}", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Blue Wave", "Server theme").forEachIndexed { index, label ->
                SegmentedButton(selected = model.waveAppearance == (index == 0), onClick = { model.chooseWaveAppearance(index == 0) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
            }
        }
        if (!model.waveAppearance) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("light" to 0xFF376D60, "midnight" to 0xFF5F7FF0, "royal-purple" to 0xFF7139C6,
                "gold" to 0xFFA77A0A, "green" to 0xFF227452, "pink" to 0xFFBF3D78, "black" to 0xFF202124).forEach { (id, color) ->
                Box(Modifier.size(48.dp).semantics { contentDescription = "$id theme"; selected = model.preferences.theme == id }
                    .clickable(enabled = !model.busy) { model.setTheme(theme = id) }.padding(6.dp)
                    .border(if (model.preferences.theme == id) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .padding(5.dp).background(Color(color), CircleShape))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Dark appearance", Modifier.weight(1f))
            Switch(model.preferences.mode == "dark" || (model.preferences.mode == null && model.preferences.theme in listOf("midnight", "black")),
                { model.setTheme(mode = if (it) "dark" else "light") }, enabled = !model.busy)
        }
        }
        HorizontalDivider()
        Text("Library backup", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Android", "iTunes").forEachIndexed { index, label -> SegmentedButton(selected = backupFormat == if (index == 0) "android" else "itunes",
                onClick = { backupFormat = if (index == 0) "android" else "itunes" }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) } }
        }
        if (backupFormat == "itunes") OutlinedTextField(destination, { destination = it }, label = { Text("iTunes extraction folder") }, modifier = Modifier.fillMaxWidth())
        val backup = model.backup
        val running = backup?.get("running")?.jsonPrimitive?.booleanOrNull == true
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            val progress = backup?.get("progress") as? JsonObject
            Text("${progress?.get("stage")?.jsonPrimitive?.content ?: "Preparing"}: ${progress?.get("processedSongs")?.jsonPrimitive?.content ?: "0"} / ${progress?.get("totalSongs")?.jsonPrimitive?.content ?: "?"}")
        }
        val latest = backup?.get("latest") as? JsonObject
        if (latest != null) Text("Latest: ${latest["createdAt"]?.jsonPrimitive?.content?.substringBefore('T')}  /  ${latest["songCount"]?.jsonPrimitive?.content} songs")
        (backup?.get("error") as? JsonPrimitive)?.contentOrNull?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { model.startBackup(backupFormat, destination) }, enabled = !model.busy && !running && (backupFormat == "android" || destination.isNotBlank())) {
                Icon(Icons.Rounded.Backup, null); Spacer(Modifier.width(8.dp)); Text("Back up")
            }
            ToolButton(Icons.Rounded.Download, "Save latest backup", enabled = latest != null && !model.busy) {
                download("/api/library/export?source=latest", "ssMusic-${latest?.get("format")?.jsonPrimitive?.content ?: "android"}.zip")
            }
            ToolButton(Icons.Rounded.Schedule, "Backup schedule", enabled = !model.busy) { schedule = true }
        }
        backup?.get("nextRunAt")?.jsonPrimitive?.contentOrNull?.let { Text("Next backup: $it", style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
        Text("Device", style = MaterialTheme.typography.titleMedium)
        ListItem(headlineContent = { Text("Notifications") }, leadingContent = { Icon(Icons.Rounded.Notifications, null) }, trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
            modifier = Modifier.clickable {
                runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
            })
        ListItem(headlineContent = { Text("Battery and background activity") }, leadingContent = { Icon(Icons.Rounded.BatteryChargingFull, null) }, trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
            modifier = Modifier.clickable { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) } })
        HorizontalDivider()
        Text("Server", style = MaterialTheme.typography.titleMedium)
        val media = model.health?.get("media") as? JsonObject
        Text("Media files: ${media?.get("totalFiles")?.jsonPrimitive?.content ?: "Unavailable"}")
        ListItem(headlineContent = { Text(if (account.user.role == "admin") "Passkeys and administration" else "Passkeys and account") }, leadingContent = { Icon(Icons.Rounded.Key, null) },
            trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) }, modifier = Modifier.clickable {
                runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse("${account.origin}/settings")) }
                    .onFailure { model.message("Unable to open the browser.") }
            })
        OutlinedButton(onClick = { logout = true }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Rounded.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
        }
        if (model.busy) TextButton(onClick = model::cancelOperation) { Text("Cancel current transfer") }
        HorizontalDivider()
        UpdateSettings()
        HorizontalDivider()
        DebugLogSettings()
        Text("ssMusic Player ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 20.dp))
    }
    if (logout) ConfirmDialog("Sign out?", "Playback will stop and this device's server session will be revoked.", { logout = false }) { logout = false; model.logout() }
    if (schedule) BackupScheduleDialog(model) { schedule = false }
}

@Composable
fun BackupScheduleDialog(model: MusicViewModel, dismiss: () -> Unit) {
    val schedule = model.backup?.get("schedule") as? JsonObject
    var enabled by rememberSaveable { mutableStateOf(schedule?.get("enabled")?.jsonPrimitive?.booleanOrNull == true) }
    var frequency by rememberSaveable { mutableStateOf(schedule?.get("frequency")?.jsonPrimitive?.content ?: "daily") }
    var time by rememberSaveable { mutableStateOf(schedule?.get("time")?.jsonPrimitive?.content ?: "03:00") }
    var weekday by rememberSaveable { mutableIntStateOf(schedule?.get("weekday")?.jsonPrimitive?.intOrNull ?: 0) }
    var format by rememberSaveable { mutableStateOf(schedule?.get("format")?.jsonPrimitive?.content ?: "android") }
    var destination by rememberSaveable { mutableStateOf(schedule?.get("destination")?.jsonPrimitive?.content ?: "") }
    val days = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    AlertDialog(onDismissRequest = dismiss, title = { Text("Backup schedule") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Enabled", Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
            ChoiceField("Frequency", frequency, listOf("daily", "weekly"), enabled) { frequency = it }
            if (frequency == "weekly") ChoiceField("Weekday", days[weekday], days, enabled) { weekday = days.indexOf(it) }
            OutlinedTextField(time, { time = it.take(5) }, label = { Text("Time (HH:mm UTC)") }, singleLine = true, enabled = enabled)
            ChoiceField("Format", format, listOf("android", "itunes"), enabled) { format = it }
            if (format == "itunes") OutlinedTextField(destination, { destination = it }, label = { Text("iTunes extraction folder") }, enabled = enabled)
        }
    }, confirmButton = { TextButton(onClick = { model.backupSchedule(enabled, frequency, time, weekday, format, destination); dismiss() },
        enabled = !model.busy && (!enabled || (Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(time) && (format == "android" || destination.isNotBlank())))) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}