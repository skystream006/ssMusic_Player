package com.ssytdlp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ssytdlp.app.core.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal fun songVideoPath(track: Track): String =
    "/api/jobs/${encode(track.jobId)}/files/${encode(track.name)}/video" +
        (track.libraryOwnerId?.let { "?userId=${encode(it)}" } ?: "")

@Composable
internal fun SongVideoMenuItem(model: MusicViewModel, track: Track, open: () -> Unit) {
    if (model.canDownloadSongVideo(track)) {
        DropdownMenuItem(text = { Text("Retrieve song video") },
            leadingIcon = { Icon(Icons.Rounded.VideoLibrary, null) },
            enabled = !model.songBusy(track), onClick = open)
    }
}

@Composable
internal fun SongVideoPolling(model: MusicViewModel, track: Track, onError: (String?) -> Unit) {
    val account by model.sessions.account.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = model.library.songVideo(track).linkedVideo?.active == true
    val saving = track.key in model.pendingSongs
    val currentOnError by rememberUpdatedState(onError)
    LaunchedEffect(track.key, track.libraryOwnerId, account?.origin, account?.user?.id, account?.session,
        lifecycle, active, saving) {
        if (account == null || saving) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                try {
                    model.refreshSongVideo(track)
                    currentOnError(null)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    currentOnError(failure.message ?: "Unable to load the song video.")
                    return@repeatOnLifecycle
                }
                if (model.library.songVideo(track).linkedVideo?.active != true) break
                delay(2_500)
            } while (true)
        }
    }
}

@Composable
internal fun SongVideoDialog(model: MusicViewModel, track: Track, dismiss: () -> Unit) {
    val account by model.sessions.account.collectAsStateWithLifecycle()
    if (!model.canDownloadSongVideo(track)) return
    key(track.key, track.libraryOwnerId, account?.origin, account?.user?.id, account?.session) {
        val video = model.library.songVideo(track)
        val pending = video.linkedVideo?.active == true
        val busy = model.songBusy(track)
        var error by remember { mutableStateOf<String?>(null) }
        SongVideoPolling(model, track) { error = it }
        AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Retrieve song video") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(track.displayTitle)
                Text("The video is saved with this song, not added to a playlist or the playback queue.")
                Text(when (video.linkedVideo?.status) {
                    "queued" -> "Video download queued. You can close this dialog."
                    "running" -> "Downloading video. You can close this dialog."
                    "completed" -> "Video ready. Select Video in the player."
                    "stopped" -> "Video download stopped."
                    "failed" -> "Video download failed. You can retry."
                    else -> "Download the video to watch it with this song."
                })
                (error ?: video.linkedVideo?.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            if (!video.available) TextButton(
                enabled = !busy && !pending && model.privacyJob(track)?.active != true,
                onClick = { error = null; model.downloadSongVideo(track) }) {
                Text(if (video.linkedVideo?.status in listOf("failed", "stopped")) "Retry download" else "Download video")
            }
        }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Close") } })
    }
}
