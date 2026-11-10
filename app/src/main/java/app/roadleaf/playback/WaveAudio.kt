package app.roadleaf.playback

import java.io.File
import java.io.RandomAccessFile
import java.io.BufferedInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Read actual PCM timing without loading an audio file into memory. */
object WaveAudio {
    data class Layout(val format: Int, val channels: Int, val rate: Int, val bits: Int, val offset: Long, val bytes: Long) {
        val frameBytes get() = channels * bits / 8
        val frames get() = bytes / frameBytes
    }
    fun layout(file: File): Layout = RandomAccessFile(file, "r").use { input ->
        fun ascii() = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun unsignedInt() = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        require(input.length() >= 44 && ascii() == "RIFF") { "Invalid local speech audio" }
        unsignedInt(); require(ascii() == "WAVE") { "Invalid local speech audio" }
        var format = 0; var channels = 0; var rate = 0; var bits = 0
        var dataBytes = 0L; var dataOffset = 0L
        while (input.filePointer + 8 <= input.length()) {
            val chunk = ascii(); val length = unsignedInt(); val start = input.filePointer
            require(length <= input.length() - start) { "Incomplete local speech audio" }
            if (chunk == "fmt " && length >= 16) {
                format = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                channels = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                rate = unsignedInt().toInt(); input.skipBytes(6)
                bits = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
            } else if (chunk == "data") { dataBytes = length; dataOffset = start }
            input.seek(start + length + (length and 1L))
        }
        require(channels in 1..8 && rate in 8000..192000 && dataBytes > 0 &&
            ((format == 1 && bits in listOf(8, 16)) || (format == 3 && bits == 32))) { "Unsupported local speech audio format" }
        Layout(format, channels, rate, bits, dataOffset, dataBytes).also { require(it.frames > 0) }
    }
    fun durationMs(file: File): Long = layout(file).let { (it.frames * 1000 / it.rate).coerceAtLeast(1) }

    internal fun header(rate: Int, bytes: Int) = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(bytes).array()

    /** Join one bounded text window, keeping all samples and a short beat between utterances.
     * Matching PCM is copied directly; other installed voices are streamed into mono PCM16. */
    fun join(files: List<File>, directory: File, gapMs: Int = 90, forcePcm16: Boolean = false): File {
        require(files.isNotEmpty() && gapMs in 0..1000)
        if (files.size == 1 && (!forcePcm16 || layout(files.single()).let { it.format == 1 && it.bits == 16 && it.channels == 1 })) return files.single()
        directory.mkdirs()
        val identity = "join-v1:$gapMs:" + files.joinToString("|") { it.name } + if (forcePcm16) ":pcm16" else ""
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        val result = File(directory, "$key.wav")
        if (result.exists() && runCatching { durationMs(result) > 0 }.getOrDefault(false)) return result
        val layouts = files.map(::layout)
        val rate = layouts.first().rate
        val gapFrames = rate.toLong() * gapMs / 1000
        val total = layouts.sumOf { it.frames * rate / it.rate } + gapFrames * (files.size - 1)
        require(total * 2 < Int.MAX_VALUE - 44) { "Speech window is too large" }
        val temporary = File(directory, "$key.joining")
        try {
            temporary.outputStream().buffered().use { output ->
                output.write(header(rate, (total * 2).toInt()))
                files.forEachIndexed { index, file ->
                    val spec = layouts[index]
                    BufferedInputStream(file.inputStream(), 32768).use { input ->
                        var remaining = spec.offset
                        while (remaining > 0) { val count = input.skip(remaining); require(count > 0); remaining -= count }
                        if (spec.format == 1 && spec.bits == 16 && spec.channels == 1 && spec.rate == rate) {
                            val buffer = ByteArray(32768); var left = spec.frames * 2
                            while (left > 0) { val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); require(count > 0); output.write(buffer, 0, count); left -= count }
                        } else {
                            fun byte(): Int = input.read().also { require(it >= 0) { "Incomplete local speech audio" } }
                            fun frame(): Float {
                                var sum = 0f
                                repeat(spec.channels) {
                                    sum += when (spec.bits) {
                                        8 -> (byte() - 128) / 128f
                                        16 -> (byte() or (byte() shl 8)).toShort() / 32768f
                                        else -> Float.fromBits(byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24)).let { if (it.isFinite()) it else 0f }
                                    }
                                }
                                return sum / spec.channels
                            }
                            var currentIndex = 0L; var a = frame(); var b = if (spec.frames > 1) frame() else a
                            val count = spec.frames * rate / spec.rate
                            for (i in 0 until count) {
                                val position = i.toDouble() * spec.rate / rate
                                val target = position.toLong().coerceAtMost(spec.frames - 1)
                                while (currentIndex < target) { a = b; currentIndex++; b = if (currentIndex + 1 < spec.frames) frame() else a }
                                val sample = (a + (b - a) * (position - target).toFloat()).coerceIn(-1f, 1f)
                                val pcm = (sample * 32767).roundToInt(); output.write(pcm and 255); output.write((pcm shr 8) and 255)
                            }
                        }
                    }
                    if (index < files.lastIndex) silence(output, gapFrames * 2)
                }
            }
            check(temporary.renameTo(result)) { "Could not assemble chapter audio" }
            return result
        } finally { temporary.delete() }
    }
    private fun silence(output: OutputStream, length: Long) {
        val zeros = ByteArray(4096); var left = length
        while (left > 0) { val count = minOf(left, zeros.size.toLong()).toInt(); output.write(zeros, 0, count); left -= count }
    }
    fun pause(directory: File): File {
        val file = File(directory, "narration-pause-v1.wav")
        if (!file.exists()) {
            val samples = 3200 // 400 ms, mono PCM16 at 8 kHz.
            file.outputStream().use { it.write(header(8000, samples * 2)); it.write(ByteArray(samples * 2)) }
        }
        return file
    }
}
