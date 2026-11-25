package app.roadleaf.playback

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** The IDs carry the recipe itself so a saved character can be resolved after an app restart or export. */
class AsuVoiceBank(original: List<String>, val starters: List<String> = original, blockedPairs: List<Pair<String, String>> = emptyList()) {
    private val starterIds = starters.toSet()
    private val rows = original.associateBy { sources(it).first() }
    private val blocked = buildMap<String, MutableSet<String>> {
        for ((a, b) in blockedPairs) {
            require(a != b && a in rows && b in rows)
            getOrPut(a) { mutableSetOf() }.add(b)
            getOrPut(b) { mutableSetOf() }.add(a)
        }
    }

    init {
        require(original.size == 161 && rows.size == 161 && original.all(::accepts))
        require(original.count { family(it) == 'M' } == 80 && original.count { family(it) == 'F' } == 81)
        require(starterIds.size == 161 && starters.all(::allowsNewRecipe))
        require(starters.map { sources(it).first() }.toSet() == rows.keys)
    }

    fun accepts(id: String): Boolean {
        if (id in RoadleafAsuSpeech.narrators) return true
        if (!id.startsWith(RoadleafAsuSpeech.PREFIX) || id.length > 240) return false
        val codes = sources(id)
        return codes.size == 4 && codes.toSet().size == 4 && codes.all(rows::containsKey) &&
            family(id) != null && family(id) == family(rows.getValue(codes.first()))
    }

    fun allowsNewRecipe(id: String): Boolean {
        if (!isCharacterVoice(id)) return false
        val slots = sources(id)
        return slots.indices.all { i -> (0 until i).none { j -> slots[j] in blocked[slots[i]].orEmpty() } }
    }

    fun next(wanted: Char?, used: Set<String>): String {
        val available = starters.filter { it !in used && rows.getValue(sources(it).first()) !in used }
        available.firstOrNull { wanted == null || family(it) == wanted }?.let { return it }
        available.firstOrNull()?.let { return it }
        val leads = starters.filter { wanted == null || family(it) == wanted }
        val donors = rows.keys.toList()
        for (shift in donors.indices) {
            for ((index, starter) in leads.withIndex()) {
                val (lead, second, third, _) = sources(starter)
                val donor = donors[(index + shift) % donors.size]
                if (donor == lead || donor == second || donor == third) continue
                val choice = "${RoadleafAsuSpeech.PREFIX}${family(starter)}:$lead~$third~$second~$donor"
                if (choice !in starterIds && choice !in used && allowsNewRecipe(choice)) return choice
            }
        }
        error("The ASU voice bank could not choose another unique recipe.")
    }

    fun isCharacterVoice(id: String) = accepts(id) && id !in RoadleafAsuSpeech.narrators

