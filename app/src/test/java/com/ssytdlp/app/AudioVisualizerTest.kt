package com.ssytdlp.app

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
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
