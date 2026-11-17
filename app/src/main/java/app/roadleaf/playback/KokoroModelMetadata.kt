package app.roadleaf.playback

import java.io.File
import java.io.RandomAccessFile

/** Extends only the ONNX ModelProto n_speakers metadata from 54 to 157. */
internal object KokoroModelMetadata {
    private val old = byteArrayOf(
        0x72, 0x10, 0x0a, 0x0a,
        'n'.code.toByte(), '_'.code.toByte(), 's'.code.toByte(), 'p'.code.toByte(),
        'e'.code.toByte(), 'a'.code.toByte(), 'k'.code.toByte(), 'e'.code.toByte(),
        'r'.code.toByte(), 's'.code.toByte(), 0x12, 0x02,
        '5'.code.toByte(), '4'.code.toByte()
    )

    fun expandSpeakers(model: File) {
        RandomAccessFile(model, "rw").use { file ->
            val originalLength = file.length()
            require(originalLength > 300_000_000L) { "Unexpected Kokoro model size." }
            val tailSize = minOf(originalLength, 1_048_576L).toInt()
            val window = ByteArray(tailSize)
            file.seek(originalLength - tailSize)
            file.readFully(window)
            val matches = (0..window.size - old.size).filter { at ->
                old.indices.all { window[at + it] == old[it] }
            }
            require(matches.size == 1) { "Cannot safely locate Kokoro speaker-count metadata." }
            val field = originalLength - tailSize + matches.single()
            val insertion = field + old.size
            val followingSize = (originalLength - insertion).toInt()
            val following = ByteArray(followingSize)
            file.seek(insertion)
            file.readFully(following)
            file.setLength(originalLength + 1)
            file.seek(insertion + 1)
            file.write(following)
            file.seek(field + 1); file.write(0x11) // metadata entry: 16 -> 17 bytes
            file.seek(field + old.size - 3); file.write(0x03) // value: 2 -> 3 bytes
            file.seek(field + old.size - 2)
            file.write(byteArrayOf('1'.code.toByte(), '5'.code.toByte(), '7'.code.toByte()))
        }
    }
}
