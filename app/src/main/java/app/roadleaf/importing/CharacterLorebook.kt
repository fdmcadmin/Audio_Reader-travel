package app.roadleaf.importing

import app.roadleaf.playback.*
import org.json.JSONObject

/** Reads collection-exported voice metadata only; full lorebooks/modules are rejected. */
object CharacterLorebook {
    const val MAX_BYTES = 2 * 1024 * 1024
    private fun isKokoroService(value: String): Boolean = value.trim().lowercase(java.util.Locale.ROOT)
        .replace(Regex("[\\s_/]+"), "-") in setOf("sherpa-onnx-kokoro", "roadleaf.native.kokoro")

    private fun profileVoice(value: String, kokoro: Boolean): String {
        require(value.length <= 160 && !value.contains('\n')) { "Keep voice IDs short." }
        if (!kokoro || value.isBlank()) return value
        return if (value.startsWith("kokoro:")) {
            require(NativeKokoro.speakerId(value) != null) { "Unknown English Kokoro speaker: $value" }
            value
        } else NativeKokoro.voiceIdForSpeaker(value)
            ?: error("Unknown English Kokoro speaker: $value")
    }
    fun profileVoiceTag(tag: JSONObject): String {
        require(tag.has("service") && tag.has("model") && tag.has("voice") && tag.has("sid")) {
            "A character voice profile needs service, model, voice and speaker ID."
        }
        require(tag.opt("sid") is Number) { "The character voice speaker ID must be a number." }
        return NativeKokoro.voiceIdForTag(tag.getString("service"), tag.getString("model"), tag.getString("voice"), tag.getInt("sid"))
    }
    data class Header(val id: String, val name: String, val category: String = "", val enabled: Boolean = true, val keys: List<String> = emptyList(),
        val voice: String = "", val pronoun: CharacterPronoun = CharacterPronoun.AUTO, val delivery: CharacterDelivery = CharacterDelivery.FOLLOW_STORY)
    data class Profiles(val id: String, val characters: List<CastCharacter>, val engine: String = "", val mainCharacter: String = "",
        val mainVoice: String = "", val narratorVoice: String = "", val mainPronoun: CharacterPronoun = CharacterPronoun.AUTO,
        val correctedModelLabels: Int = 0) {
        val voices get() = (characters.map { it.voice } + mainVoice + narratorVoice).filter(String::isNotBlank).toSet()
    }
    fun parse(json: String): List<CastCharacter> = decode(json).characters
    fun decode(json: String): Profiles {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Lorebook limit is 2 MB." }
        val root = JSONObject(json)
        require(root.optString("format") == "roadleaf-scene-profiles" && root.optInt("version") == 1) { "Choose the voice-profiles JSON from your personal story collection. Full NovelAI modules stay in the collection." }
        val id = root.getString("profileSetId")
        require(id.isNotBlank() && id.length <= 120) { "A profile-set ID is required." }
        val entries = root.getJSONArray("entries")
        require(entries.length() <= 512) { "Keep the lorebook to 512 entries." }
        val engine = root.optString("enginePackage")
        val services = (listOf(root.optString("voiceService")) + (0 until entries.length()).map { entries.getJSONObject(it).optString("voiceService") } +
            (0 until entries.length()).map { entries.getJSONObject(it).optJSONObject("ttsProfile")?.optString("service").orEmpty() })
            .filter(String::isNotBlank)
        require(services.all { isKokoroService(it) || it.equals("sherpa-onnx", true) }) { "This voice-profile service is not supported by Roadleaf." }
        val kokoro = services.isNotEmpty() || engine == NativeKokoro.ENGINE_ID
        require(!kokoro || engine.isBlank() || engine == NativeKokoro.ENGINE_ID) { "The profile service and speech engine disagree." }
        return Profiles(id, headers((0 until entries.length()).map { i ->
            val item = entries.getJSONObject(i)
            require(!item.has("text") && !item.has("contextConfig") && !item.has("loreBiasGroups")) { "This file contains module content. Import only the collection's voice-profile metadata." }
            Header(item.optString("id"), item.optString("displayName"), item.optString("category"), item.optBoolean("enabled", true),
                item.optJSONArray("keys")?.let { array -> (0 until minOf(array.length(), 24)).map { array.getString(it) } }.orEmpty(),
                item.optJSONObject("ttsProfile")?.let { tag ->
                    profileVoiceTag(tag).also { resolved ->
                        require(item.optString("voice").isBlank() || profileVoice(item.optString("voice"), kokoro) == resolved) {
                            "A character has conflicting Kokoro voice tags."
                        }
                    }
                } ?: profileVoice(item.optString("voice"), kokoro),
                item.optString("pronoun", "AUTO").let { raw -> CharacterPronoun.entries.firstOrNull { it.name == raw } ?: error("Unknown character pronoun.") },
                item.optString("delivery", "FOLLOW_STORY").let { raw -> CharacterDelivery.entries.firstOrNull { it.name == raw } ?: error("Unknown character delivery.") })
        }), if (kokoro) NativeKokoro.ENGINE_ID else engine, root.optString("mainCharacter"),
            profileVoice(root.optString("mainVoice"), kokoro), profileVoice(root.optString("narratorVoice"), kokoro),
            root.optString("mainPronoun", "AUTO").let { raw -> CharacterPronoun.entries.firstOrNull { it.name == raw } ?: error("Unknown main pronoun.") },
            (0 until entries.length()).count { i -> val item = entries.getJSONObject(i); item.optBoolean("enabled", true) &&
                NativeKokoro.correctedV11Label(item.optJSONObject("ttsProfile")?.optString("model").orEmpty()) }).also {
            require(it.engine.length <= 160 && it.mainCharacter.length <= 240 && it.mainVoice.length <= 160 && it.narratorVoice.length <= 160) { "Keep voice settings short." }
        }
    }
    fun headers(entries: List<Header>): List<CastCharacter> {
        require(entries.size <= 512) { "Keep the lorebook to 512 entries." }
        val result = entries.mapNotNull { item ->
            val display = item.name
            val character = item.id.startsWith("char_") || Regex("^\\d+[ \\t]+.+[—–-][ \\t]*Preset$").matches(display) || item.category.equals("Characters", true)
            if (!character || !item.enabled) return@mapNotNull null
            val keys = item.keys.take(24).map(String::trim).filter { it.isNotBlank() && !it.startsWith('/') && !it.contains('&') && it.length <= 80 }
            val name = keys.firstOrNull { !it.startsWith("char_") } ?: display.replace(Regex("^\\d+[ \\t]+"), "").replace(Regex("[ \\t]*[—–-][ \\t]*Preset$"), "").trim()
            require(name.isNotBlank() && name.length <= 80) { "A character has an invalid name." }
            CastCharacter(name, keys.filter { !it.equals(name, true) }.joinToString(",").take(240), if (item.voice.isBlank()) CastRole.AUTO else CastRole.CUSTOM,
                item.voice, item.delivery, pronoun = item.pronoun, profileId = item.id)
        }
        require(result.isNotEmpty() && result.size <= 64) { "Import 1–64 character entries (char_ IDs or Characters category)." }
        require(result.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == result.size) { "Character names must be unique." }
        return result
    }
    fun merge(existing: BookVoiceCast, imported: List<CastCharacter>, profileSetId: String = existing.profileSetId): BookVoiceCast {
        require(existing.profileSetId.isBlank() || existing.profileSetId == profileSetId) { "This story uses another profile set. Choose its own collection file." }
        fun same(a: CastCharacter, b: CastCharacter) = a.profileId.isNotBlank() && a.profileId == b.profileId || a.name.equals(b.name, true)
        val retained = existing.characters.map { current ->
            val fresh = imported.firstOrNull { same(current, it) } ?: return@map current
            val applyVoice = fresh.role == CastRole.CUSTOM && fresh.voice.isNotBlank() &&
                (current.voice.isBlank() || current.role == CastRole.AUTO)
            current.copy(name = fresh.name, profileId = fresh.profileId.ifBlank { current.profileId },
                role = if (applyVoice) CastRole.CUSTOM else current.role, voice = if (applyVoice) fresh.voice else current.voice,
                aliases = (current.aliases.split(',') + fresh.aliases.split(',') + current.name.takeUnless { it.equals(fresh.name, true) }.orEmpty()).map(String::trim).filter(String::isNotBlank).distinct().joinToString(",").take(240))
        }
        return existing.copy(sceneOnly = true, profileSetId = profileSetId, characters = retained + imported.filter { fresh -> existing.characters.none { same(it, fresh) } }).validated()
    }
    fun merge(existing: BookVoiceCast, imported: Profiles): BookVoiceCast {
        val linked = merge(existing, imported.characters, imported.id)
        val sameMain = existing.mainCharacter.isBlank() || existing.mainCharacter.split(',').any { old -> imported.mainCharacter.split(',').any { it.trim().equals(old.trim(), true) } }
        return linked.copy(mainCharacter = existing.mainCharacter.ifBlank { imported.mainCharacter },
            mainVoice = if (sameMain) existing.mainVoice.ifBlank { imported.mainVoice } else existing.mainVoice,
            mainPronoun = if (sameMain && existing.mainPronoun == CharacterPronoun.AUTO) imported.mainPronoun else existing.mainPronoun,
            narratorVoice = existing.narratorVoice.ifBlank { imported.narratorVoice }).validated()
    }
}
