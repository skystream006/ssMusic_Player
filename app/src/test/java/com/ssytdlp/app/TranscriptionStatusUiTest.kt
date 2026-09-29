package com.ssytdlp.app

import android.app.Application
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ssytdlp.app.core.Track
import com.ssytdlp.app.core.Transcription
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranscriptionStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val record = mutableStateOf<Transcription?>(Transcription(status = "sent"))
    private var plays = 0
    private var menus = 0

    @Test fun hoveringStatusDoesNotShowDetailsOrPlayTheTrack() {
        showTrack()
        compose.onNodeWithText("Transcription request sent").performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Requested: Unknown").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, plays) }
    }

    @Test fun tapShowsPersistentDetailsAndCloseButtonDismissesWithoutPlayback() {
        showTrack()
        openDetails()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Requested: Unknown").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText("Requested: Unknown").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close transcription details").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Requested: Unknown").assertDoesNotExist()
        openDetails()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Requested: Unknown").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, plays) }
    }

    @Test fun tappingOutsideDismissesDetailsWithoutPlayingTheTrack() {
        showTrack()
        openDetails()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Requested: Unknown").assertIsDisplayed()
        compose.runOnIdle {
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try {
                ShadowDialog.getLatestDialog().dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Requested: Unknown").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, plays)
            assertEquals(0, menus)
        }
    }

    @Test fun statusChangesPreserveNarrowScreenTrackAndMenuActions() {
        showTrack()
        update(Transcription(status = "transcribed", lyricsIncluded = true))
        compose.onNodeWithText("Transcription request sent").assertDoesNotExist()
        compose.onNodeWithText("Lyrics included").assertIsDisplayed()
        update(Transcription(status = "failed", error = "Service unavailable"))
        compose.onNodeWithContentDescription("Transcription failed\nRequested: Unknown\nService unavailable").assertIsDisplayed()
        compose.onNodeWithText("Song").performClick()
        compose.onNodeWithContentDescription("Track options").performClick()
        compose.runOnIdle {
            assertEquals(1, plays)
            assertEquals(1, menus)
        }
        update(Transcription(status = "unknown"))
        compose.onNodeWithText("Transcription failed").assertDoesNotExist()
        update(null)
        compose.onNodeWithText("Song").assertIsDisplayed()
    }

    private fun openDetails() {
        compose.onNodeWithContentDescription(
            "Transcription request sent\nRequested: Unknown",
            useUnmergedTree = true
        ).performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Requested: Unknown").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showTrack() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MusicTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Box(Modifier.width(320.dp)) {
                        TrackRow(Track(name = "song.mp3", title = "Song"), transcription = record.value,
                            onClick = { plays++ }) {
                            ToolButton(Icons.Rounded.MoreVert, "Track options") { menus++ }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
    }

    private fun update(value: Transcription?) {
        compose.runOnIdle { record.value = value; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(600)
    }
}
