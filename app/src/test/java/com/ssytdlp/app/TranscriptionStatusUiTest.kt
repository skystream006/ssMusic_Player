package com.ssytdlp.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ssytdlp.app.core.SavedTranscriptionOptions
import com.ssytdlp.app.core.Track
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

    @Test fun untranscribedSongHasNoTranscriptionSubtitle() {
        compose.setContent {
            MusicTheme { TrackRow(Track(name = "song.mp3"), onClick = {}) }
        }
        compose.onNodeWithText("AI Transcribed").assertDoesNotExist()
        compose.onNodeWithText("Lyrics Included").assertDoesNotExist()
        compose.onNodeWithText("song").assertIsDisplayed()
    }

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
