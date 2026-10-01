package com.ssytdlp.app

import android.animation.ValueAnimator
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ssytdlp.app.core.ApiJson
import com.ssytdlp.app.core.SongMetadata
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TranscriptionControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setDurationScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Any?>(ValueAnimator::class.java, "setDurationScale",
            ClassParameter.from(Float::class.javaPrimitiveType, scale))
    }

    @Before fun disableAnimations() {
        compose.mainClock.autoAdvance = false
        setDurationScale(0f)
    }
    @After fun enableAnimations() = setDurationScale(1f)

    @Test fun onlyActiveHealthEnablesTranscription() {
        assertFalse(null.transcriptionActive)
        listOf("{}", """{"transcription":null}""", """{"transcription":{"status":"inactive"}}""",
            """{"transcription":{"status":"unknown"}}""").forEach {
            assertFalse(ApiJson.parseToJsonElement(it).jsonObject.transcriptionActive)
        }
        assertTrue(ApiJson.parseToJsonElement("""{"transcription":{"status":"active"}}""").jsonObject.transcriptionActive)
    }

    @Test fun suppliedLyricsDefaultToAlign() {
        assertEquals("align", TranscriptionOptions().lyricsMode)
        assertEquals("align", TranscriptionOptions(addLyrics = true, lyrics = "Line").toRequestBody()["lyrics_mode"]?.jsonPrimitive?.content)
        compose.setContent { MusicTheme { TranscribeDialog({}, {}) } }
        compose.onNodeWithText("Add lyrics").performScrollTo()
        compose.onNodeWithText("Add lyrics").performClick()
        compose.onNodeWithText("Align").assertExists()
    }

    @Test fun lockHidesEveryOptionAndSubmitsOnlyOnConfirmation() {
        var transcribed = 0
        var locked = 0
        compose.setContent { MusicTheme { TranscribeDialog({}, { transcribed++ }, lock = { locked++ }) } }
        compose.onNodeWithText("Add lyrics").performScrollTo()
        compose.onNodeWithText("Add lyrics").performClick()
        compose.onNodeWithText("Align").assertExists()
        compose.onNodeWithText("Transcribe").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Lock transcription").performClick()
        listOf("Add lyrics", "Auto-detect", "Multilingual", "Viet Lyrics Fallback", "Align").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(0, locked) }
        compose.onNodeWithText("Lock transcription").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, locked)
            assertEquals(0, transcribed)
        }
    }

    @Test fun cancelAndUnlockKeepOptionsWithoutSavingLock() {
        var locked = 0
        var dismissed = 0
        compose.setContent { MusicTheme { TranscribeDialog({ dismissed++ }, {}, lock = { locked++ }) } }
        compose.onNodeWithText("Multilingual").performClick()
        compose.onNodeWithContentDescription("Lock transcription").performClick()
        compose.onNodeWithContentDescription("Unlock transcription").performClick()
        compose.onNodeWithText("Multilingual").assertIsOn()
        compose.onNodeWithContentDescription("Lock transcription").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(0, locked)
            assertEquals(1, dismissed)
        }
    }

    @Test fun inactiveServiceDisablesTranscriptionButAllowsLocking() {
        var locked = 0
        compose.setContent { MusicTheme { TranscribeDialog({}, {}, available = false, lock = { locked++ }) } }
        compose.onNodeWithText("Transcribe").assertIsNotEnabled()
        compose.onNodeWithText(INACTIVE_TRANSCRIPTION_MESSAGE).assertExists()
        compose.onNodeWithContentDescription("Lock transcription").performClick()
        compose.onNodeWithText("Lock transcription").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, locked) }
    }

    @Test fun busyDialogDisablesLockButtonAndSubmit() {
        compose.setContent { MusicTheme { TranscribeDialog({}, {}, busy = true) } }
        compose.onNodeWithContentDescription("Lock transcription").assertIsNotEnabled()
        compose.onNodeWithText("Transcribe").assertIsNotEnabled()
    }

    @Test fun inactiveMenuHasExplanationAndLockedSongsHaveNoTranscribeAction() {
        val locked = mutableStateOf(false)
        val available = mutableStateOf(false)
        var clicks = 0
        compose.setContent { MusicTheme { TranscribeMenuItem(locked.value, available.value) { clicks++ } } }
        compose.onNodeWithText("Transcribe lyrics").assertIsNotEnabled()
        compose.onNodeWithContentDescription(INACTIVE_TRANSCRIPTION_MESSAGE).assertExists()
        compose.onNodeWithText("Transcribe lyrics").performClick()
        compose.runOnIdle { assertEquals(0, clicks); available.value = true }
        compose.onNodeWithText("Transcribe lyrics").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicks); locked.value = true }
        compose.onNodeWithText("Transcribe lyrics").assertDoesNotExist()
    }

    @Test fun editDialogCanLockAndUnlockWithoutChangingLyrics() {
        val original = SongMetadata(title = "Song", uslt = "Keep these lyrics", transcriptionLocked = true)
        val value = mutableStateOf(original)
        var saved: SongMetadata? = null
        compose.activity.setContent {
            MusicTheme { MetadataDialog(value.value, { value.value = it }, dismiss = {}, save = { saved = it }) }
        }
        compose.onNodeWithContentDescription("Unlock transcription").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(original.copy(transcriptionLocked = false), saved) }
        compose.onNodeWithContentDescription("Lock transcription").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(original, saved) }
    }

    @Test fun ratingDialogDoesNotOfferLocking() {
        compose.setContent {
            MusicTheme { MetadataDialog(SongMetadata(), {}, ratingOnly = true, dismiss = {}, save = {}) }
        }
        compose.onNodeWithContentDescription("Lock transcription").assertDoesNotExist()
    }
}
