package com.ssytdlp.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.ssytdlp.app.core.LyricLine
import com.ssytdlp.app.core.SongMetadata
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val metadata = mutableStateOf<SongMetadata?>(SongMetadata(
        sylt = List(40) { LyricLine(it * 10.0, "Line $it") },
        uslt = "Unsynchronized lyrics"
    ))
    private val position = mutableLongStateOf(0)
    private val trackKey = mutableStateOf("first-track")
    private val error = mutableStateOf<String?>(null)
    private val video = mutableStateOf(false)
    private val preferUslt = mutableStateOf(false)
    private val textScale = mutableFloatStateOf(1f)
    private val seeks = mutableListOf<Long>()

    @Test fun pinchResizesSynchronizedLyricsAndLineSpacingWithoutSeeking() {
        showLyrics()
        val activeStyle = textStyle("Line 0")
        val inactiveStyle = textStyle("Line 1")
        val timestampStyle = textStyle("00:00:00")
        pinchLyrics(zoomIn = true)
        val scale = compose.runOnIdle {
            assertTrue(seeks.isEmpty())
            assertTrue(textScale.floatValue > 1f)
            textScale.floatValue
        }
        assertScaledStyle(activeStyle, textStyle("Line 0"), scale)
        assertScaledStyle(inactiveStyle, textStyle("Line 1"), scale)
        assertEquals(timestampStyle, textStyle("00:00:00"))
        compose.onNodeWithText("Line 0").performClick()
        compose.runOnIdle {
            assertEquals(listOf(0L), seeks)
            position.longValue = 300_000
        }
        compose.onNodeWithText("Line 30").assertIsDisplayed()
    }

    @Test fun pinchInAndOutResizesPlainLyricsAndKeepsThemScrollable() {
        preferUslt.value = true
        metadata.value = metadata.value!!.copy(uslt = List(80) { "Plain lyric $it" }.joinToString("\n"))
        val text = metadata.value!!.uslt!!
        showLyrics()
        val initialStyle = textStyle(text)
        pinchLyrics(zoomIn = true)
        val enlarged = compose.runOnIdle { textScale.floatValue }
        assertTrue(enlarged > 1f)
        assertScaledStyle(initialStyle, textStyle(text), enlarged)
        pinchLyrics(zoomIn = false)
        val reduced = compose.runOnIdle { textScale.floatValue }
        assertTrue(reduced < enlarged)
        assertScaledStyle(initialStyle, textStyle(text), reduced)
        compose.onNodeWithTag("lyrics").performTouchInput { swipeUp(durationMillis = 1_000) }
        val scroll = compose.onNodeWithTag("lyrics").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(scroll.value() > 0f)
        compose.runOnIdle {
            assertEquals(reduced, textScale.floatValue, 0f)
            assertTrue(seeks.isEmpty())
        }
    }

    @Test fun zoomIsRetainedWhenLyricsSourceOrTrackChanges() {
        showLyrics()
        pinchLyrics(zoomIn = true)
        val enlargedStyle = textStyle("Line 0")
        val scale = compose.runOnIdle {
            preferUslt.value = true
            textScale.floatValue
        }
        val plainStyle = textStyle("Unsynchronized lyrics")
        compose.runOnIdle {
            textScale.floatValue = 1f
        }
        assertScaledStyle(textStyle("Unsynchronized lyrics"), plainStyle, scale)
        compose.runOnIdle {
            textScale.floatValue = scale
            preferUslt.value = false
            trackKey.value = "second-track"
        }
        assertEquals(enlargedStyle, textStyle("Line 0"))
    }

    @Test fun zoomDoesNotResumeFollowingAfterManualScroll() {
        showLyrics()
        scrollAway()
        compose.runOnIdle {
            assertEquals(1f, textScale.floatValue, 0f)
            position.longValue = 300_000
        }
        pinchLyrics(zoomIn = true)
        compose.onNodeWithText("Line 30").assertIsNotDisplayed()
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
    }

    @Test fun fallbackMessagesDoNotZoom() {
        metadata.value = null
        showLyrics()
        val initialStyle = textStyle("Loading lyrics...")
        pinchLyrics(zoomIn = true)
        assertEquals(initialStyle, textStyle("Loading lyrics..."))
        compose.runOnIdle { assertEquals(1f, textScale.floatValue, 0f) }
    }

    @Test fun synchronizedLyricsShowTheirTimestamps() {
        showLyrics()
        compose.onNodeWithText("00:00:00").assertIsDisplayed()
        compose.onNodeWithTag("lyrics").performScrollToIndex(20)
        compose.onNodeWithText("00:03:20").assertIsDisplayed()
    }

    @Test fun unsynchronizedLyricsAreUsedOnlyWhenSynchronizedLyricsAreAbsent() {
        showLyrics()
        compose.onNodeWithText("Line 0").assertIsDisplayed()
        compose.onNodeWithText("Unsynchronized lyrics").assertDoesNotExist()
        compose.runOnIdle { metadata.value = metadata.value!!.copy(sylt = emptyList()) }
        compose.onNodeWithText("Unsynchronized lyrics").assertIsDisplayed()
        compose.onNodeWithText("Line 0").assertDoesNotExist()
    }

    @Test fun plainLyricsCanBeSelectedWithoutSeekingAndSwitchBackToFollowing() {
        showLyrics()
        compose.runOnIdle { preferUslt.value = true }
        compose.onNodeWithText("Unsynchronized lyrics").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("Line 0").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(seeks.isEmpty())
            position.longValue = 200_000
            preferUslt.value = false
        }
        compose.onNodeWithText("Line 20").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(200_000L), seeks) }
    }

    @Test fun missingPlainLyricsFallBackToSynchronizedLyricsEvenWhenPreferred() {
        preferUslt.value = true
        metadata.value = metadata.value!!.copy(uslt = " ")
        showLyrics()
        compose.onNodeWithText("Line 0").assertIsDisplayed()
    }

    @Test fun loadingErrorEmptyAndVideoFallbacksArePreserved() {
        metadata.value = null
        showLyrics()
        compose.onNodeWithText("Loading lyrics...").assertIsDisplayed()
        compose.runOnIdle { error.value = "Lyrics request failed" }
        compose.onNodeWithText("Lyrics request failed").assertIsDisplayed()
        compose.runOnIdle {
            error.value = null
            video.value = true
        }
        compose.onNodeWithText("No lyrics available").assertIsDisplayed()
        compose.runOnIdle {
            video.value = false
            metadata.value = SongMetadata(uslt = " ")
        }
        compose.onNodeWithText("No lyrics available").assertIsDisplayed()
    }

    @Test fun followsInitiallyAndContinuesWhenNewHighlightedLineIsOffscreen() {
        position.longValue = 200_000
        showLyrics()
        compose.onNodeWithText("Line 20").assertIsDisplayed()
        compose.onNodeWithText("Line 0").assertIsNotDisplayed()
        compose.runOnIdle { position.longValue = 300_000 }
        compose.onNodeWithText("Line 30").assertIsDisplayed()
        compose.runOnIdle { position.longValue = 50_000 }
        compose.onNodeWithText("Line 5").assertIsDisplayed()
    }

    @Test fun manualScrollOutOfViewCancelsFollowingUntilALyricIsTapped() {
        showLyrics()
        scrollAway()
        compose.onNodeWithText("Line 0").assertIsNotDisplayed()
        compose.runOnIdle { position.longValue = 300_000 }
        compose.onNodeWithText("Line 30").assertIsNotDisplayed()
        compose.onNodeWithTag("lyrics").performScrollToIndex(12)
        compose.onNodeWithText("Line 12").performClick()
        compose.runOnIdle { assertEquals(listOf(120_000L), seeks) }
        compose.onNodeWithText("Line 12").assertIsDisplayed()
        compose.runOnIdle { position.longValue = 350_000 }
        compose.onNodeWithText("Line 35").assertIsDisplayed()
    }

    @Test fun tappingTheSameActiveLineResumesFollowingWithoutAPositionChange() {
        showLyrics()
        scrollAway()
        compose.onNodeWithTag("lyrics").performScrollToIndex(0)
        compose.onNodeWithText("Line 0").performClick()
        compose.runOnIdle {
            assertEquals(listOf(0L), seeks)
            assertEquals(0L, position.longValue)
            position.longValue = 300_000
        }
        compose.onNodeWithText("Line 30").assertIsDisplayed()
    }

    @Test fun tappedLineStaysVisibleUntilDelayedPlaybackPositionArrives() {
        showLyrics(seekImmediately = false)
        scrollAway()
        compose.runOnIdle { position.longValue = 300_000 }
        compose.onNodeWithTag("lyrics").performScrollToIndex(12)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Line 12").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Line 12").assertIsDisplayed()
        compose.onNodeWithText("Line 30").assertIsNotDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(120_000L), seeks)
            assertEquals(300_000L, position.longValue)
            position.longValue = 120_000
            // With a paused clock, writes must be applied explicitly so the recomposer sees them.
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Line 12").assertIsDisplayed()
        compose.runOnIdle {
            position.longValue = 350_000
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText("Line 35").assertIsDisplayed()
    }

    @Test fun failedSeekDoesNotLeaveFollowingPinnedToTheTappedLine() {
        showLyrics(seekImmediately = false)
        scrollAway()
        compose.onNodeWithTag("lyrics").performScrollToIndex(12)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Line 12").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Line 12").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Line 0").assertIsDisplayed()
    }

    @Test fun smallMouseWheelScrollRetainsFollowingWithoutAFling() {
        position.longValue = 100_000
        showLyrics()
        compose.onNodeWithTag("lyrics").performMouseInput {
            moveTo(center)
            scroll(0.25f)
        }
        compose.onNodeWithText("Line 10").assertIsDisplayed()
        compose.runOnIdle { position.longValue = 300_000 }
        compose.onNodeWithText("Line 30").assertIsDisplayed()
    }

    @Test fun tappingAfterMouseWheelScrollRestoresFollowing() {
        position.longValue = 100_000
        showLyrics()
        compose.onNodeWithTag("lyrics").performMouseInput {
            moveTo(center)
            scroll(8f)
        }
        compose.onNodeWithText("Line 10").assertIsNotDisplayed()
        compose.runOnIdle { position.longValue = 300_000 }
        compose.onNodeWithText("Line 30").assertIsNotDisplayed()
        compose.onNodeWithTag("lyrics").performScrollToIndex(12)
        compose.onNodeWithText("Line 12").performClick()
        compose.onNodeWithText("Line 12").assertIsDisplayed()
        compose.runOnIdle { position.longValue = 350_000 }
        compose.onNodeWithText("Line 35").assertIsDisplayed()
    }

    @Test fun smallManualScrollKeepingTheHighlightVisibleRetainsFollowing() {
        position.longValue = 100_000
        showLyrics()
        compose.onNodeWithTag("lyrics").performTouchInput {
            swipe(center, center - Offset(0f, 30f), durationMillis = 1_000)
        }
        compose.onNodeWithText("Line 10").assertIsDisplayed()
        compose.runOnIdle { position.longValue = 300_000 }
        compose.onNodeWithText("Line 30").assertIsDisplayed()
    }

    @Test fun followingWaitsForTheOngoingDragToFinish() {
        position.longValue = 100_000
        showLyrics()
        compose.onNodeWithTag("lyrics").performTouchInput {
            down(center)
            moveTo(center - Offset(0f, 30f), delayMillis = 300)
        }
        compose.onNodeWithText("Line 10").assertIsDisplayed()
        compose.runOnIdle { position.longValue = 300_000 }
        compose.onNodeWithText("Line 30").assertIsNotDisplayed()
        compose.onNodeWithTag("lyrics").performTouchInput {
            advanceEventTime(300)
            up()
        }
        compose.onNodeWithText("Line 30").assertIsDisplayed()
    }

    @Test fun flingCancelsFollowingWhenItCarriesTheHighlightOutOfView() {
        position.longValue = 100_000
        showLyrics()
        compose.onNodeWithTag("lyrics").performTouchInput {
            // The drag alone leaves the highlighted text visible; its velocity carries it offscreen.
            swipe(center, center - Offset(0f, 36f), durationMillis = 60)
        }
        compose.onNodeWithText("Line 10").assertIsNotDisplayed()
        compose.runOnIdle { position.longValue = 350_000 }
        compose.onNodeWithText("Line 35").assertIsNotDisplayed()
    }

    @Test fun changingTrackResetsScrollAndFollowingEvenWithIdenticalLyrics() {
        showLyrics()
        scrollAway()
        compose.runOnIdle {
            position.longValue = 300_000
            trackKey.value = "second-track"
        }
        compose.onNodeWithText("Line 30").assertIsDisplayed()
        compose.runOnIdle { position.longValue = 0 }
        compose.onNodeWithText("Line 0").assertIsDisplayed()
    }

    @Test fun replacingLyricsResetsFollowingOnTheSameTrack() {
        showLyrics()
        scrollAway()
        compose.runOnIdle {
            position.longValue = 300_000
            metadata.value = metadata.value!!.copy(sylt = List(40) { LyricLine(it * 10.0, "Replacement $it") })
        }
        compose.onNodeWithText("Replacement 30").assertIsDisplayed()
        compose.runOnIdle { position.longValue = 0 }
        compose.onNodeWithText("Replacement 0").assertIsDisplayed()
    }

    @Test fun positionBeforeTheFirstLineIsSafeAndLaterStartsFollowing() {
        metadata.value = metadata.value!!.copy(sylt = List(40) { LyricLine((it + 1) * 10.0, "Line $it") })
        showLyrics()
        compose.onNodeWithText("Line 0").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(seeks.isEmpty())
            position.longValue = 310_000
        }
        compose.onNodeWithText("Line 30").assertIsDisplayed()
    }

    private fun scrollAway() {
        compose.onNodeWithTag("lyrics").performTouchInput { swipeUp(durationMillis = 1_000) }
    }

    private fun pinchLyrics(zoomIn: Boolean) {
        compose.onNodeWithTag("lyrics").performTouchInput {
            val near = Offset(width * 0.1f, 0f)
            val far = Offset(width * 0.35f, 0f)
            val start = if (zoomIn) near else far
            val end = if (zoomIn) far else near
            pinch(start0 = center - start, end0 = center - end,
                start1 = center + start, end1 = center + end, durationMillis = 600)
        }
    }

    private fun textStyle(text: String): TextStyle {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single().layoutInput.style
    }

    private fun assertScaledStyle(original: TextStyle, scaled: TextStyle, scale: Float) {
        assertEquals(original.fontSize.value * scale, scaled.fontSize.value, 0.01f)
        assertEquals(original.lineHeight.value * scale, scaled.lineHeight.value, 0.01f)
    }

    private fun showLyrics(seekImmediately: Boolean = true) {
        compose.setContent {
            MusicTheme {
                LyricsContent(metadata.value, position.longValue, trackKey.value, {
                    seeks += it
                    if (seekImmediately) position.longValue = it
                }, Modifier.size(320.dp, 240.dp).testTag("lyrics"), error.value, video.value, preferUslt.value,
                    textScale.floatValue, { textScale.floatValue = normalizeLyricsTextScale(textScale.floatValue * it) })
            }
        }
    }
}
