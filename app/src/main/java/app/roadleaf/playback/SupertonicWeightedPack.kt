package app.roadleaf.playback

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import app.roadleaf.sources.SafeHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/** Recipes refer to the installed model's ten stock styles and carry no executable model data. */
internal class SupertonicWeightedPack private constructor(val recipes: List<Recipe>, val digest: String) {
    data class Source(val index: Int, val weight: Int)
    data class Recipe(val name: String, val sources: List<Source>) {
        val id get() = PREFIX + name
    }

    companion object {
        private const val PREFIX = "supertonic3-rvs:"
        private const val FILE = "supertonic-weighted-v1.tsv"
        private const val LIMIT = 16_384
        private const val HEADER = 48
        private const val TIMBRE_FLOATS = 50 * 256
        private const val DURATION_FLOATS = 8 * 16
        const val DOWNLOAD = "https://github.com/fdmcadmin/Audio_Reader-travel/releases/download/v0.3.46/Roadleaf-Weighted-15x15.tsv"
        private const val DOWNLOAD_HASH = "be139620da9abd55fd1c9eec135189cf1b4f8de5e2492a86e1c4bb685b45b8bb"
        private val hosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        private val names = Regex("[FM]-Weighted-(0[1-9]|[1-9][0-9])")
        private val sources = Regex("([FM])([1-5])=([1-9][0-9]?|100)")

        fun label(id: String): String? = id.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf(names::matches)

        private fun hash(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        fun parse(data: ByteArray): SupertonicWeightedPack {
            require(data.size in 1..LIMIT) { "The voice pack file is too large or empty." }
            val rows = data.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
            require(rows.firstOrNull() == "roadleaf-supertonic-weighted-v1") { "This file is not a Roadleaf weighted voice pack." }
            val recipes = rows.drop(1).map { row ->
                val columns = row.split('\t')
                require(columns.size == 2 && names.matches(columns[0])) { "Invalid weighted voice name." }
                val name = columns[0]
                val source = columns[1].split(',').map {
                    val match = requireNotNull(sources.matchEntire(it)) { "Invalid weighted voice source." }
                    require(match.groupValues[1].single() == name.first()) { "Weighted voices require sources from the same voice group." }
                    val index = match.groupValues[2].toInt() - 1 + if (name.first() == 'M') 5 else 0
                    Source(index, match.groupValues[3].toInt())
                }
                require(source.size in 2..4 && source.sumOf { it.weight } == 100 &&
                    source.first().weight >= 50 && source.distinctBy { it.index }.size == source.size) {
                    "A weighted voice needs two to four distinct sources totaling 100, with a primary of at least 50."
                }
                Recipe(name, source)
            }
            require(recipes.size in 1..64 && recipes.distinctBy { it.name }.size == recipes.size) {
                "The voice pack has missing or duplicate voice names."
            }
            return SupertonicWeightedPack(recipes, hash(data))
        }

        fun read(context: Context): SupertonicWeightedPack? = runCatching {
            File(context.filesDir, FILE).inputStream().use { parse(readBounded(it)) }
        }.getOrNull()

        suspend fun install(context: Context, uri: Uri?): SupertonicWeightedPack = withContext(Dispatchers.IO) {
            val data = if (uri == null) {
                SafeHttp.get(DOWNLOAD, LIMIT) { url ->
                    url.scheme == "https" && url.host in hosts && url.userInfo == null && url.port in listOf(-1, 443)
                }.also { require(hash(it) == DOWNLOAD_HASH) { "The GitHub voice pack did not match its checksum." } }
            } else {
                requireNotNull(context.contentResolver.openInputStream(uri)) { "The voice pack could not be opened." }
                    .use(::readBounded)
            }
            val pack = parse(data)
            val target = AtomicFile(File(context.filesDir, FILE))
            val stream = target.startWrite()
            try {
                stream.write(data)
                target.finishWrite(stream)
            } catch (error: Exception) {
                target.failWrite(stream)
                throw error
            }
            pack
        }

        private fun readBounded(input: InputStream): ByteArray {
            val bytes = ByteArray(LIMIT + 1)
            var length = 0
            while (length < bytes.size) {
                val n = input.read(bytes, length, bytes.size - length)
                if (n <= 0) break
                length += n
            }
            require(length <= LIMIT) { "The voice pack file is too large." }
            return bytes.copyOf(length)
        }
    }

    /** The first ten rows are the stock anchors even when Step 3 voices precede these recipes. */
    fun appendTable(baseline: File, count: Int, directory: File): File {
        val result = File(directory, "weighted-${baseline.nameWithoutExtension}-$digest.bin")
        if (result.length() == SupertonicVoiceIndex.tableSize(count + recipes.size)) return result
        val combined = merge(baseline.readBytes(), count)
        val stage = File.createTempFile("weighted-", ".part", directory)
        try {
            stage.writeBytes(combined)
            check(stage.renameTo(result) || result.length() == combined.size.toLong()) { "Could not prepare weighted styles." }
        } finally {
            stage.delete()
        }
        return result
    }

    internal fun merge(base: ByteArray, count: Int): ByteArray {
        require(count >= 10 && base.size.toLong() == SupertonicVoiceIndex.tableSize(count))
        val total = count + recipes.size
        val input = ByteBuffer.wrap(base).order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(SupertonicVoiceIndex.tableSize(total).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        longArrayOf(total.toLong(), 50, 256, total.toLong(), 8, 16).forEach(output::putLong)
        val timbreBytes = count * TIMBRE_FLOATS * 4
        output.put(base, HEADER, timbreBytes)
        blendChannel(input, output, HEADER, 50, 256)
        output.put(base, HEADER + timbreBytes, count * DURATION_FLOATS * 4)
        blendChannel(input, output, HEADER + timbreBytes, 8, 16)
        return output.array()
    }

    private fun blendChannel(input: ByteBuffer, output: ByteBuffer, offset: Int, height: Int, width: Int) {
        for (recipe in recipes) {
            val mix = DoubleArray(width)
            repeat(height) { row ->
                mix.fill(0.0)
                var length = 0.0
                for (source in recipe.sources) {
                    val start = offset + 4 * (source.index * height + row) * width
                    val weight = source.weight / 100.0
                    var norm = 0.0
                    repeat(width) { column ->
                        val value = input.getFloat(start + 4 * column).toDouble()
                        mix[column] += value * weight
                        norm += value * value
                    }
                    length += sqrt(norm) * weight
                }
                val direction = sqrt(mix.sumOf { it * it })
                require(direction.isFinite() && direction > 1e-8) { "Weighted voice sources cancel out." }
                val scale = length / direction
                for (value in mix) output.putFloat((value * scale).toFloat())
            }
        }
    }
}