    fun assignImported(cast: BookVoiceCast, currentNarrator: String, learned: LearnedCast): VoiceDelegation.Result {
        val manual = (cast.characters.filter { it.role == CastRole.CUSTOM }.map { it.voice } + cast.mainVoice).filter(::isCharacterVoice).toSet()
        val narrator = listOf(cast.narratorVoice, currentNarrator, RoadleafAsuSpeech.NARRATOR_F).first { accepts(it) && it !in manual }
        val reserved = mutableSetOf(narrator)
        fun viewport(saved: String, fallback: String): String {
            if (!cast.separateViewpointNarrators && saved.isBlank()) return ""
            val id = saved.takeIf { accepts(it) && family(it) == family(fallback) && it !in manual && (it !in reserved || it == narrator) } ?: fallback
            reserved += id
            return id
        }
        val femaleNarrator = viewport(cast.femaleNarratorVoice, RoadleafAsuSpeech.NARRATOR_F)
        val maleNarrator = viewport(cast.maleNarratorVoice.takeUnless { it == femaleNarrator }.orEmpty(), RoadleafAsuSpeech.NARRATOR_M)
        val keep = reserved.toMutableSet()
        val saved = learned.people.filter { it.engine == RoadleafAsuSpeech.ENGINE_ID && isCharacterVoice(it.voice) }
            .associate { AdaptiveCast.nameKey(it.name) to it.voice }
        val used = (reserved + manual.filter(::isCharacterVoice) + saved.values).toMutableSet()
        val missing = linkedSetOf<String>()
        var assigned = 0
        fun choose(name: String, original: String, wanted: Char?): String {
            if (isCharacterVoice(original) && keep.add(original)) return original
            if (original.isNotBlank() && !accepts(original)) missing += original
            saved[AdaptiveCast.nameKey(name)]?.takeIf { it !in manual && keep.add(it) }?.let { return it }
            assigned++
            return next(wanted, used).also { used += it; keep += it }
        }
        val mainName = cast.mainCharacter.substringBefore(',').trim()
        val savedMain = cast.mainVoice.ifBlank {
            cast.characters.firstOrNull { AdaptiveCast.nameKey(it.name) == AdaptiveCast.nameKey(mainName) && it.role == CastRole.CUSTOM }?.voice.orEmpty()
        }
        val mainVoice = if (mainName.isBlank()) "" else choose(mainName, savedMain, family(cast.mainPronoun))
        val characters = cast.characters.map { character ->
            if (character.role == CastRole.NARRATOR) character
            else {
                val wanted = family(character.pronoun) ?: when (character.role) {
                    CastRole.FEMALE -> 'F'
                    CastRole.MALE -> 'M'
                    else -> null
                }
                val voice = if (AdaptiveCast.nameKey(character.name) == AdaptiveCast.nameKey(mainName) && mainVoice.isNotBlank()) mainVoice
                    else choose(character.name, character.voice, wanted)
                character.copy(role = CastRole.CUSTOM, voice = voice)
            }
        }
        val genders = reserved + characters.filter { it.role == CastRole.CUSTOM }.map { it.voice } + mainVoice
        val updated = cast.copy(mainVoice = mainVoice, characters = characters, narratorVoice = narrator,
            femaleNarratorVoice = femaleNarrator, maleNarratorVoice = maleNarrator, exclusiveVoices = true,
            femaleVoice = cast.femaleVoice.takeIf { accepts(it) && it !in genders }.orEmpty(),
            maleVoice = cast.maleVoice.takeIf { accepts(it) && it !in genders }.orEmpty())
        return VoiceDelegation.Result(updated, assigned, emptyList(), missing.toList(), false, starters.size)
    }

    companion object {
        fun family(id: String): Char? = when {
            id == RoadleafAsuSpeech.NARRATOR_F -> 'F'
            id == RoadleafAsuSpeech.NARRATOR_M -> 'M'
            id.startsWith(RoadleafAsuSpeech.PREFIX + "F:") -> 'F'
            id.startsWith(RoadleafAsuSpeech.PREFIX + "M:") -> 'M'
            else -> null
        }

        fun family(pronoun: CharacterPronoun): Char? = when (pronoun) {
            CharacterPronoun.HE -> 'M'
            CharacterPronoun.SHE -> 'F'
            else -> null
        }

        private fun sources(id: String) = id.removePrefix(RoadleafAsuSpeech.PREFIX).substringAfter(':').split('~')
    }
}

internal object RoadleafAsuSpeech {
    const val ENGINE_ID = "roadleaf.asu.desktop"
    const val LABEL = "Roadleaf ASU voices · PC companion"
    const val PREFIX = "roadleaf-asu:"
    const val NARRATOR_F = PREFIX + "narrator-f"
    const val NARRATOR_M = PREFIX + "narrator-m"
    val narrators = listOf(NARRATOR_F, NARRATOR_M)
    private const val MODEL = "qwen3-base-0.6b/asu-four-slot-v1"
    private const val READY = "$MODEL/block-v1"
    private const val URL_ROOT = "http://127.0.0.1:18765"
    const val MAX_SPAN = 300
    private const val MAX_AUDIO = 3_000_000
    private const val BLOCK_BYTES = 64 * 1024
    private var cached: AsuVoiceBank? = null

