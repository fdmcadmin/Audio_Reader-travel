package app.roadleaf

import app.roadleaf.playback.WaveAudio
import app.roadleaf.playback.WavePitch
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class WavePitchTest {
    private fun tone(directory: File, bits: Int = 16, channels: Int = 1): File {
        val rate = 16000; val frames = rate * 2; val size = frames * channels * bits / 8
        val buffer = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * bits / 8)
            .putShort((channels * bits / 8).toShort()).putShort(bits.toShort()).put("data".toByteArray()).putInt(size)
        repeat(frames) { i ->
            val value = sin(2 * PI * 220 * i / rate)
            repeat(channels) { if (bits == 16) buffer.putShort((value * 16000).toInt().toShort()) else buffer.put((128 + value * 60).toInt().toByte()) }
        }
        return File(directory, "tone-$bits-$channels.wav").apply { writeBytes(buffer.array()) }
    }
    private fun frequency(file: File): Double {
        val spec = WaveAudio.layout(file)
        val samples = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).apply { position(spec.offset.toInt()) }.asShortBuffer()
        val start = spec.rate / 4; val end = samples.limit() - spec.rate / 4
        var crossings = 0
        for (i in start + 1 until end) if (samples[i - 1] <= 0 && samples[i] > 0) crossings++
        return crossings.toDouble() * spec.rate / (end - start)
    }
    @Test fun pitchChangesFrequencyWhilePreservingDurationAndCachingResult() {
        val directory = Files.createTempDirectory("roadleaf-pitch").toFile()
        try {
            val source = tone(directory)
            assertSame(source, WavePitch.adjust(source, 1f))
            val adjusted = WavePitch.adjust(source, 0.92f)
            assertNotEquals(source, adjusted)
            assertEquals(220.0 * 0.92, frequency(adjusted), 3.0)
            assertTrue(abs(WaveAudio.durationMs(adjusted) - WaveAudio.durationMs(source)) < 100)
            assertEquals(adjusted, WavePitch.adjust(source, 0.92f))
            assertTrue(directory.listFiles().orEmpty().none { it.extension == "pitching" })
        } finally { directory.deleteRecursively() }
    }
    @Test fun stereoPcm8IsNormalizedBeforePitchAdjustment() {
        val directory = Files.createTempDirectory("roadleaf-pitch-pcm8").toFile()
        try {
            val source = tone(directory, bits = 8, channels = 2)
            val adjusted = WavePitch.adjust(source, 1.08f)
            val spec = WaveAudio.layout(adjusted)
            assertEquals(1, spec.channels); assertEquals(16, spec.bits); assertEquals(1, spec.format)
            assertEquals(220.0 * 1.08, frequency(adjusted), 4.0)
            assertTrue(abs(WaveAudio.durationMs(adjusted) - 2000) < 100)
        } finally { directory.deleteRecursively() }
    }
    @Test fun invalidPitchDoesNotCreateAudio() {
        val directory = Files.createTempDirectory("roadleaf-pitch-invalid").toFile()
        try {
            val source = tone(directory)
            for (pitch in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0.69f, 1.31f)) {
                assertThrows(IllegalArgumentException::class.java) { WavePitch.adjust(source, pitch) }
            }
            assertEquals(1, directory.listFiles().orEmpty().size)
        } finally { directory.deleteRecursively() }
    }
}
