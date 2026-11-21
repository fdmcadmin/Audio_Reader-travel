package app.roadleaf.playback

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Materializes the supplemental RVS presets with the installed legacy voice table. */
internal class SupertonicStep3Pack private constructor(val presets: List<Preset>, private val digest: String) {
    data class Preset(val name: String, val speed: Float) {
        val id get() = "$PREFIX$name"
    }

    companion object {
        private const val DIR = "supertonic3-step3"
        private const val PREFIX = "supertonic3-rvs:"
        private const val HEADER = 48
        private const val TIMBRE_BYTES = 50 * 256 * 4
        private val namePattern = Regex("[FM][A-Za-z0-9-]{1,70}")
        fun name(id: String): String? = id.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
            ?.takeIf(namePattern::matches)

        fun read(context: Context): SupertonicStep3Pack? {
            if (context.assets.list(DIR)?.contains("voices.tsv") != true) return null
            val lines = context.assets.open("$DIR/voices.tsv").bufferedReader().use { it.readLines() }
            val header = lines.first().split('\t')
            require(header.size == 2 && header[0] == "rvs-0.2/0.4.0" && header[1].matches(Regex("[a-f0-9]{64}"))) {
                "Unknown Supertonic preset pack."
            }
            val presets = lines.drop(1).map { row ->
                val fields = row.split('\t')
                val speed = fields.getOrNull(1)?.toFloatOrNull()
                require(fields.size == 2 && namePattern.matches(fields[0]) && speed != null && speed in 0.5f..2f) {
                    "Invalid Supertonic preset."
                }
                Preset(fields[0], speed)
            }
            require(presets.size in 1..256 && presets.distinctBy { it.name }.size == presets.size)
            return SupertonicStep3Pack(presets, header[1])
        }
    }

    fun mergedTable(context: Context, legacy: File, legacyCount: Int): File {
        val result = File(context.cacheDir, "supertonic3-$legacyCount-$digest.bin")
        val combinedCount = legacyCount + presets.size
        if (result.length() == SupertonicVoiceIndex.tableSize(combinedCount)) return result
        val extra = context.assets.open("$DIR/voice.bin").use { it.readBytes() }
        require(extra.size.toLong() == SupertonicVoiceIndex.tableSize(presets.size))
        require(MessageDigest.getInstance("SHA-256").digest(extra).joinToString("") { "%02x".format(it) } == digest) {
            "The bundled Supertonic presets did not match their checksum."
        }
        val original = legacy.readBytes()
        val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        longArrayOf(combinedCount.toLong(), 50, 256, combinedCount.toLong(), 8, 16).forEach(header::putLong)
        val staged = File(context.cacheDir, result.name + "." + UUID.randomUUID() + ".part")
        try {
            staged.outputStream().buffered().use { output ->
                output.write(header.array())
                output.write(original, HEADER, legacyCount * TIMBRE_BYTES)
                output.write(extra, HEADER, presets.size * TIMBRE_BYTES)
                val originalDuration = HEADER + legacyCount * TIMBRE_BYTES
                val extraDuration = HEADER + presets.size * TIMBRE_BYTES
                output.write(original, originalDuration, original.size - originalDuration)
                output.write(extra, extraDuration, extra.size - extraDuration)
            }
            check(staged.renameTo(result) || result.length() == staged.length()) { "Could not prepare Supertonic presets." }
        } finally {
            staged.delete()
        }
        return result
    }

    fun cacheIdentity(voice: Preset) = "${voice.id}:$digest"
}