    @Synchronized
    fun bank(context: Context): AsuVoiceBank = cached ?: context.assets.open("roadleaf-asu/voices.json").bufferedReader().use {
        val asset = JSONObject(it.readText())
        check(asset.getString("model") == MODEL)
        val voices = asset.getJSONArray("voices")
        val original = List(voices.length(), voices::getString)
        val starters = asset.optJSONArray("starters")?.let { List(it.length(), it::getString) } ?: original
        val pairs = asset.optJSONArray("blockedPairs")?.let { blocked ->
            List(blocked.length()) { index -> blocked.getJSONArray(index).let {
                require(it.length() == 2)
                it.getString(0) to it.getString(1)
            } }
        }.orEmpty()
        AsuVoiceBank(original, starters, pairs).also { loaded -> cached = loaded }
    }

    fun options(context: Context) = (narrators + bank(context).starters).map { OfflineVoiceOption(it, "en-US", 400, 100) }

    fun label(id: String): String? = when {
        id == NARRATOR_F -> "ASU · female narrator"
        id == NARRATOR_M -> "ASU · male narrator"
        AsuVoiceBank.family(id) != null -> "ASU · " + id.substringAfterLast(':').split('~').joinToString(" / ")
        else -> null
    }

    private fun request(path: String) = (URL(URL_ROOT + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 3_000
        readTimeout = 180_000
    }

    suspend fun ready(): Boolean = withContext(Dispatchers.IO) {
        val connection = request("/ready").apply { readTimeout = 2_000 }
        try { connection.responseCode == 200 && connection.inputStream.bufferedReader().use { it.readText() } == READY }
        catch (_: java.io.IOException) { false }
        finally { connection.disconnect() }
    }

    suspend fun render(context: Context, text: String, requested: String, fallback: String?, pitch: Float, folder: File): File =
        withContext(Dispatchers.IO) {
            val bank = bank(context)
            require(!requested.startsWith(PREFIX) || bank.accepts(requested)) { "The saved ASU voice recipe is invalid." }
            val id = requested.takeIf(bank::accepts) ?: fallback?.takeIf(bank::accepts) ?: NARRATOR_F
            require(text.length in 1..320)
            val file = File(folder, AudioCacheKey.create(ENGINE_ID, "$MODEL/$id", 1f, text) + ".wav")
            if (file.isFile && runCatching { WaveAudio.durationMs(file) > 0 }.getOrDefault(false))
                return@withContext WavePitch.adjust(file, pitch)
            folder.mkdirs()
            val partial = File.createTempFile("asu-", ".part", folder)
            val connection = request("/speech").apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                val body = JSONObject().put("voice", id).put("text", text).toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                check(connection.responseCode == 200 && connection.contentType == "application/json" && connection.contentLength in 1..512) {
                    "The Roadleaf PC companion could not prepare this span. Check that it is up to date."
                }
                val info = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                val size = info.getInt("size")
                val clip = info.getString("clip")
                check(size in 46..MAX_AUDIO && clip.matches(Regex("[a-f0-9]{32}"))) { "The Roadleaf PC companion sent invalid audio." }
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BLOCK_BYTES)
                    var offset = 0
                    while (offset < size) {
                        currentCoroutineContext().ensureActive()
                        val count = minOf(size - offset, buffer.size)
                        try { readBlock(clip, offset, buffer, count) }
                        catch (_: java.io.IOException) { readBlock(clip, offset, buffer, count) }
                        output.write(buffer, 0, count)
                        offset += count
                    }
                }
                check(WaveAudio.durationMs(partial) > 0 && (partial.renameTo(file) || file.length() == size.toLong())) { "Could not save Roadleaf ASU speech." }
                WavePitch.adjust(file, pitch)
            } catch (error: java.io.IOException) {
                throw IllegalStateException("Connect the Roadleaf PC companion before playing ASU voices.", error)
            } finally {
                partial.delete()
                connection.disconnect()
            }
        }

    private fun readBlock(clip: String, offset: Int, buffer: ByteArray, length: Int) {
        val chunk = request("/audio/$clip/$offset").apply { readTimeout = 5_000 }
        try {
            check(chunk.responseCode == 200 && chunk.contentLength == length) { "The Roadleaf PC companion sent an incomplete block." }
            DataInputStream(chunk.inputStream).use { it.readFully(buffer, 0, length) }
        } finally { chunk.disconnect() }
    }
}
