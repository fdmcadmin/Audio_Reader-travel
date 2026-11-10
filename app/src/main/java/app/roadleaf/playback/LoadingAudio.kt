package app.roadleaf.playback

import java.io.File

/** A bounded silent media item keeps normal audio-focus and headset controls active during page loading. */
object LoadingAudio {
    fun file(folder: File): File {
        val result = File(folder, "web-loading.wav")
        val bytes = 8_000 * 2 * 120
        if (result.length() == bytes + 44L) return result
        result.outputStream().buffered().use { out ->
            fun word(value: Int, count: Int) { repeat(count) { out.write(value ushr (8 * it) and 255) } }
            fun tag(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
            tag("RIFF"); word(bytes + 36, 4); tag("WAVEfmt "); word(16, 4); word(1, 2); word(1, 2)
            word(8_000, 4); word(16_000, 4); word(2, 2); word(16, 2); tag("data"); word(bytes, 4)
            val silence = ByteArray(8192)
            var remaining = bytes
            while (remaining > 0) { val size = minOf(remaining, silence.size); out.write(silence, 0, size); remaining -= size }
        }
        return result
    }
}
