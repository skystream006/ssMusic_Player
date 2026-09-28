package com.ssytdlp.app

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

@androidx.annotation.OptIn(UnstableApi::class)
class AudioLevelMeter(private val nanoTime: () -> Long = System::nanoTime) : TeeAudioProcessor.AudioBufferSink {
    private data class Sample(val level: Float, val time: Long)
    @Volatile private var sample = Sample(0f, 0L)
    private var encoding = C.ENCODING_INVALID

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
        clear()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = input.remaining() / bytesPerSample
        if (count == 0) return
        // Bound analysis work without changing the buffer consumed by the audio sink.
        val stride = ((count + 1023) / 1024).coerceAtLeast(1)
        var energy = 0.0
        var measured = 0
        for (index in 0 until count step stride) {
            val offset = input.position() + index * bytesPerSample
            val value = if (bytesPerSample == 2) input.getShort(offset) / 32768f else input.getFloat(offset)
            val normalized = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
            energy += normalized.toDouble() * normalized
            measured++
        }
        sample = Sample(sqrt(energy / measured).toFloat().coerceIn(0f, 1f), nanoTime())
    }

    fun level(): Float {
        val current = sample
        val age = ((nanoTime() - current.time) / 1_000_000f).coerceAtLeast(0f)
        return current.level * (1f - age / 250f).coerceIn(0f, 1f)
    }

    fun clear() { sample = Sample(0f, 0L) }
}
