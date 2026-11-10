package app.roadleaf.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Offline, bounded pitch adjustment with unchanged speaking speed, including engines that ignore TTS pitch. */
@androidx.annotation.OptIn(UnstableApi::class)
object WavePitch {
    fun adjust(source: File, pitch: Float): File {
        require(pitch.isFinite() && pitch in 0.7f..1.3f)
        if (pitch == 1f) return source
        val identity = "pitch-v1\u0000${source.name}\u0000$pitch"
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        val result = File(source.parentFile, "$key.wav")
        if (result.exists() && runCatching { WaveAudio.durationMs(result) > 0 }.getOrDefault(false)) return result
        val inputFile = WaveAudio.join(listOf(source), requireNotNull(source.parentFile), gapMs = 0, forcePcm16 = true)
        val spec = WaveAudio.layout(inputFile)
        val processor = SonicAudioProcessor().apply {
            setSpeed(1f); setPitch(pitch)
            configure(AudioProcessor.AudioFormat(spec.rate, 1, C.ENCODING_PCM_16BIT)); flush()
        }
        val temporary = File(source.parentFile, "$key.pitching")
        try {
            RandomAccessFile(inputFile, "r").use { input ->
                input.seek(spec.offset)
                temporary.outputStream().buffered().use { output ->
                    output.write(WaveAudio.header(spec.rate, 0))
                    val bytes = ByteArray(32768)
                    val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                    var written = 0L
                    fun drain() {
                        val audio = processor.output
                        while (audio.hasRemaining()) {
                            val count = minOf(audio.remaining(), bytes.size)
                            audio.get(bytes, 0, count); output.write(bytes, 0, count); written += count
                            check(written < Int.MAX_VALUE - 44) { "Speech window is too large" }
                        }
                    }
                    var remaining = spec.frames * 2
                    while (remaining > 0) {
                        val count = minOf(remaining, bytes.size.toLong()).toInt()
                        input.readFully(bytes, 0, count); buffer.clear(); buffer.put(bytes, 0, count); buffer.flip()
                        while (buffer.hasRemaining()) {
                            val before = buffer.remaining(); processor.queueInput(buffer); drain()
                            check(buffer.remaining() < before) { "Pitch adjustment stalled" }
                        }
                        remaining -= count
                    }
                    processor.queueEndOfStream()
                    var attempts = 0
                    while (!processor.isEnded) { drain(); check(++attempts <= 1000) { "Pitch adjustment did not finish" } }
                    check(written > 0 && written % 2 == 0L) { "Pitch adjustment produced no audio" }
                    output.flush()
                    RandomAccessFile(temporary, "rw").use { it.write(WaveAudio.header(spec.rate, written.toInt())) }
                }
            }
            check(temporary.renameTo(result)) { "Could not save adjusted narration" }
            return result
        } finally { processor.reset(); temporary.delete() }
    }
}
