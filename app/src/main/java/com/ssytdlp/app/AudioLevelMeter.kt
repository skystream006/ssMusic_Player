package com.ssytdlp.app

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

@androidx.annotation.OptIn(UnstableApi::class)
class AudioLevelMeter(private val nanoTime: () -> Long = System::nanoTime) : TeeAudioProcessor.AudioBufferSink {
    private data class Sample(val level: Float, val time: Long, val waveform: FloatArray = floatArrayOf())
    @Volatile private var sample = Sample(0f, 0L)
    @Volatile var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) clear()
        }
    private var encoding = C.ENCODING_INVALID
    private var channels = 1

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
        channels = channelCount.coerceAtLeast(1)
        clear()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!enabled) return
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = input.remaining() / bytesPerSample / channels
        if (count == 0) return
        // Bound analysis work without changing the buffer consumed by the audio sink.
        val stride = ((count + 511) / 512).coerceAtLeast(1)
        var energy = 0.0
        var measured = 0
        val waveform = FloatArray(count.coerceAtMost(128))
        for (index in 0 until count step stride) {
            val bin = (index.toLong() * waveform.size / count).toInt()
            repeat(channels) { channel ->
                val offset = input.position() + (index * channels + channel) * bytesPerSample
                val value = if (bytesPerSample == 2) input.getShort(offset) / 32768f else input.getFloat(offset)
                val normalized = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
                energy += normalized.toDouble() * normalized
                if (abs(normalized) > abs(waveform[bin])) waveform[bin] = normalized
                measured++
            }
        }
        if (enabled) sample = Sample(sqrt(energy / measured).toFloat().coerceIn(0f, 1f), nanoTime(), waveform)
    }

    fun level(): Float {
        if (!enabled) return 0f
        val current = sample
        return current.level * freshness(current)
    }

    fun waveform(): FloatArray {
        if (!enabled) return floatArrayOf()
        val current = sample
        val scale = freshness(current)
        return FloatArray(current.waveform.size) { current.waveform[it] * scale }
    }

    private fun freshness(current: Sample): Float {
        val age = ((nanoTime() - current.time) / 1_000_000f).coerceAtLeast(0f)
        return (1f - age / 250f).coerceIn(0f, 1f)
    }

    fun clear() { sample = Sample(0f, 0L) }
}
