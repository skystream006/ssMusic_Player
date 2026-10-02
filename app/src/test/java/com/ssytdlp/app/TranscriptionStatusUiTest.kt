package com.ssytdlp.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TranscriptionStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun requestSentIconSpinsInBothLayoutsAndStopsForOtherStatuses() {
        val record = mutableStateOf<Transcription?>(Transcription(status = "sent"))
        val iconOnly = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MusicTheme(waveAppearance = false) {
                TranscriptionStatus(record.value, iconOnly = iconOnly.value)
            }
        }
        fun update(transcription: Transcription?, compact: Boolean) {
            compose.runOnIdle {
                record.value = transcription
                iconOnly.value = compact
                Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeBy(100)
        }
        fun assertMotion(label: String, spinning: Boolean) {
            val before = captureStatus(label)
            compose.mainClock.advanceTimeBy(256)
            val after = captureStatus(label)
            assertEquals("Unexpected icon motion for $label", spinning, !before.sameAs(after))
            before.recycle()
            after.recycle()
        }
        listOf(false, true).forEach { compact ->
            update(Transcription(status = "sent"), compact)
            assertMotion("Transcription request sent", spinning = true)
            compose.mainClock.advanceTimeBy(1200)
            assertMotion("Transcription request sent", spinning = true)
            statuses.drop(1).forEach { (transcription, label) ->
                update(transcription, compact)
                assertMotion(label, spinning = false)
            }
            update(Transcription(status = "sent",
                options = SavedTranscriptionOptions(noVocalsOnly = true)), compact)
            assertMotion("No-vocals request sent", spinning = true)
            update(null, compact)
            compose.onNodeWithContentDescription("request sent", substring = true).assertDoesNotExist()
        }
    }

    private fun captureStatus(label: String): Bitmap {
        val bounds = compose.onNodeWithContentDescription(label, substring = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // Draw the host directly; captureToImage() can hang with Robolectric's paused clock.
        return compose.runOnIdle {
            val view = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
                .also {
                    val canvas = Canvas(it)
                    canvas.translate(-bounds.left, -bounds.top)
                    view.draw(canvas)
                }
        }
    }

    @Test fun aiTranscribedIconOpensDetailsWithoutPlayingSong() {
        checkIconAndDetails(lyricsIncluded = false, label = "AI Transcribed")
    }

    @Test fun lyricsIncludedIconOpensDetailsWithoutPlayingSong() {
        checkIconAndDetails(lyricsIncluded = true, label = "Lyrics Included")
    }

    @Test fun noVocalsOnlyStatusOpensInstrumentalDetailsWithoutClaimingTranscribedLyrics() {
        var playCount = 0
        compose.setContent {
            MusicTheme(waveAppearance = false) {
                TrackRow(Track(name = "song.mp3"), onClick = { playCount++ },
                    transcription = Transcription(status = "transcribed", lyricsIncluded = true,
                        options = SavedTranscriptionOptions(noVocalsOnly = true)))
            }
        }
        compose.onNodeWithContentDescription("No-vocals version generated", substring = true)
            .assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.onNodeWithText("Generate NoVocals Only: On", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Lyrics: Unchanged (not transcribed)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("AI Transcribed").assertDoesNotExist()
        compose.onNodeWithText("Lyrics Included").assertDoesNotExist()
        compose.onNodeWithText("Add lyrics:", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, playCount) }
    }

    @Test fun songWithoutTranscriptionInformationHidesStatus() {
        compose.setContent {
            MusicTheme(waveAppearance = false) { TrackRow(Track(name = "song.mp3"), onClick = {}) }
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
            MusicTheme(waveAppearance = false) {
                LibraryContent(
                    LibraryState(library = Library(jobs = listOf(Job(id = "source",
                        transcriptions = record.value?.let { mapOf(track.name to it) }.orEmpty()))),
                        tracks = TrackPage(files = listOf(track), total = 1)),
                    PlaybackState(connected = true), onPlay = {}, onPage = {}
                ) { _, _ -> }
            }
        }
        compose.onNodeWithText("Not transcribed").assertDoesNotExist()
        compose.onNodeWithContentDescription("Not transcribed", substring = true).assertDoesNotExist()
        statuses.forEach { (transcription, label) ->
            compose.runOnIdle { record.value = transcription }
            assertIconLeftOfRating(label)
        }
    }

    @Test fun libraryShowsRefreshedInlineStatusesWithEmptyJobSummaries() {
        val track = Track(jobId = "source", name = "song.mp3")
        val state = mutableStateOf(LibraryState(library = Library(jobs = listOf(Job(id = "source"))),
            tracks = TrackPage(files = listOf(track), total = 1)))
        compose.setContent {
            MusicTheme(waveAppearance = false) {
                LibraryContent(state.value, PlaybackState(connected = true),
                    onPlay = {}, onPage = {}) { _, _ -> }
            }
        }
        statuses.forEach { (transcription, label) ->
            compose.runOnIdle {
                state.value = state.value.withTrackPage(
                    TrackPage(files = listOf(track.copy(transcription = transcription)), total = 1))
            }
            assertIconLeftOfRating(label)
        }
    }

    @Test fun narrowRowsKeepClickableStatusIconsWithoutPlayingSongOrOpeningMenu() {
        val record = mutableStateOf<Transcription?>(null)
        val width = mutableStateOf(160.dp)
        var playCount = 0
        var menuCount = 0
        var ratingCount = 0
        compose.setContent {
            MusicTheme(waveAppearance = false) {
                Box(Modifier.width(width.value)) {
                    TrackRow(Track(name = "song.mp3", title = "A long song title", artist = "A long artist name"),
                        transcription = record.value, onRatingClick = { ratingCount++ }, onClick = { playCount++ }) {
                        ToolButton(Icons.Rounded.MoreVert, "Song actions") { menuCount++ }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Not transcribed", substring = true).assertDoesNotExist()
        statuses.forEach { (transcription, label) ->
            compose.runOnIdle { record.value = transcription }
            assertIconLeftOfRating(label)
            compose.onNodeWithContentDescription(label, substring = true).assertIsDisplayed().performClick()
            compose.onNode(isDialog()).assertIsDisplayed()
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onNodeWithContentDescription("Close transcription details").performClick()
        }
        compose.runOnIdle {
            assertEquals(0, playCount)
            assertEquals(0, menuCount)
            assertEquals(0, ratingCount)
        }
        compose.onNodeWithContentDescription("Rating: 0 out of 5").performClick()
        compose.onNodeWithContentDescription("Song actions").performClick()
        compose.runOnIdle { width.value = 360.dp }
        compose.onNodeWithText("A long song title").performClick()
        compose.runOnIdle {
            assertEquals(1, playCount)
            assertEquals(1, menuCount)
            assertEquals(1, ratingCount)
        }
    }

    @Test fun largeFontFallsBackToIconWithFullStatusDetails() {
        compose.setContent {
            MusicTheme(waveAppearance = false) {
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

    private fun assertIconLeftOfRating(label: String) {
        compose.onNodeWithText(label).assertDoesNotExist()
        val status = compose.onNodeWithContentDescription(label, substring = true)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val rating = compose.onNodeWithContentDescription("Rating: 0 out of 5")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(status.right <= rating.left)
        assertEquals((rating.top + rating.bottom) / 2, (status.top + status.bottom) / 2)
    }

    private fun checkIconAndDetails(lyricsIncluded: Boolean, label: String) {
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
            MusicTheme(waveAppearance = false) {
                TrackRow(Track(name = "song.mp3"), transcription = transcription, onClick = { playCount++ })
            }
        }
        assertIconLeftOfRating(label)
        compose.onNodeWithContentDescription(label, substring = true).performClick()
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
