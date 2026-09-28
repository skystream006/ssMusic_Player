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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

private data class EdgeFrame(val progress: Float = 0f, val level: Float = 0f, val flutter: Float = 0f)

@Composable
internal fun PlaybackEdgeLighting(playing: Boolean, modifier: Modifier = Modifier) {
    val meter = (LocalContext.current.applicationContext as? MusicApplication)?.audioLevels
    EdgeLighting(playing, { meter?.level() ?: 0f }, modifier)
}

@Composable
internal fun EdgeLighting(playing: Boolean, audioLevel: () -> Float, modifier: Modifier = Modifier) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!playing || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return
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
                elapsed = (elapsed + delta / (motionScale?.scaleFactor ?: 1f)) % 6f
                val raw = currentLevel()
                val target = if (raw.isFinite()) sqrt(raw.coerceIn(0f, 1f)) else 0f
                val response = if (target > envelope) 18f else 7f
                envelope += (target - envelope) * (delta * response).coerceAtMost(1f)
                frame = EdgeFrame(elapsed / 6f, envelope, sin(elapsed * 2f * PI.toFloat() * 8f) * envelope)
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
        val segment = Path()
        val length = measure.length
        val baseWidth = 1.5.dp.toPx()
        val glowWidth = 8.dp.toPx()
        val flutterSize = 0.7.dp.toPx()
        onDrawBehind {
            if (length <= 0f || size.minDimension <= 0f) return@onDrawBehind
            val animation = frame
            val flutter = flutterSize * animation.flutter
            scale((size.width - flutter * 2) / size.width, (size.height - flutter * 2) / size.height) {
                drawPath(outline, primary.copy(alpha = 0.12f), style = Stroke(baseWidth))
                // Two soft trails travel along the perimeter, rather than rotating the rectangle.
                repeat(2) { trail ->
                    repeat(18) { step ->
                        val start = ((animation.progress + trail * 0.5f + step * 0.012f) % 1f) * length
                        val end = start + length * 0.013f
                        segment.reset()
                        measure.getSegment(start, end.coerceAtMost(length), segment)
                        if (end > length) measure.getSegment(0f, end - length, segment)
                        val strength = (step + 1) / 18f
                        drawPath(segment, primary.copy(alpha = strength * (0.07f + animation.level * 0.04f)),
                            style = Stroke(glowWidth + animation.level * 3.dp.toPx(), cap = StrokeCap.Round))
                        drawPath(segment, primary.copy(alpha = strength * (0.65f + animation.level * 0.25f)),
                            style = Stroke(baseWidth + animation.level * 0.8.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
            }
        }
    })
}
