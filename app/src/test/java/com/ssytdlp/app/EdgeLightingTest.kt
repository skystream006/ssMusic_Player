package com.ssytdlp.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgeLightingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun onlyPlayingShowsLightingAndOverlayDoesNotBlockControls() {
        var playing by mutableStateOf(false)
        var clicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Button(onClick = { clicks++ }) { Text("Playback control") }
                    EdgeLighting(playing, { 0.5f }, Modifier.matchParentSize().testTag("edges"))
                }
            }
        }
        compose.onNodeWithTag("edges").assertDoesNotExist()
        compose.runOnIdle { playing = true }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("edges").assertIsDisplayed()
        compose.onNodeWithText("Playback control").performClick()
        compose.runOnIdle { assertEquals(1, clicks); playing = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("edges").assertDoesNotExist()
    }

    @Test fun lightingIsRemovedWhenHostLeavesForeground() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { EdgeLighting(true, { 1f }, Modifier.fillMaxSize().testTag("edges")) }
            }
        }
        compose.onNodeWithTag("edges").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("edges").assertDoesNotExist()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("edges").assertIsDisplayed()
    }

    @Test fun borderFollowsThemeChangesWithoutCoveringTheCenter() {
        var accent by mutableStateOf(Color.Red)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = accent)) {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("host")) {
                    EdgeLighting(true, { 0f }, Modifier.matchParentSize())
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        fun assertTint(red: Boolean) {
            val pixels = compose.onNodeWithTag("host").captureToImage().toPixelMap()
            var litPixels = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                if (pixel.red + pixel.blue > 0.05f) {
                    litPixels++
                    assertTrue(if (red) pixel.red > pixel.blue else pixel.blue > pixel.red)
                }
            }
            assertTrue(litPixels > 0)
            assertEquals(Color.Black, pixels[pixels.width / 2, pixels.height / 2])
        }
        assertTint(red = true)
        compose.runOnIdle { accent = Color.Blue }
        compose.mainClock.advanceTimeByFrame()
        assertTint(red = false)
    }
}
