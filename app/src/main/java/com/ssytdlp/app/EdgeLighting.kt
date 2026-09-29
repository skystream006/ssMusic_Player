package com.ssytdlp.app

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val EDGE_CIRCULATION_SECONDS = 12f

internal fun edgeOscillation(position: Float, progress: Float, level: Float): Float =
    sin(2f * PI.toFloat() * (position * 12f - progress * 18f)) * level

private data class EdgeFrame(val progress: Float = 0f, val level: Float = 0f)

@Composable
internal fun PlaybackEdgeLighting(playing: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val meter = (LocalContext.current.applicationContext as? MusicApplication)?.audioLevels
    EdgeLighting(playing, { meter?.level() ?: 0f }, modifier, enabled)
}

@Composable
internal fun EdgeLighting(playing: Boolean, audioLevel: () -> Float, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!enabled || !playing || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return
    val primary = MaterialTheme.colorScheme.primary
    val currentLevel by rememberUpdatedState(audioLevel)
    var frame by remember { mutableStateOf(EdgeFrame()) }
    LaunchedEffect(Unit) {
        val motionScale = coroutineContext[MotionDurationScale]
        var previous = withFrameNanos { it }
        var elapsed = 0f
        var envelope = 0f
        while (isActive) {
            if (!ValueAnimator.areAnimatorsEnabled() || motionScale?.scaleFactor == 0f) {
                frame = EdgeFrame()
                break
            }
            withFrameNanos { now ->
                val delta = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.064f)
                previous = now
                elapsed = (elapsed + delta / (motionScale?.scaleFactor ?: 1f)) % EDGE_CIRCULATION_SECONDS
                val raw = currentLevel()
                val target = if (raw.isFinite()) sqrt(raw.coerceIn(0f, 1f)) else 0f
                val response = if (target > envelope) 18f else 7f
                envelope += (target - envelope) * (delta * response).coerceAtMost(1f)
                frame = EdgeFrame(elapsed / EDGE_CIRCULATION_SECONDS, envelope)
            }
        }
    }
    Spacer(modifier.drawWithCache {
        val inset = 7.dp.toPx().coerceAtMost(size.minDimension / 4f)
        val corner = CornerRadius(28.dp.toPx().coerceAtMost(size.minDimension / 4f))
        val outline = Path().apply {
            addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, corner))
        }
        val measure = PathMeasure().apply { setPath(outline, true) }
        val length = measure.length
        val samples = (length / 4.dp.toPx()).toInt().coerceIn(64, 1024)
        val points = List(samples) { measure.getPosition(length * it / samples) }
        val normals = List(samples) {
            val tangent = measure.getTangent(length * it / samples)
            Offset(-tangent.y, tangent.x)
        }
        val line = Path()
        val baseWidth = 1.5.dp.toPx()
        val amplitude = 2.5.dp.toPx()
        onDrawBehind {
            if (length <= 0f || size.minDimension <= 0f) return@onDrawBehind
            val animation = frame
            line.reset()
            repeat(samples) { index ->
                val displacement = amplitude * edgeOscillation(index.toFloat() / samples, animation.progress, animation.level)
                val point = points[index] + normals[index] * displacement
                if (index == 0) line.moveTo(point.x, point.y) else line.lineTo(point.x, point.y)
            }
            line.close()
            // A continuous gradient avoids seams between individual trail segments.
            val gradient = Brush.sweepGradient(List(65) { index ->
                val strength = (0.5f + 0.5f * cos(4f * PI.toFloat() * (index / 64f - animation.progress)))
                primary.copy(alpha = 0.12f + strength * strength * (0.65f + animation.level * 0.2f))
            })
            drawPath(line, gradient, alpha = 0.04f, style = Stroke(14.dp.toPx()))
            drawPath(line, gradient, alpha = 0.08f, style = Stroke(9.dp.toPx()))
            drawPath(line, gradient, alpha = 0.16f, style = Stroke(5.dp.toPx()))
            drawPath(line, gradient, style = Stroke(baseWidth + animation.level * 0.8.dp.toPx()))
        }
    })
}
