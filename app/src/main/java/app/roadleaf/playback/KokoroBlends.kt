package app.roadleaf.playback

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Appends stable 60/40 style vectors as extra speaker IDs for Sherpa ONNX. */
internal object KokoroBlends {
    private const val ORIGINAL_SPEAKERS = 54
    private const val FLOATS_PER_STYLE = 510 * 256
    private const val BYTES_PER_STYLE = FLOATS_PER_STYLE * 4

    fun create(original: File, blended: File) {
        require(original.length() == ORIGINAL_SPEAKERS.toLong() * BYTES_PER_STYLE) {
            "Unexpected Kokoro voice-vector layout."
        }
        val partial = File(blended.parentFile, blended.name + ".part")
        try {
            RandomAccessFile(original, "r").use { input ->
                FileOutputStream(partial).buffered().use { output ->
                    original.inputStream().buffered().use { it.copyTo(output) }
                    val aBytes = ByteArray(BYTES_PER_STYLE)
                    val bBytes = ByteArray(BYTES_PER_STYLE)
                    val result = ByteBuffer.allocate(BYTES_PER_STYLE).order(ByteOrder.LITTLE_ENDIAN)
                    for ((a, b) in NativeKokoro.blendPairs()) {
                        input.seek(a.toLong() * BYTES_PER_STYLE); input.readFully(aBytes)
                        input.seek(b.toLong() * BYTES_PER_STYLE); input.readFully(bBytes)
                        val first = ByteBuffer.wrap(aBytes).order(ByteOrder.LITTLE_ENDIAN)
                        val second = ByteBuffer.wrap(bBytes).order(ByteOrder.LITTLE_ENDIAN)
                        result.clear()
                        repeat(FLOATS_PER_STYLE) {
                            result.putFloat(first.getFloat() * 0.6f + second.getFloat() * 0.4f)
                        }
                        output.write(result.array())
                    }
                }
            }
            check(partial.renameTo(blended)) { "Could not install blended Kokoro voices." }
        } finally { partial.delete() }
    }
}
