package com.ssytdlp.app

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@androidx.annotation.OptIn(UnstableApi::class)
class AudioLevelMeterTest {
    @Test fun measuresActualPcmWithoutMovingOrChangingInput() {
        val meter = AudioLevelMeter { 0L }
        meter.flush(48_000, 1, C.ENCODING_PCM_16BIT)
        val buffer = pcm(123, 16384, -16384).apply { position(2) }
        val original = buffer.array().copyOf()
        meter.handleBuffer(buffer)
        assertEquals(0.5f, meter.level(), 0.001f)
        assertEquals(2, buffer.position())
        assertArrayEquals(original, buffer.array())
        meter.handleBuffer(pcm(0, 0))
        assertEquals(0f, meter.level(), 0f)
    }

    @Test fun includesBothStereoChannelsEvenWhenSubsamplingLargeBuffers() {
        val meter = AudioLevelMeter { 0L }
        meter.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        val samples = ShortArray(8192) { if (it % 2 == 0) 0 else Short.MIN_VALUE }
        meter.handleBuffer(pcm(*samples))
        assertEquals(0.7071f, meter.level(), 0.001f)
    }

    @Test fun staleAudioDecaysAndFlushClearsOldTrackLevels() {
        var now = 0L
        val meter = AudioLevelMeter { now }
        meter.flush(48_000, 1, C.ENCODING_PCM_16BIT)
        meter.handleBuffer(pcm(Short.MIN_VALUE))
        assertEquals(1f, meter.level(), 0f)
        now = 125_000_000L
        assertEquals(0.5f, meter.level(), 0.001f)
        now = 300_000_000L
        assertEquals(0f, meter.level(), 0f)
        meter.handleBuffer(pcm(Short.MIN_VALUE))
        meter.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        assertEquals(0f, meter.level(), 0f)
    }

    @Test fun handlesEmptyUnsupportedAndNonFiniteSamplesSafely() {
        val meter = AudioLevelMeter { 0L }
        meter.flush(48_000, 1, C.ENCODING_PCM_FLOAT)
        val floats = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY).putFloat(2f).apply { flip() }
        meter.handleBuffer(floats)
        assertEquals(0.57735f, meter.level(), 0.001f)
        meter.clear()
        meter.handleBuffer(ByteBuffer.allocate(0))
        assertEquals(0f, meter.level(), 0f)
        meter.flush(48_000, 1, C.ENCODING_PCM_24BIT)
        meter.handleBuffer(pcm(Short.MIN_VALUE))
        assertEquals(0f, meter.level(), 0f)
    }

    @Test fun audioTapPassesEveryByteThroughUnchanged() {
        val meter = AudioLevelMeter { 0L }
        val processor = TeeAudioProcessor(meter)
        val format = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(format, processor.configure(format))
        processor.flush()
        val input = pcm(Short.MIN_VALUE, Short.MAX_VALUE, 0, -1234, 4567, 0)
        val expected = input.array().copyOf()
        processor.queueInput(input)
        val output = processor.output
        val actual = ByteArray(output.remaining()).also { output.get(it) }
        assertArrayEquals(expected, actual)
        assertFalse(input.hasRemaining())
        assertTrue(meter.level() > 0f)
        processor.flush()
        assertEquals(0f, meter.level(), 0f)
        processor.reset()
    }

    private fun pcm(vararg values: Short): ByteBuffer =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putShort(it) }
            flip()
        }
}
