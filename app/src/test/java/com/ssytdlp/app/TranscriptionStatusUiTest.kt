package com.ssytdlp.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ssytdlp.app.core.Job
import com.ssytdlp.app.core.Library
import com.ssytdlp.app.core.SavedTranscriptionOptions
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.TrackPage
import com.ssytdlp.app.core.Transcription
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class TranscriptionStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aiTranscribedSubtitleOpensDetailsWithoutPlayingSong() {
        checkSubtitleAndDetails(lyricsIncluded = false, label = "AI Transcribed")
    }

    @Test fun lyricsIncludedSubtitleOpensDetailsWithoutPlayingSong() {
        checkSubtitleAndDetails(lyricsIncluded = true, label = "Lyrics Included")
    }

    @Test fun songWithoutTranscriptionInformationHidesStatus() {
        compose.setContent {
            MusicTheme { TrackRow(Track(name = "song.mp3"), onClick = {}) }
        }
        compose.onNodeWithText("AI Transcribed").assertDoesNotExist()
        compose.onNodeWithText("Lyrics Included").assertDoesNotExist()
        compose.onNodeWithText("song").assertIsDisplayed()
        compose.onNodeWithText("Not transcribed").assertDoesNotExist()
        compose.onNodeWithContentDescription("Not transcribed", substring = true).assertDoesNotExist()
    }

    @Test fun libraryShowsKnownStatusesAndOmitsMissingRecords() {
        val record = mutableStateOf<Transcription?>(null)
        val track = Track(jobId = "source", name = "song.mp3")
        compose.setContent {
            MusicTheme {
                LibraryContent(
                    LibraryState(library = Library(jobs = listOf(Job(id = "source",
                        transcriptions = record.value?.let { mapOf(track.name to it) }.orEmpty()))),
                        tracks = TrackPage(files = listOf(track), total = 1)),
                    PlaybackState(connected = true), onBrowse = {}, onPlay = {}, onSearch = {}, onPage = {}
                ) { _, _ -> }
            }
        }
        compose.onNodeWithText("Not transcribed").assertDoesNotExist()
        compose.onNodeWithContentDescription("Not transcribed", substring = true).assertDoesNotExist()
        statuses.forEach { (transcription, label) ->
            compose.runOnIdle { record.value = transcription }
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test fun libraryShowsRefreshedInlineStatusesWithEmptyJobSummaries() {
        val track = Track(jobId = "source", name = "song.mp3")
        val state = mutableStateOf(LibraryState(library = Library(jobs = listOf(Job(id = "source"))),
            tracks = TrackPage(files = listOf(track), total = 1)))
        compose.setContent {
            MusicTheme {
                LibraryContent(state.value, PlaybackState(connected = true),
                    onBrowse = {}, onPlay = {}, onSearch = {}, onPage = {}) { _, _ -> }
            }
        }
        statuses.forEach { (transcription, label) ->
            compose.runOnIdle {
                state.value = state.value.withTrackPage(
                    TrackPage(files = listOf(track.copy(transcription = transcription)), total = 1))
            }
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test fun narrowRowsKeepClickableStatusIconsWithoutPlayingSongOrOpeningMenu() {
        val record = mutableStateOf<Transcription?>(null)
        var playCount = 0
        var menuCount = 0
        compose.setContent {
            MusicTheme {
                Box(Modifier.width(160.dp)) {
                    TrackRow(Track(name = "song.mp3", title = "A long song title", artist = "A long artist name"),
                        transcription = record.value, onClick = { playCount++ }) {
                        ToolButton(Icons.Rounded.MoreVert, "Song actions") { menuCount++ }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Not transcribed", substring = true).assertDoesNotExist()
        statuses.forEach { (transcription, label) ->
            compose.runOnIdle { record.value = transcription }
            compose.onNodeWithText(label).assertDoesNotExist()
            compose.onNodeWithContentDescription(label, substring = true).assertIsDisplayed().performClick()
            compose.onNode(isDialog()).assertIsDisplayed()
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithContentDescription("Close transcription details").performClick()
        }
        compose.runOnIdle {
            assertEquals(0, playCount)
            assertEquals(0, menuCount)
        }
        compose.onNodeWithContentDescription("Song actions").performClick()
        compose.onNodeWithText("A long song title").performClick()
        compose.runOnIdle {
            assertEquals(1, playCount)
            assertEquals(1, menuCount)
        }
    }

    @Test fun largeFontFallsBackToIconWithFullStatusDetails() {
        compose.setContent {
            MusicTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Box(Modifier.width(48.dp)) {
                        TranscriptionStatus(Transcription(status = "sent"))
                    }
                }
            }
        }
        compose.onNodeWithText("Transcription request sent").assertDoesNotExist()
        compose.onNodeWithContentDescription("Transcription request sent", substring = true)
            .assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
    }

    @Test fun unknownStatusPreservesServerStatusAndErrorInDetails() {
        compose.setContent {
            MusicTheme { TranscriptionStatus(Transcription(status = "processing", error = "Waiting for worker")) }
        }
        compose.onNodeWithText("Transcription status unknown").performClick()
        compose.onNodeWithText("Status: processing", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Waiting for worker", substring = true).assertIsDisplayed()
    }

    private val statuses = listOf(
        Transcription(status = "sent") to "Transcription request sent",
        Transcription(status = "transcribed") to "AI Transcribed",
        Transcription(status = "transcribed", lyricsIncluded = true) to "Lyrics Included",
        Transcription(status = "failed") to "Transcription failed",
        Transcription(status = "interrupted") to "Interrupted",
        Transcription(status = "processing") to "Transcription status unknown",
        Transcription() to "Transcription status unknown"
    )

    private fun checkSubtitleAndDetails(lyricsIncluded: Boolean, label: String) {
        var playCount = 0
        val transcription = Transcription(
            status = "transcribed",
            requestedAt = "2026-09-01T10:00:00Z",
            completedAt = "2026-09-01T10:05:00Z",
            lyricsIncluded = lyricsIncluded,
            options = SavedTranscriptionOptions(
                language = "vi", multilingual = true, noVocals = false,
                vietLyricsFallback = null, lyricsMode = if (lyricsIncluded) "align" else null
            )
        )
        compose.setContent {
            MusicTheme {
                TrackRow(Track(name = "song.mp3"), transcription = transcription, onClick = { playCount++ })
            }
        }
        compose.onNodeWithText(label).assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText(transcription.tooltip()).assertIsDisplayed()
        compose.onNodeWithText("Requested:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Finished:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Language: Vietnamese", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Multilingual: On", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No vocals (karaoke): Off", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Viet Lyrics Fallback: Service default", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Add lyrics: ${if (lyricsIncluded) "Yes" else "No"}", substring = true).assertIsDisplayed()
        if (lyricsIncluded) compose.onNodeWithText("Lyrics mode: Align", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, playCount) }
        compose.onNodeWithContentDescription("Close transcription details").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("song").performClick()
        compose.runOnIdle { assertEquals(1, playCount) }
    }
}
