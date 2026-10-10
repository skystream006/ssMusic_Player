package com.ssytdlp.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.ssytdlp.app.core.LyricLine
import com.ssytdlp.app.core.SongMetadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class LyricsEditorTest {
    private val original = SongMetadata(title = "Title", rating = 4,
        sylt = listOf(LyricLine(1.001, "First"), LyricLine(62.345, "Second")), uslt = "Plain\nlyrics")

    @Test fun unchangedOrRevertedDraftOmitsAllFields() {
        assertTrue(buildLyricsEditRequest(original, formatSyltForEditing(original.sylt), original.uslt).isEmpty())
        assertTrue(buildLyricsEditRequest(original, "\n${formatSyltForEditing(original.sylt)}\n", original.uslt).isEmpty())
        assertTrue(buildLyricsEditRequest(SongMetadata(), "", "").isEmpty())
    }

    @Test fun textEditsPreserveEveryTimestampIncludingMillisecondsAndExtraPrecision() {
        val times = listOf(0.001, 1.001, 1.234567, 59.999, 3600.007, 4294967.295)
        val metadata = original.copy(sylt = times.map { LyricLine(it, "Original") })
        val formatted = formatSyltForEditing(metadata.sylt)
        assertTrue(formatted.startsWith("[00:00:00.001]"))
        assertTrue(formatted.contains("[01:00:00.007]"))
        assertTrue(formatted.endsWith("[1193:02:47.295] Original"))
        val patch = buildLyricsEditRequest(metadata, formatted.replace("Original", "Changed"), metadata.uslt)
        assertEquals(setOf("sylt"), patch.keys)
        assertEquals(times, patch.getValue("sylt").jsonArray.map { it.jsonObject.getValue("time").jsonPrimitive.double })
    }

    @Test fun onlyPlainLyricsChangeDoesNotSendOrValidateSynchronizedLyrics() {
        val metadata = original.copy(sylt = listOf(LyricLine(Double.NaN, "Legacy")))
        val patch = buildLyricsEditRequest(metadata, formatSyltForEditing(metadata.sylt), "New plain lyrics")
        assertEquals(JsonObject(mapOf("uslt" to JsonPrimitive("New plain lyrics"))), patch)
    }

    @Test fun timestampAndLineEditsSendOnlySyltAndPreserveSuppliedOrder() {
        val patch = buildLyricsEditRequest(original,
            "[00:01:03.007] Replaced\n[00:00:00.000] Added", original.uslt)
        assertEquals(setOf("sylt"), patch.keys)
        assertEquals(listOf(LyricLine(63.007, "Replaced"), LyricLine(0.0, "Added")), patchLines(patch))
    }

    @Test fun bothModesCanBeChangedOrClearedIndependently() {
        val sylt = formatSyltForEditing(original.sylt)
        assertEquals(setOf("sylt"), buildLyricsEditRequest(original, "", original.uslt).keys)
        assertEquals(setOf("uslt"), buildLyricsEditRequest(original, sylt, "").keys)
        val patch = buildLyricsEditRequest(original, "", "")
        assertEquals(JsonObject(mapOf("sylt" to JsonArray(emptyList()), "uslt" to JsonPrimitive(""))), patch)
        val both = buildLyricsEditRequest(original, "[00:00:00.010] New", "New plain")
        assertEquals(setOf("sylt", "uslt"), both.keys)
    }

    @Test fun blankRowsAreIgnoredButTimestampedEmptyLyricsAndSpacesArePreserved() {
        val source = "\n \n[00:00:00.000]\n[00:00:00.001] \n[00:00:01.000]   padded  \n"
        assertEquals(listOf(LyricLine(0.0, ""), LyricLine(0.001, ""), LyricLine(1.0, "  padded  ")),
            parseSyltForEditing(source))
        assertTrue(parseSyltForEditing("\r\n \t\n").isEmpty())
    }

    @Test fun embeddedNewlinesCarriageReturnsAndLiteralBackslashesRoundTrip() {
        val lines = listOf(LyricLine(1.003, "First\nsecond\r\nthird\\n\\path\\"))
        assertEquals(lines, parseSyltForEditing(formatSyltForEditing(lines)))
        val metadata = original.copy(sylt = lines + LyricLine(2.0, "Other"))
        val patch = buildLyricsEditRequest(metadata,
            formatSyltForEditing(metadata.sylt).replace("Other", "Changed"), metadata.uslt)
        assertEquals(lines.single(), patchLines(patch).first())
    }

    @Test fun invalidOrOutOfRangeTimestampsAreRejected() {
        listOf(
            "Missing timestamp", "[NaN] text", "[Infinity] text", "[-00:00:01.000] text",
            "[00:60:00.000] text", "[00:00:60.000] text", "[1193:02:47.296] text",
            "[1193:02:47.29500001] text", "[99999999999999999999:00:00.000] text",
            "[00:00:01.-001] text", "[00:00:01] text", "[00:00:01.000] text\u0000"
        ).forEach { source ->
            assertThrows(source, IllegalArgumentException::class.java) {
                buildLyricsEditRequest(original, source, original.uslt)
            }
        }
    }

    @Test fun lineLimitIncludesTimedEmptyLyrics() {
        val maximum = List(10_000) { "[00:00:00.000]" }.joinToString("\n")
        assertEquals(10_000, parseSyltForEditing(maximum).size)
        assertThrows(IllegalArgumentException::class.java) { parseSyltForEditing("$maximum\n[00:00:00.000]") }
    }

    @Test fun syltTextLimitIsCombinedAcrossLinesAndExcludesTimestampSyntax() {
        val text = "x".repeat(50_000)
        val maximum = "[00:00:00.000] $text\n[00:00:01.000] $text"
        assertEquals(100_000, parseSyltForEditing(maximum).sumOf { it.text.length })
        assertThrows(IllegalArgumentException::class.java) { parseSyltForEditing("${maximum}x") }
    }

    @Test fun usltLimitsAndNulAreValidatedWithoutTrimmingWhitespace() {
        val sylt = formatSyltForEditing(original.sylt)
        assertEquals(JsonPrimitive("x".repeat(100_000)),
            buildLyricsEditRequest(original, sylt, "x".repeat(100_000))["uslt"])
        listOf("x".repeat(100_001), "Text\u0000").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { buildLyricsEditRequest(original, sylt, text) }
        }
        assertEquals(JsonPrimitive(" \n "), buildLyricsEditRequest(original, sylt, " \n ")["uslt"])
    }

    private fun patchLines(patch: JsonObject) = patch.getValue("sylt").jsonArray.map {
        val line = it.jsonObject
        LyricLine(line.getValue("time").jsonPrimitive.double, line.getValue("text").jsonPrimitive.content)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
class LyricsEditorDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val original = SongMetadata(sylt = listOf(LyricLine(1.001, "Timed")), uslt = "Plain")
    private val busy = mutableStateOf(false)
    private val visible = mutableStateOf(true)
    private val requests = mutableListOf<JsonObject>()
    private var dismissals = 0

    @Test fun dialogFillsScreenAndBothEditorsUseAvailableHeight() {
        show()
        assertDialogFillsScreen()
        compose.onNodeWithTag("sylt-editor").assertHeightIsAtLeast(300.dp)
        compose.onNodeWithText("USLT").assertIsDisplayed().performClick()
        compose.onNodeWithTag("uslt-editor").assertHeightIsAtLeast(300.dp)
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test fun longLyricsKeepEditorsBoundedAndCanStillBeClearedAndSaved() {
        val longLyrics = original.copy(
            uslt = List(20_000) { "x" }.joinToString("\n"),
            sylt = List(10_000) { LyricLine(it.toDouble(), "x") })
        show(preferUslt = true, value = longLyrics)
        assertTrue(compose.onNodeWithTag("uslt-editor").getUnclippedBoundsInRoot().height <= 800.dp)
        compose.onNodeWithText("Clear USLT").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("SYLT").assertIsDisplayed().performClick()
        assertTrue(compose.onNodeWithTag("sylt-editor").getUnclippedBoundsInRoot().height <= 800.dp)
        compose.onNodeWithText("Clear SYLT").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(JsonObject(mapOf("sylt" to JsonArray(emptyList()), "uslt" to JsonPrimitive(""))), requests.single())
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun landscapeKeepsTabsAndActionsVisibleWhileErrorsScroll() {
        show()
        assertDialogFillsScreen()
        compose.onNodeWithTag("sylt-editor").performTextReplacement("[00:60:00.000] Invalid")
        compose.onNodeWithText("SYLT line 1: minutes and seconds must be 00–59.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("USLT").assertIsDisplayed().performClick()
        compose.onNodeWithText("SYLT").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sylt-editor").performTextReplacement("[00:01:00.000] Corrected")
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(setOf("sylt"), requests.single().keys) }
    }

    @Test
    @Config(qualifiers = "w360dp-h320dp")
    fun shortViewportAllowsScrollingWithoutHidingSaveAndCancel() {
        show()
        assertDialogFillsScreen()
        compose.onNodeWithText("Clear SYLT").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("USLT").assertIsDisplayed().performClick()
        compose.onNodeWithText("Clear USLT").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
    }

    @Test fun unchangedDraftCannotSaveAndCancelDoesNotSave() {
        show()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(requests.isEmpty())
            assertEquals(1, dismissals)
        }
    }

    @Test fun switchingModesRetainsBothEditsAndSaveWaitsForParentToDismiss() {
        show()
        compose.onNodeWithTag("sylt-editor").performTextReplacement("[00:00:02.345] Changed\n[00:00:03.001] Added")
        compose.onNodeWithText("USLT").performClick()
        compose.onNodeWithTag("uslt-editor").performTextReplacement("Changed plain")
        compose.onNodeWithText("SYLT").performClick()
        compose.onNodeWithTag("sylt-editor").assertTextContains("[00:00:02.345] Changed\n[00:00:03.001] Added")
        compose.onNodeWithText("USLT").performClick()
        compose.onNodeWithTag("uslt-editor").assertTextContains("Changed plain")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(setOf("sylt", "uslt"), requests.single().keys)
            assertEquals(2.345, requests.single().getValue("sylt").jsonArray.first()
                .jsonObject.getValue("time").jsonPrimitive.double, 0.0)
            assertEquals(JsonPrimitive("Changed plain"), requests.single()["uslt"])
            assertEquals(0, dismissals)
        }
    }

    @Test fun cancelDiscardsDraftAndReopeningStartsFromOriginal() {
        show(preferUslt = true)
        compose.onNodeWithTag("uslt-editor").performTextReplacement("Discard this")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("uslt-editor").assertTextContains("Plain")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(requests.isEmpty())
            assertEquals("Plain", original.uslt)
        }
    }

    @Test fun invalidHiddenModeBlocksSaveUntilCorrected() {
        show()
        compose.onNodeWithTag("sylt-editor").performTextReplacement("[00:60:00.000] Invalid")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("USLT").performClick()
        compose.onNodeWithTag("uslt-editor").performTextReplacement("Valid plain")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("SYLT line 1: minutes and seconds must be 00–59.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SYLT", substring = false).performClick()
        compose.onNodeWithTag("sylt-editor").performTextReplacement("[00:01:00.000] Corrected")
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun clearBothModesSendsExplicitEmptyValues() {
        show()
        compose.onNodeWithText("Clear SYLT").performScrollTo().performClick()
        compose.onNodeWithText("USLT").performClick()
        compose.onNodeWithText("Clear USLT").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(JsonObject(mapOf("sylt" to JsonArray(emptyList()), "uslt" to JsonPrimitive(""))), requests.single())
        }
    }

    @Test fun busyDisablesEditingSwitchingAndSavingButAllowsBackgroundWorkAndRetainsDraft() {
        show(preferUslt = true)
        compose.onNodeWithTag("uslt-editor").performTextReplacement("Draft")
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("uslt-editor").assertIsNotEnabled()
        compose.onNodeWithText("SYLT").assertIsNotEnabled()
        compose.onNodeWithText("USLT").assertIsNotEnabled()
        compose.onNodeWithText("Clear USLT").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Saving...").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Continue in background").assertIsEnabled()
        compose.onNode(isDialog()).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(requests.isEmpty())
            assertEquals(0, dismissals)
            busy.value = false
        }
        compose.onNodeWithTag("uslt-editor").assertIsEnabled().assertTextContains("Draft")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(setOf("uslt"), requests.single().keys) }
    }

    @Test fun savingCanContinueAfterDismissingTheEditor() {
        show(preferUslt = true)
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithText("Continue in background").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun draftsAndSelectedModeSurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { LyricsEditorDialog(original, false, dismiss = {}, save = {}) }
        }
        compose.onNodeWithTag("sylt-editor").performTextReplacement("[00:00:04.001] Draft")
        compose.onNodeWithText("USLT").performClick()
        compose.onNodeWithTag("uslt-editor").performTextReplacement("Plain draft")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("uslt-editor").assertTextContains("Plain draft")
        compose.onNodeWithText("SYLT").performClick()
        compose.onNodeWithTag("sylt-editor").assertTextContains("[00:00:04.001] Draft")
    }

    private fun assertDialogFillsScreen() {
        val bounds = compose.onNode(isDialog()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val screen = compose.activity.resources.displayMetrics
        assertEquals(screen.widthPixels.toFloat(), bounds.width, 1f)
        assertEquals(screen.heightPixels.toFloat(), bounds.height, 1f)
    }

    private fun show(preferUslt: Boolean = false, value: SongMetadata = original) {
        compose.setContent {
            MaterialTheme {
                if (visible.value) LyricsEditorDialog(value, preferUslt, busy.value,
                    dismiss = { dismissals++; visible.value = false }, save = { requests += it })
            }
        }
    }
}
