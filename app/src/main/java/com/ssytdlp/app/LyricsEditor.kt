package com.ssytdlp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ssytdlp.app.core.LyricLine
import com.ssytdlp.app.core.SongMetadata
import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MAX_LYRICS_TEXT = 100_000
private const val MAX_SYLT_LINES = 10_000
private val maxSyltTime = BigDecimal("4294967.295")
private val timedLyric = Regex("""^\[([0-9]{2,4}):([0-9]{2}):([0-9]{2})\.([0-9]+)] ?(.*)$""")

internal fun formatSyltForEditing(lines: List<LyricLine>): String = lines.joinToString("\n") { line ->
    val time = if (line.time.isFinite() && line.time >= 0 && line.time <= maxSyltTime.toDouble()) {
        val seconds = BigDecimal.valueOf(line.time)
        val whole = seconds.toLong()
        val fraction = seconds.remainder(BigDecimal.ONE).toPlainString().substringAfter('.', "0").padEnd(3, '0')
        "${(whole / 3600).toString().padStart(2, '0')}:" +
            "${(whole / 60 % 60).toString().padStart(2, '0')}:" +
            "${(whole % 60).toString().padStart(2, '0')}.$fraction"
    } else line.time.toString()
    val text = line.text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    "[$time] $text"
}

private fun decodeLyricText(text: String): String = buildString {
    var index = 0
    while (index < text.length) {
        val escaped = if (text[index] == '\\' && index + 1 < text.length) when (text[index + 1]) {
            'n' -> '\n'
            'r' -> '\r'
            '\\' -> '\\'
            else -> null
        } else null
        if (escaped != null) {
            append(escaped)
            index += 2
        } else append(text[index++])
    }
}

internal fun parseSyltForEditing(source: String): List<LyricLine> {
    require('\u0000' !in source) { "SYLT must not contain NUL characters." }
    val lines = mutableListOf<LyricLine>()
    var textSize = 0L
    source.lineSequence().forEachIndexed { index, raw ->
        if (raw.isBlank()) return@forEachIndexed
        require(lines.size < MAX_SYLT_LINES) { "SYLT supports at most 10,000 lines." }
        val match = requireNotNull(timedLyric.matchEntire(raw)) {
            "SYLT line ${index + 1}: use [HH:MM:SS.mmm] text."
        }
        val (hours, minutes, seconds, fraction, encodedText) = match.destructured
        require(minutes.toInt() < 60 && seconds.toInt() < 60) {
            "SYLT line ${index + 1}: minutes and seconds must be 00–59."
        }
        val time = BigDecimal(hours.toInt() * 3600 + minutes.toInt() * 60 + seconds.toInt())
            .add(BigDecimal("0.$fraction"))
        require(time <= maxSyltTime) {
            "SYLT line ${index + 1}: time must be between 0 and 4294967.295 seconds."
        }
        val text = decodeLyricText(encodedText)
        textSize += text.length
        require(textSize <= MAX_LYRICS_TEXT) { "SYLT text supports at most 100,000 characters in total." }
        lines += LyricLine(time.toDouble(), text)
    }
    return lines
}

internal fun buildLyricsEditRequest(original: SongMetadata, syltText: String, uslt: String): JsonObject =
    buildJsonObject {
        // Untouched modes are omitted, even if their existing server data is not editable.
        if (syltText != formatSyltForEditing(original.sylt)) {
            val lines = parseSyltForEditing(syltText)
            if (lines != original.sylt) put("sylt", buildJsonArray {
                lines.forEach { line -> add(buildJsonObject {
                    put("time", line.time)
                    put("text", line.text)
                }) }
            })
        }
        if (uslt != original.uslt) {
            require(uslt.length <= MAX_LYRICS_TEXT) { "USLT supports at most 100,000 characters." }
            require('\u0000' !in uslt) { "USLT must not contain NUL characters." }
            put("uslt", uslt)
        }
    }

@Composable
fun LyricsEditorDialog(value: SongMetadata, preferUslt: Boolean, busy: Boolean = false,
    dismiss: () -> Unit, save: (JsonObject) -> Unit) {
    var syltText by rememberSaveable(value.sylt, value.uslt) { mutableStateOf(formatSyltForEditing(value.sylt)) }
    var uslt by rememberSaveable(value.sylt, value.uslt) { mutableStateOf(value.uslt) }
    var editUslt by rememberSaveable(value.sylt, value.uslt) { mutableStateOf(preferUslt) }
    val result = remember(value.sylt, value.uslt, syltText, uslt) {
        runCatching { buildLyricsEditRequest(value, syltText, uslt) }
    }
    val request = result.getOrNull()?.takeIf { it.isNotEmpty() }
    val error = result.exceptionOrNull()?.message
    val mode = if (editUslt) "USLT" else "SYLT"
    Dialog(onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Edit lyrics", style = MaterialTheme.typography.headlineSmall)
                TabRow(selectedTabIndex = if (editUslt) 1 else 0) {
                    Tab(selected = !editUslt, enabled = !busy, onClick = { editUslt = false }, text = { Text("SYLT") })
                    Tab(selected = editUslt, enabled = !busy, onClick = { editUslt = true }, text = { Text("USLT") })
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight).height(IntrinsicSize.Min),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (editUslt) "Plain lyrics, up to 100,000 characters. Clear to remove USLT."
                            else "One [HH:MM:SS.mmm] text per line; edit times/text, add or delete lines. " +
                                "Blank rows are ignored; a timestamp alone keeps an empty lyric. " +
                                "Use \\n for a line break and \\\\ for a backslash. " +
                                "Up to 10,000 lines / 100,000 text characters. Clear to remove SYLT.",
                            style = MaterialTheme.typography.bodySmall)
                        key(editUslt) {
                            OutlinedTextField(
                                value = if (editUslt) uslt else syltText,
                                onValueChange = { if (editUslt) uslt = it else syltText = it },
                                enabled = !busy,
                                label = { Text("$mode lyrics") },
                                minLines = 4,
                                textStyle = MaterialTheme.typography.bodyMedium.let {
                                    if (editUslt) it else it.copy(fontFamily = FontFamily.Monospace)
                                },
                                modifier = Modifier.fillMaxWidth().weight(1f)
                                    .heightIn(max = this@BoxWithConstraints.maxHeight.coerceAtLeast(160.dp))
                                    .testTag(if (editUslt) "uslt-editor" else "sylt-editor")
                            )
                        }
                        TextButton(enabled = !busy, onClick = { if (editUslt) uslt = "" else syltText = "" }) {
                            Text("Clear $mode")
                        }
                        if (error != null) Text(error, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(if (busy) "Continue in background" else "Cancel") }
                    TextButton(enabled = !busy && request != null, onClick = { if (!busy) request?.let(save) }) {
                        Text(if (busy) "Saving..." else "Save")
                    }
                }
            }
        }
    }
}
