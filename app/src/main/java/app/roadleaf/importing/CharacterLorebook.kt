package app.roadleaf.importing

import app.roadleaf.playback.*
import org.json.JSONObject

/** Reads collection-exported voice metadata only; full lorebooks/modules are rejected. */
object CharacterLorebook {
    const val MAX_BYTES = 2 * 1024 * 1024
    data class Header(val id: String, val name: String, val category: String = "", val enabled: Boolean = true, val keys: List<String> = emptyList(),
        val voice: String = "", val pronoun: CharacterPronoun = CharacterPronoun.AUTO, val delivery: CharacterDelivery = CharacterDelivery.FOLLOW_STORY)
    data class Profiles(val id: String, val characters: List<CastCharacter>, val engine: String = "", val mainCharacter: String = "",
        val mainVoice: String = "", val narratorVoice: String = "", val mainPronoun: CharacterPronoun = CharacterPronoun.AUTO) {
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
        return Profiles(id, headers((0 until entries.length()).map { i ->
            val item = entries.getJSONObject(i)
            require(!item.has("text") && !item.has("contextConfig") && !item.has("loreBiasGroups")) { "This file contains module content. Import only the collection's voice-profile metadata." }
            Header(item.optString("id"), item.optString("displayName"), item.optString("category"), item.optBoolean("enabled", true),
                item.optJSONArray("keys")?.let { array -> (0 until minOf(array.length(), 24)).map { array.getString(it) } }.orEmpty(),
                item.optString("voice").also { require(it.length <= 160 && !it.contains('\n')) { "Keep voice IDs short." } },
                item.optString("pronoun", "AUTO").let { raw -> CharacterPronoun.entries.firstOrNull { it.name == raw } ?: error("Unknown character pronoun.") },
                item.optString("delivery", "FOLLOW_STORY").let { raw -> CharacterDelivery.entries.firstOrNull { it.name == raw } ?: error("Unknown character delivery.") })
        }), root.optString("enginePackage"), root.optString("mainCharacter"), root.optString("mainVoice"), root.optString("narratorVoice"),
            root.optString("mainPronoun", "AUTO").let { raw -> CharacterPronoun.entries.firstOrNull { it.name == raw } ?: error("Unknown main pronoun.") }).also {
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
            current.copy(name = fresh.name, profileId = fresh.profileId.ifBlank { current.profileId },
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
