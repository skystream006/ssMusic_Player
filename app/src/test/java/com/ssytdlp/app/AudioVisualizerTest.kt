package com.ssytdlp.app

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@androidx.annotation.OptIn(UnstableApi::class)
class AudioVisualizerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setDurationScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Any?>(ValueAnimator::class.java, "setDurationScale",
            ClassParameter.from(Float::class.javaPrimitiveType, scale))
    }

    @Before fun setup() {
        setDurationScale(1f)
        compose.mainClock.autoAdvance = false
    }

    @After fun restoreAnimations() = setDurationScale(1f)

    @Test fun gradientsFollowEachStyleAndRefreshWithThemeAndSize() {
        val themes = listOf(
            lightColorScheme(primary = Color.Red, secondary = Color.Green, tertiary = Color.Blue),
            darkColorScheme(primary = Color.Cyan, secondary = Color.Magenta, tertiary = Color.Yellow))
        var theme by mutableStateOf(themes.first())
        var style by mutableStateOf(AudioVisualizerStyle.WAVEFORM)
        var height by mutableStateOf(260.dp)
        val silence = FloatArray(128)
        val loud = FloatArray(128) { 1f }
        compose.setContent {
            MaterialTheme(colorScheme = theme) {
                AudioVisualizer(true, style, { 1f },
                    { if (style == AudioVisualizerStyle.WAVEFORM) silence else loud }, Modifier.size(260.dp, height))
            }
        }
        fun colorDistance(first: Color, second: Color) =
            abs(first.red - second.red) + abs(first.green - second.green) + abs(first.blue - second.blue)
        AudioVisualizerStyle.entries.forEach { option ->
            listOf(260.dp, 160.dp).forEach { canvasHeight ->
                var previousColors: List<Color>? = null
                themes.forEach { scheme ->
                    update { style = option; theme = scheme; height = canvasHeight }
                    compose.mainClock.advanceTimeBy(100)
                    val bounds = compose.onNodeWithContentDescription("${option.label} audio visualizer")
                        .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val inset = minOf(bounds.width, bounds.height) * 0.08f
                    val points = when (option) {
                        AudioVisualizerStyle.WAVEFORM -> listOf(0.2f, 0.5f, 0.8f).map {
                            Offset(bounds.left + bounds.width * it, bounds.center.y)
                        }
                        AudioVisualizerStyle.BARS -> listOf(2, 11, 21).map {
                            Offset(bounds.left + inset + (bounds.width - 2f * inset) * (it + 0.5f) / 24,
                                bounds.center.y)
                        }
                        AudioVisualizerStyle.RADIAL -> {
                            val radius = minOf(bounds.width, bounds.height) * 0.36f
                            listOf(Offset(radius, 0f), Offset(0f, radius), Offset(-radius, 0f))
                                .map { bounds.center + it }
                        }
                    }
                    val pixels = captureContent()
                    val width = compose.runOnIdle {
                        compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).width
                    }
                    val colors = points.map { Color(pixels[it.y.toInt() * width + it.x.toInt()]) }
                    colors.zipWithNext().forEach { (first, second) ->
                        assertTrue("${option.label} must have a visible gradient along its geometry",
                            colorDistance(first, second) > 0.2f)
                    }
                    previousColors?.zip(colors)?.forEach { (before, after) ->
                        assertTrue("${option.label} must refresh its gradient when the theme changes",
                            colorDistance(before, after) > 0.2f)
                    }
                    previousColors = colors
                }
            }
        }
    }

    @Test fun allThreeStylesRenderDistinctVisualsThatRespondToDecodedAudio() {
        val meter = AudioLevelMeter { 0L }
        meter.flush(48_000, 1, C.ENCODING_PCM_16BIT)
        var style by mutableStateOf(AudioVisualizerStyle.WAVEFORM)
        compose.setContent {
            MaterialTheme { AudioVisualizer(true, style, meter::level, meter::waveform, Modifier.size(260.dp)) }
        }
        val images = mutableListOf<IntArray>()
        AudioVisualizerStyle.entries.forEach { option ->
            update { style = option }
            val pcm = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(128) { putShort((sin(it * 0.15) * 24_000).toInt().toShort()) }
                flip()
            }
            meter.handleBuffer(pcm)
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithContentDescription("${option.label} audio visualizer").assertIsDisplayed()
            val playing = captureContent()
            images.forEach { assertFalse("Each style must look different", it.contentEquals(playing)) }
            images.add(playing)
            meter.handleBuffer(ByteBuffer.allocate(256))
            compose.mainClock.advanceTimeBy(100)
            assertFalse("Each style must react to audio", playing.contentEquals(captureContent()))
        }
        assertEquals(3, images.size)
    }

    @Test fun samplingStopsWhenPausedBackgroundedOrRemovedAndResumes() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        var playing by mutableStateOf(true)
        var visible by mutableStateOf(true)
        var reads = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    if (visible) AudioVisualizer(playing, AudioVisualizerStyle.WAVEFORM,
                        { reads++; 0.5f }, { reads++; floatArrayOf(-1f, 1f) }, Modifier.size(260.dp))
                }
            }
        }
        fun assertSampling() {
            val before = compose.runOnIdle { reads }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle { assertTrue(reads > before) }
        }
        fun assertStopped() {
            compose.mainClock.advanceTimeByFrame()
            val before = compose.runOnIdle { reads }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle { assertEquals(before, reads) }
        }
        assertSampling()
        update { playing = false }
        assertStopped()
        update { playing = true }
        assertSampling()
        update { owner.registry.currentState = Lifecycle.State.STARTED }
        assertStopped()
        update { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertSampling()
        update { visible = false }
        assertStopped()
        update { visible = true }
        assertSampling()
    }

    @Test fun reducedMotionShowsStaticVisualsWithoutSamplingAudio() {
        setDurationScale(0f)
        var style by mutableStateOf(AudioVisualizerStyle.WAVEFORM)
        compose.setContent {
            MaterialTheme {
                AudioVisualizer(true, style, { error("Reduced motion must not read audio") },
                    { error("Reduced motion must not read waveform") }, Modifier.size(260.dp))
            }
        }
        AudioVisualizerStyle.entries.forEach { option ->
            update { style = option }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithContentDescription("${option.label} audio visualizer").assertIsDisplayed()
            val first = captureContent()
            compose.mainClock.advanceTimeBy(500)
            assertArrayEquals(first, captureContent())
        }
    }

    @Test fun emptyAndInvalidSamplesRenderSafely() {
        var samples by mutableStateOf(floatArrayOf())
        var style by mutableStateOf(AudioVisualizerStyle.WAVEFORM)
        compose.setContent {
            MaterialTheme { AudioVisualizer(true, style, { Float.NaN }, { samples }, Modifier.size(260.dp)) }
        }
        AudioVisualizerStyle.entries.forEach { option ->
            listOf(floatArrayOf(), floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -2f, 2f)).forEach { input ->
                update { style = option; samples = input }
                compose.mainClock.advanceTimeBy(100)
                compose.onNodeWithContentDescription("${option.label} audio visualizer").assertIsDisplayed()
                captureContent()
            }
        }
    }

    private fun update(block: () -> Unit) = compose.runOnIdle { block(); Snapshot.sendApplyNotifications() }

    private fun captureContent(): IntArray = compose.runOnIdle {
        val view = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
        }
    }
}
