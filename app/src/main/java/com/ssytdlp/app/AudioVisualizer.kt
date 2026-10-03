package com.ssytdlp.app

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class AudioVisualizerStyle(val label: String) {
    WAVEFORM("Waveform"),
    BARS("Amplitude bars"),
    RADIAL("Radial pulse");

    companion object {
        fun fromPreference(value: String?): AudioVisualizerStyle = entries.firstOrNull { it.name == value } ?: WAVEFORM
    }
}

private data class VisualizerFrame(val level: Float = 0f, val waveform: FloatArray = floatArrayOf())

@Composable
internal fun PlaybackAudioVisualizer(playing: Boolean, style: AudioVisualizerStyle, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as? MusicApplication
    DisposableEffect(app) {
        val stopObserving = app?.observeAudioLevels()
        onDispose { stopObserving?.invoke() }
    }
    AudioVisualizer(playing, style, { app?.audioLevels?.level() ?: 0f },
        { app?.audioLevels?.waveform() ?: floatArrayOf() }, modifier)
}

@Composable
internal fun AudioVisualizer(playing: Boolean, style: AudioVisualizerStyle, audioLevel: () -> Float,
    audioWaveform: () -> FloatArray, modifier: Modifier = Modifier) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = playing && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val currentLevel by rememberUpdatedState(audioLevel)
    val currentWaveform by rememberUpdatedState(audioWaveform)
    var frame by remember(active, style) { mutableStateOf(VisualizerFrame()) }
    LaunchedEffect(active, style) {
        if (!active) return@LaunchedEffect
        val motionScale = coroutineContext[MotionDurationScale]
        while (isActive) {
            if (!ValueAnimator.areAnimatorsEnabled() || motionScale?.scaleFactor == 0f) {
                frame = VisualizerFrame()
                break
            }
            withFrameNanos {
                val raw = currentLevel()
                val level = if (raw.isFinite()) sqrt(raw.coerceIn(0f, 1f)) else 0f
                val samples = currentWaveform()
                val waveform = FloatArray(samples.size.coerceAtMost(128)) { index ->
                    val value = samples[index]
                    if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
                }
                frame = VisualizerFrame(level, waveform)
            }
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val gradient = remember(primary, secondary) { Brush.verticalGradient(listOf(primary, secondary)) }
    val path = remember { Path() }
    Canvas(modifier.clip(RoundedCornerShape(24.dp)).background(background).semantics {
        contentDescription = "${style.label} audio visualizer"
        stateDescription = if (active) "Playing" else "Idle"
    }) {
        if (size.minDimension <= 0f) return@Canvas
        val animation = frame
        val inset = size.minDimension * 0.08f
        val width = size.width - 2f * inset
        val height = size.height - 2f * inset
        val stroke = 2.dp.toPx()
        when (style) {
            AudioVisualizerStyle.WAVEFORM -> {
                path.reset()
                val samples = animation.waveform
                val count = samples.size.coerceAtLeast(2)
                repeat(count) { index ->
                    val x = inset + width * index / (count - 1)
                    val y = center.y - (samples.getOrNull(index) ?: 0f) * height * 0.45f
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, gradient, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            AudioVisualizerStyle.BARS -> {
                val count = 24
                val step = width / count
                repeat(count) { index ->
                    val start = index * animation.waveform.size / count
                    val end = (index + 1) * animation.waveform.size / count
                    var peak = 0f
                    for (sample in start until end) peak = maxOf(peak, abs(animation.waveform[sample]))
                    val barHeight = maxOf(stroke, sqrt(peak) * height * 0.85f)
                    drawRoundRect(gradient,
                        topLeft = Offset(inset + step * (index + 0.15f), center.y - barHeight / 2f),
                        size = Size(step * 0.7f, barHeight), cornerRadius = CornerRadius(stroke))
                }
            }
            AudioVisualizerStyle.RADIAL -> {
                val radius = size.minDimension * (0.18f + animation.level * 0.08f)
                drawCircle(primary.copy(alpha = 0.08f + animation.level * 0.12f), radius, center)
                drawCircle(gradient, radius, center, style = Stroke(stroke))
                repeat(64) { index ->
                    val angle = (2.0 * PI * index / 64).toFloat()
                    val direction = Offset(cos(angle), sin(angle))
                    val sample = animation.waveform.getOrNull(index * animation.waveform.size / 64) ?: 0f
                    val length = stroke + abs(sample) * size.minDimension * 0.16f
                    drawLine(primary, center + direction * (radius + stroke * 2f),
                        center + direction * (radius + stroke * 2f + length), stroke, StrokeCap.Round)
                }
            }
        }
    }
}
