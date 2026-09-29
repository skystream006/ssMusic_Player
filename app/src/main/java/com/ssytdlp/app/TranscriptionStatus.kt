package com.ssytdlp.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.ssytdlp.app.core.Transcription
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal val Transcription?.label: String
    get() = when (this?.status) {
        "sent" -> "Transcription request sent"
        "transcribed" -> if (this?.lyricsIncluded == true) "Lyrics Included" else "AI Transcribed"
        "failed" -> "Transcription failed"
        "interrupted" -> "Interrupted"
        else -> if (this == null) "Not transcribed" else "Transcription status unknown"
    }

internal fun Transcription.tooltip(formatDate: (String?) -> String = ::transcriptionDate): String = buildList {
    if (status !in setOf("sent", "transcribed", "failed", "interrupted")) {
        add("Status: ${status.ifBlank { "Unknown" }}")
    }
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
    val label = transcription.label
    val details = transcription?.tooltip() ?: "No transcription information is available for this song."
    var showDetails by remember(transcription) { mutableStateOf(false) }
    val color = when (transcription?.status) {
        "failed", "interrupted" -> MaterialTheme.colorScheme.error
        "sent", "transcribed" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall
    val labelWidth = textMeasurer.measure(label, style, softWrap = false).size.width
    val density = LocalDensity.current
    BoxWithConstraints {
        val showLabel = labelWidth + with(density) { 28.dp.toPx() } <= constraints.maxWidth
        Row(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Show transcription details") { showDetails = true }
            .semantics { contentDescription = "$label\n$details" }
            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(when (transcription?.status) {
                "sent" -> Icons.Rounded.Refresh
                "failed" -> Icons.Rounded.ErrorOutline
                "interrupted" -> Icons.Rounded.Schedule
                "transcribed" -> if (transcription.lyricsIncluded) Icons.Rounded.Subtitles else Icons.Rounded.Check
                else -> if (transcription == null) Icons.Rounded.SubtitlesOff else Icons.Rounded.HelpOutline
            }, null, Modifier.size(24.dp), tint = color)
            if (showLabel) Text(label, style = style, color = color, maxLines = 1)
        }
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
