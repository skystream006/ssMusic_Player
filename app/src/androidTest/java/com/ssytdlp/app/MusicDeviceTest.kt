package com.ssytdlp.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun folderDialogRequiresANameAndTrimsItBeforeSaving() {
        var saved: String? = null
        compose.setContent { MusicTheme { NameDialog("New folder", "", {}, { saved = it }) } }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("  Road trip  ")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("Road trip", saved) }
    }

    @Test fun transcriptionDefaultsMatchServerDialog() {
        var submitted: TranscriptionOptions? = null
        compose.setContent { MusicTheme { TranscribeDialog({}, { submitted = it }) } }
        compose.onNodeWithText("Transcribe Song").assertIsDisplayed()
        compose.onNodeWithText("Auto-detect").assertIsDisplayed()
        listOf("Multilingual", "Create no-vocals version [Karaoke version]", "Viet Lyrics Fallback", "Add lyrics").forEach {
            compose.onNodeWithText(it).assertIsOff()
        }
        compose.onNodeWithText("Lyrics mode").assertDoesNotExist()
        compose.onNodeWithText("Transcribe").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(TranscriptionOptions(), submitted) }
    }

    @Test fun vietnameseFallbackLocksLanguageAndKeepsVietnameseWhenDisabled() {
        var submitted: TranscriptionOptions? = null
        compose.setContent { MusicTheme { TranscribeDialog({}, { submitted = it }) } }
        compose.onNodeWithText("Auto-detect").performClick()
        compose.onNodeWithText("English").performScrollTo().performClick()
        compose.onNodeWithText("Viet Lyrics Fallback").performScrollTo().performClick()
        compose.onNodeWithText("Vietnamese").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Viet Lyrics Fallback").performScrollTo().performClick()
        compose.onNodeWithText("Vietnamese").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Multilingual").performScrollTo().performClick()
        compose.onNodeWithText("Create no-vocals version [Karaoke version]").performScrollTo().performClick()
        compose.onNodeWithText("Transcribe").performClick()
        compose.runOnIdle {
            assertEquals(TranscriptionOptions(language = "vi", multilingual = true, noVocals = true), submitted)
        }
    }

    @Test fun suppliedLyricsRequireTextAndSupportEveryMode() {
        var submitted: TranscriptionOptions? = null
        compose.setContent { MusicTheme { TranscribeDialog({}, { submitted = it }) } }
        compose.onNodeWithText("Add lyrics").performScrollTo().performClick()
        compose.onNodeWithText("Transcribe").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(" \n ")
        compose.onNodeWithText("Transcribe").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("  Known words  ")
        compose.onNodeWithText("Transcribe").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("prompt", submitted?.lyricsMode)
            assertEquals("Known words", submitted?.toRequestBody()?.get("lyrics")?.let {
                (it as kotlinx.serialization.json.JsonPrimitive).content
            })
        }
        compose.onNodeWithText("Prompt").performScrollTo().performClick()
        compose.onNodeWithText("Align").performClick()
        compose.onNodeWithText("Transcribe").performClick()
        compose.runOnIdle { assertEquals("align", submitted?.lyricsMode) }
        compose.onNodeWithText("Align").performScrollTo().performClick()
        compose.onNodeWithText("Correct").performClick()
        compose.onNodeWithText("Transcribe").performClick()
        compose.runOnIdle { assertEquals("correct", submitted?.lyricsMode) }
        compose.onNodeWithText("Add lyrics").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Lyrics mode").assertDoesNotExist()
        compose.onNodeWithText("Transcribe").performClick()
        compose.runOnIdle { assertEquals(TranscriptionOptions().toRequestBody(), submitted?.toRequestBody()) }
    }

    @Test fun cancelDoesNotSubmitTranscription() {
        var submissions = 0
        var dismissals = 0
        compose.setContent { MusicTheme { TranscribeDialog({ dismissals++ }, { submissions++ }) } }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(0, submissions)
            assertEquals(1, dismissals)
        }
    }

    @Test fun busyTranscriptionDisablesActionsAndOptions() {
        compose.setContent { MusicTheme { TranscribeDialog({}, {}, busy = true) } }
        compose.onNodeWithText("Auto-detect").assertIsNotEnabled()
        compose.onNodeWithText("Multilingual").assertIsNotEnabled()
        compose.onNodeWithText("Viet Lyrics Fallback").assertIsNotEnabled()
        compose.onNodeWithText("Add lyrics").assertIsNotEnabled()
        compose.onNodeWithText("Transcribe").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }
}