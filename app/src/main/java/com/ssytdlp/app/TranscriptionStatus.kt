package com.ssytdlp.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ssytdlp.app.core.Transcription
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal val Transcription.label: String?
    get() = when (status) {
        "sent" -> "Transcription request sent"
        "transcribed" -> if (lyricsIncluded) "Lyrics Included" else "AI Transcribed"
        "failed" -> "Transcription failed"
        "interrupted" -> "Interrupted"
        else -> null
    }

internal fun Transcription.tooltip(formatDate: (String?) -> String = ::transcriptionDate): String = buildList {
    add("Requested: ${formatDate(requestedAt)}")
    completedAt?.takeIf { it.isNotBlank() }?.let { add("Finished: ${formatDate(it)}") }
    options?.let { saved ->
        add("Language: ${transcriptionLanguages.find { it.first == saved.language }?.second ?: saved.language}")
        add("Multilingual: ${saved.multilingual.optionLabel()}")
        add("No vocals (karaoke): ${saved.noVocals.optionLabel()}")
        add("Viet Lyrics Fallback: ${saved.vietLyricsFallback.optionLabel()}")
        add("Add lyrics: ${if (lyricsIncluded) "Yes" else "No"}")
        saved.lyricsMode?.takeIf { it.isNotBlank() }?.let { mode ->
            add("Lyrics mode: ${transcriptionLyricsModes.find { it.first == mode }?.second ?: mode}")
        }
    }
    error?.takeIf { it.isNotBlank() }?.let(::add)
}.joinToString("\n")

private fun Boolean?.optionLabel() = when (this) {
    true -> "On"
    false -> "Off"
    null -> "Service default"
}

private fun transcriptionDate(value: String?): String {
    if (value.isNullOrBlank()) return "Unknown"
    return try {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
    } catch (_: java.time.DateTimeException) { value }
}

@Composable
internal fun TranscriptionStatus(transcription: Transcription?) {
    val label = transcription?.label ?: return
    val details = transcription.tooltip()
    var showDetails by remember { mutableStateOf(false) }
    val color = when (transcription.status) {
        "failed", "interrupted" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Row(Modifier.clickable(onClickLabel = "Show transcription details") { showDetails = true }
        .semantics { contentDescription = "$label\n$details" }
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(when (transcription.status) {
            "sent" -> Icons.Rounded.Refresh
            "failed" -> Icons.Rounded.ErrorOutline
            "interrupted" -> Icons.Rounded.Schedule
            else -> Icons.Rounded.Check
        }, null, Modifier.size(14.dp), tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (showDetails) {
        AlertDialog(onDismissRequest = { showDetails = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    IconButton(onClick = { showDetails = false }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close transcription details")
                    }
                }
            },
            text = { Text(details, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {})
    }
}
