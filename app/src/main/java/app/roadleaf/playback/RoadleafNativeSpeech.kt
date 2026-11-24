package app.roadleaf.playback

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Roadleaf's own English synthesis model and its installed numeric voice roster. */
internal object RoadleafNativeSpeech {
    const val ENGINE_ID = "roadleaf.native.formant"
    const val LABEL = "Roadleaf Native · experimental English"
    const val PREFIX = "roadleaf-native:"
    const val MODEL = "roadleaf-formant-v1"
    const val EXAMPLE = "F:[(1/1),10,0,0]|[0,5,4]"
    private val base = Regex("""([FM])(0[1-9]|1[0-5])""")
    private val recipe = Regex("""([FM]):\[([^\[\]]+)]\|\[([0-9,]+)]""")
    private val pair = Regex("""\(([1-9]|1[0-5])/(0|[1-9]|1[0-5])\)""")
    private val source = Regex("0|[1-9]|1[0-5]")
    private const val SAVED = "roadleaf-native-recipes"
    private var packaged: Map<String, RoadleafFormant.Voice>? = null

    data class Entry(val id: String, val voice: RoadleafFormant.Voice)

    /** Both the user-facing notation and a stored ID resolve to the same cache and voice identity. */
    fun parse(value: String): Entry? {
        if (value.length !in 1..160) return null
        val code = value.removePrefix(PREFIX).filterNot(Char::isWhitespace)
        base.matchEntire(code)?.let { match ->
            val number = match.groupValues[2].toInt()
            return Entry(PREFIX + code, RoadleafFormant.Voice(code.first(), listOf(number.toString(), "0", "0", "0"), 0, 0, 0))
        }
        val match = recipe.matchEntire(code) ?: return null
        val slots = match.groupValues[2].split(',')
        val controls = match.groupValues[3].split(',').map { it.toIntOrNull() ?: return null }
        if (slots.size != 4 || controls.size != 3 || slots.first() == "0") return null
        val numbers = slots.mapIndexed { index, slot ->
            if (source.matches(slot)) slot
            else if (index == 0 && pair.matches(slot)) slot.removeSurrounding("(", ")")
            else return null
        }
        val family = match.groupValues[1].single()
        val id = "$PREFIX$family:[${slots.joinToString(",")}]|[${controls.joinToString(",")}]"
        return Entry(id, RoadleafFormant.Voice(family, numbers, controls[0], controls[1], controls[2]))
    }

    @Synchronized
    private fun roster(context: Context): Map<String, RoadleafFormant.Voice> {
        packaged?.let { return it }
        val root = context.assets.open("roadleaf-native/voices.json").bufferedReader().use { JSONObject(it.readText()) }
        check(root.getString("format") == "roadleaf-native-voices" && root.getInt("version") == 1 && root.getString("model") == MODEL)
        val values = root.getJSONArray("voices")
        val entries = buildMap {
            for (index in 0 until values.length()) {
                val saved = values.getJSONObject(index)
                val entry = requireNotNull(parse(saved.getString("id"))) { "Invalid Roadleaf voice ID." }
                val slots = saved.getJSONArray("sources")
                require(slots.length() == 4)
                val voice = RoadleafFormant.Voice(saved.getString("family").single(),
                    List(4) { slots.getString(it).removeSurrounding("(", ")") }, saved.getInt("emotion"), saved.getInt("presence"), saved.getInt("age"))
                check(voice == entry.voice && put(entry.id, voice) == null) { "Roadleaf voice settings disagree with their ID." }
            }
        }
        check(entries.containsKey(PREFIX + "F01")) { "Roadleaf Native is missing its default voice." }
        packaged = entries
        return entries
    }

    private fun saved(context: Context) = context.getSharedPreferences(SAVED, Context.MODE_PRIVATE)

    fun rememberRecipes(context: Context, ids: Iterable<String>) {
        val stock = roster(context)
        val custom = ids.mapNotNull { parse(it)?.id }.filter { it !in stock }
        if (custom.isEmpty()) return
        val preferences = saved(context)
        val previous = preferences.getStringSet("voices", emptySet()).orEmpty()
        preferences.edit().putStringSet("voices", previous + custom).apply()
    }

    fun options(context: Context): List<OfflineVoiceOption> {
        val recipes = saved(context).getStringSet("voices", emptySet()).orEmpty().mapNotNull { parse(it)?.id }.sorted()
        return (roster(context).keys + recipes).map { OfflineVoiceOption(it, "en-US", 200, 100) }
    }

    fun label(id: String): String? = if (id.startsWith(PREFIX))
        parse(id)?.let { "Roadleaf · " + it.id.removePrefix(PREFIX) } else null

    suspend fun render(context: Context, text: String, requested: String, fallback: String?, pitch: Float, folder: File): File =
        withContext(Dispatchers.Default) {
            val stock = roster(context)
            val entry = parse(requested) ?: run {
                require(!requested.startsWith(PREFIX)) { "Invalid Roadleaf Native voice recipe." }
                fallback?.let(::parse) ?: requireNotNull(parse(PREFIX + "F01"))
            }
            val voice = stock[entry.id] ?: entry.voice
            val key = AudioCacheKey.create(ENGINE_ID, "$MODEL/${entry.id}", pitch, text)
            val file = File(folder, "$key.wav")
            if (file.isFile && runCatching { WaveAudio.durationMs(file) > 0 }.getOrDefault(false)) return@withContext file
            folder.mkdirs()
            val partial = File.createTempFile("native-", ".part", folder)
            try {
                RandomAccessFile(partial, "rw").use { out ->
                    out.write(WaveAudio.header(RoadleafFormant.SAMPLE_RATE, 0))
                    val buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
                    for (part in SpeechUtterances.split(text, 240)) {
                        currentCoroutineContext().ensureActive()
                        val samples = RoadleafFormant.synthesize(part, voice, pitch)
                        var start = 0
                        while (start < samples.size) {
                            val count = minOf(buffer.capacity() / 2, samples.size - start)
                            buffer.asShortBuffer().put(samples, start, count)
                            out.write(buffer.array(), 0, count * 2)
                            start += count
                        }
                    }
                    val bytes = out.length() - 44
                    check(bytes in 2..Int.MAX_VALUE.toLong()) { "Roadleaf Native produced no playable speech." }
                    out.seek(0)
                    out.write(WaveAudio.header(RoadleafFormant.SAMPLE_RATE, bytes.toInt()))
                }
                currentCoroutineContext().ensureActive()
                check(partial.renameTo(file) || file.length() == partial.length()) { "Could not save Roadleaf speech." }
                file
            } finally {
                partial.delete()
            }
        }
}
