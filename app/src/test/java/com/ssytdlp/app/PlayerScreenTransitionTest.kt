package com.ssytdlp.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.ssytdlp.app.core.Track
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerScreenTransitionTest {
    private var motionScale = 1f
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(
        effectContext = object : MotionDurationScale {
            override val scaleFactor get() = motionScale
        }
    )
    private var screen by mutableIntStateOf(0)
    private var hasPlayer by mutableStateOf(true)
    private var toggles = 0

    @Test fun tappingMiniplayerGrowsPageFromDockAndBackCollapsesIt() {
        showNavigation()
        val root = bounds("navigation-root")
        val dock = bounds("player-dock")
        assertTrue("The source excludes navigation-bar padding", dock.bottom < root.bottom)

        compose.onNodeWithContentDescription("Play").performClick()
        compose.runOnIdle {
            assertEquals(1, toggles)
            assertEquals(0, screen)
        }
        compose.onNodeWithText("Transition song").performClick()
        advance(32)
        val start = bounds("page-1")
        assertTrue(start.top > root.height / 2)
        assertTrue(start.height < root.height / 2)
        advance(128)
        val middle = bounds("page-1")
        assertTrue(middle.top < start.top)
        assertTrue(middle.height > start.height)
        assertTrue(middle.height < root.height)
        advance(500)
        assertBounds(root, bounds("page-1"))
        compose.onNodeWithTag("page-0").assertDoesNotExist()
        compose.onNodeWithTag("player-dock").assertDoesNotExist()
        compose.onNodeWithText("Player action").performClick()
        compose.runOnIdle { assertEquals(2, toggles) }

        compose.onNodeWithText("Back").performClick()
        advance(160)
        val collapsing = bounds("page-1")
        assertTrue(collapsing.top > root.top)
        assertTrue(collapsing.height < root.height)
        advance(500)
        compose.onNodeWithTag("page-1").assertDoesNotExist()
        assertBounds(dock, bounds("player-dock"))
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land")
    fun expandsFromSettingsDockInLandscape() {
        screen = 2
        showNavigation()
        val dock = bounds("player-dock")
        val root = bounds("navigation-root")
        compose.onNodeWithText("Transition song").performClick()
        advance(160)
        val growing = bounds("page-1")
        assertTrue(growing.height > dock.height)
        assertTrue(growing.height < root.height)
        assertTrue(growing.top < dock.top)
        advance(500)
        assertBounds(root, bounds("page-1"))
        compose.onNodeWithTag("page-2").assertDoesNotExist()
    }

    @Test fun backDuringExpansionAndReopeningDoNotLeaveAnOverlay() {
        showNavigation()
        compose.onNodeWithText("Transition song").performClick()
        advance(128)
        compose.runOnIdle { screen = 0; Snapshot.sendApplyNotifications() }
        advance(500)
        compose.onNodeWithTag("page-1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
        compose.onNodeWithText("Transition song").performClick()
        advance(500)
        assertBounds(bounds("navigation-root"), bounds("page-1"))
        compose.onNodeWithTag("player-dock").assertDoesNotExist()
    }

    @Test fun restoredPlayerHasFullBoundsWithoutAnEntranceAnimation() {
        screen = 1
        showNavigation()
        assertBounds(bounds("navigation-root"), bounds("page-1"))
        compose.onNodeWithTag("player-dock").assertDoesNotExist()
    }

    @Test fun navigationBetweenNonPlayerScreensRemainsImmediate() {
        showNavigation()
        val dock = bounds("player-dock")
        compose.runOnIdle { screen = 2; Snapshot.sendApplyNotifications() }
        advance(64)
        compose.onNodeWithTag("page-0").assertDoesNotExist()
        assertBounds(bounds("navigation-root"), bounds("page-2"))
        assertBounds(dock, bounds("player-dock"))
    }

    @Test fun disabledMotionOpensAndClosesWithoutWaitingForAnimationDuration() {
        motionScale = 0f
        showNavigation()
        compose.onNodeWithText("Transition song").performClick()
        advance(64)
        assertBounds(bounds("navigation-root"), bounds("page-1"))
        compose.onNodeWithTag("page-0").assertDoesNotExist()
        compose.onNodeWithText("Back").performClick()
        advance(64)
        compose.onNodeWithTag("page-1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    @Test fun removingPlayerDuringExpansionDoesNotLeaveStaleSharedContent() {
        showNavigation()
        compose.onNodeWithText("Transition song").performClick()
        advance(128)
        compose.runOnIdle {
            hasPlayer = false
            screen = 0
            Snapshot.sendApplyNotifications()
        }
        advance(500)
        compose.onNodeWithTag("page-1").assertDoesNotExist()
        compose.onNodeWithTag("player-dock").assertDoesNotExist()
        assertBounds(bounds("navigation-root"), bounds("page-0"))
    }

    private fun showNavigation() {
        val track = Track(jobId = "job", name = "song.mp3", title = "Transition song")
        compose.setContent {
            MusicTheme {
                Box(Modifier.fillMaxSize().testTag("navigation-root")) {
                    PlayerScreenTransition(screen, hasPlayer) { visibleScreen, pageModifier, dockModifier ->
                        SkinBackground(pageModifier.fillMaxSize().testTag("page-$visibleScreen")) {
                            Scaffold(topBar = {
                                if (visibleScreen == 1) Button(onClick = { screen = 0 }) { Text("Back") }
                                else Text("Library or settings", Modifier.height(56.dp))
                            }, bottomBar = {
                                if (hasPlayer && visibleScreen != 1) Box(Modifier.padding(bottom = 24.dp)) {
                                    Box(dockModifier.testTag("player-dock")) {
                                        MiniPlayer(PlaybackState(track = track), null, { screen = 1 },
                                            { toggles++ }, {}, {})
                                    }
                                }
                            }) { padding ->
                                Column(Modifier.padding(padding).fillMaxSize()) {
                                    if (visibleScreen == 1) {
                                        Text("NOW PLAYING")
                                        Button(onClick = { toggles++ }) { Text("Player action") }
                                    } else Text("Background screen")
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
    }

    private fun advance(millis: Long) {
        compose.runOnIdle { Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1f)
        assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.right, actual.right, 1f)
        assertEquals(expected.bottom, actual.bottom, 1f)
    }
}
