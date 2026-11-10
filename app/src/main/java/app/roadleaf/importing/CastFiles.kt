package app.roadleaf.importing

import app.roadleaf.playback.BookVoiceCast
import app.roadleaf.playback.CastCharacter
import app.roadleaf.playback.CastRole
import app.roadleaf.playback.CharacterDelivery
import app.roadleaf.playback.CharacterPronoun
import app.roadleaf.playback.LearnedCast
import app.roadleaf.playback.LearnedGender
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Import and export of a book's cast. Reads Roadleaf cast files, story voice-profile files, NovelAI
 * lorebooks (also inside scenario or story files), lorebook JSON in the common "entries" form and
 * character cards. From lorebooks only names, nicknames and pronoun hints are kept; their text is
 * never stored.
 */
object CastFiles {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val FORMAT = "roadleaf-voice-cast"
    private const val MAX_ENTRIES = 4096

    enum class Kind(val label: String) {
        CAST("Roadleaf cast"), PROFILES("story voice profiles"), NOVELAI("NovelAI lorebook"), LOREBOOK("lorebook"), CARD("character card")
    }

    /** The merged cast to save, how many characters the file supplied and how many it described. */
    data class Imported(val kind: Kind, val cast: BookVoiceCast, val characters: Int, val found: Int, val engine: String = "")

    fun read(json: String, existing: BookVoiceCast): Imported {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Choose a cast or lorebook file under 4 MB." }
        val root = runCatching { JSONObject(json) }.getOrNull() ?: error("This file isn't a cast or lorebook JSON file.")
        val novelAi = novelAi(root)
        return when {
            root.optString("format") == FORMAT -> readCast(root, existing)
            root.optString("format") == "roadleaf-scene-profiles" -> CharacterLorebook.decode(json).let { profiles ->
                Imported(Kind.PROFILES, CharacterLorebook.merge(existing, profiles), profiles.characters.size, profiles.characters.size, profiles.engine)
            }
            novelAi != null -> lorebook(Kind.NOVELAI, novelAiEntries(novelAi), existing)
            root.optString("spec").startsWith("chara_card") || root.has("first_mes") && root.has("name") -> lorebook(Kind.CARD, cardEntries(root), existing)
            root.has("entries") -> lorebook(Kind.LOREBOOK, worldInfoEntries(root.opt("entries")), existing)
            else -> error("This file isn't a Roadleaf cast, a NovelAI lorebook or a lorebook Roadleaf can read.")
        }
    }

    fun export(cast: BookVoiceCast, learned: LearnedCast, book: String, engine: String, locale: String, appVersion: String): String =
        JSONObject().put("format", FORMAT).put("version", 1).put("exportedBy", "Roadleaf $appVersion").put("book", book.take(240))
            .put("enginePackage", engine).put("locale", locale)
            .put("enabled", cast.enabled).put("adaptive", cast.adaptive).put("followViewpoint", cast.followViewpoint).put("sceneOnly", cast.sceneOnly)
            .put("mainCharacter", cast.mainCharacter).put("mainVoice", cast.mainVoice).put("mainPronoun", cast.mainPronoun.name)
            .put("narratorVoice", cast.narratorVoice).put("maleVoice", cast.maleVoice).put("femaleVoice", cast.femaleVoice)
            .put("profileSetId", cast.profileSetId).put("chapterViewpoints", JSONObject(cast.chapterViewpoints))
            .put("characters", JSONArray().apply { cast.characters.forEach {
                put(JSONObject().put("name", it.name).put("aliases", it.aliases).put("role", it.role.name).put("voice", it.voice)
                    .put("pronoun", it.pronoun.name).put("delivery", it.delivery.name).put("startsLower", it.startsLower).put("profileId", it.profileId))
            } })
            .put("learned", JSONArray().apply {
                learned.people.filter { person -> person.voice.isNotBlank() && cast.characters.none { it.name.equals(person.name, true) } }.take(64)
                    .forEach { put(JSONObject().put("name", it.name).put("voice", it.voice).put("gender", it.gender.name)) }
            })
            .toString(2)

    private inline fun <reified T : Enum<T>> enumOr(value: String, default: T): T = enumValues<T>().firstOrNull { it.name == value } ?: default

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until minOf(array.length(), 48)).mapNotNull { array.opt(it) as? String }

    private fun readCast(root: JSONObject, existing: BookVoiceCast): Imported {
        require(root.optInt("version") == 1) { "This cast file comes from a newer Roadleaf. Update Roadleaf, then import it again." }
        val list = root.optJSONArray("characters") ?: JSONArray()
        require(list.length() <= 256) { "This cast file has too many characters." }
        val characters = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { item ->
            val name = item.optString("name").trim().take(80).ifBlank { return@mapNotNull null }
            CastCharacter(name, item.optString("aliases").take(240), enumOr(item.optString("role"), CastRole.AUTO), item.optString("voice").take(160),
                enumOr(item.optString("delivery"), CharacterDelivery.FOLLOW_STORY), item.optBoolean("startsLower"),
                pronoun = enumOr(item.optString("pronoun"), CharacterPronoun.AUTO), profileId = item.optString("profileId").take(120))
        }
        val learnedList = root.optJSONArray("learned") ?: JSONArray()
        val learned = (0 until minOf(learnedList.length(), 64)).mapNotNull { learnedList.optJSONObject(it) }.mapNotNull { item ->
            val name = item.optString("name").trim().take(80).ifBlank { return@mapNotNull null }
            val pronoun = when (enumOr(item.optString("gender"), LearnedGender.UNKNOWN)) {
                LearnedGender.FEMALE -> CharacterPronoun.SHE; LearnedGender.MALE -> CharacterPronoun.HE; else -> CharacterPronoun.AUTO
            }
            CastCharacter(name, role = CastRole.CUSTOM, voice = item.optString("voice").take(160), pronoun = pronoun)
        }
        val supplied = (characters + learned).distinctBy { it.name.lowercase(Locale.ROOT) }
        val others = existing.characters.filter { old -> supplied.none { it.name.equals(old.name, true) } }
            .map { old -> if (old.profileId.isNotBlank() && supplied.any { it.profileId == old.profileId }) old.copy(profileId = "") else old }
        val viewpoints = root.optJSONObject("chapterViewpoints")?.let { value ->
            value.keys().asSequence().take(64).associateWith { value.optString(it) }.filterValues { it.isNotBlank() && it.length <= 80 }
        }.orEmpty()
        val cast = BookVoiceCast(root.optBoolean("enabled", true), root.optString("mainCharacter").take(240), root.optString("mainVoice").take(160),
            root.optString("maleVoice").take(160), root.optString("femaleVoice").take(160), (supplied + others).take(LorebookCast.MAX_CHARACTERS),
            root.optBoolean("adaptive", true), root.optBoolean("followViewpoint", true),
            (existing.chapterViewpoints + viewpoints).entries.take(64).associate { it.key to it.value },
            enumOr(root.optString("mainPronoun"), CharacterPronoun.AUTO), narratorVoice = root.optString("narratorVoice").take(160),
            sceneOnly = root.optBoolean("sceneOnly"), profileSetId = root.optString("profileSetId").take(120).ifBlank { existing.profileSetId })
        return Imported(Kind.CAST, cast.validated(), supplied.size, supplied.size, root.optString("enginePackage"))
    }

    private fun lorebook(kind: Kind, entries: List<LorebookCast.Entry>, existing: BookVoiceCast): Imported {
        val result = LorebookCast.characters(entries)
        require(result.characters.isNotEmpty()) {
            "No characters found. Put character entries in a category named Characters, or give them Gender: or Pronouns: lines."
        }
        return Imported(kind, merge(existing, result.characters), result.characters.size, result.found)
    }

    /** Adds new characters and fills nicknames and pronouns for known ones; voices already chosen stay. */
    fun merge(existing: BookVoiceCast, imported: List<CastCharacter>): BookVoiceCast {
        fun same(a: CastCharacter, b: CastCharacter) = a.profileId.isNotBlank() && a.profileId == b.profileId || a.name.equals(b.name, true)
        val kept = existing.characters.map { current ->
            val fresh = imported.firstOrNull { same(current, it) } ?: return@map current
            current.copy(aliases = (current.aliases.split(',') + fresh.aliases.split(',')).map(String::trim).filter(String::isNotBlank)
                .distinctBy { it.lowercase(Locale.ROOT) }.joinToString(",").take(240),
                pronoun = current.pronoun.takeUnless { it == CharacterPronoun.AUTO } ?: fresh.pronoun,
                profileId = current.profileId.ifBlank { fresh.profileId.takeIf { id -> existing.characters.none { it.profileId == id } }.orEmpty() })
        }
        val added = imported.filter { fresh -> existing.characters.none { same(it, fresh) } }
        val room = (LorebookCast.MAX_CHARACTERS - kept.size).coerceAtLeast(0)
        return existing.copy(enabled = true, sceneOnly = true, characters = kept + added.take(room)).validated()
    }

    private fun novelAi(root: JSONObject): JSONObject? = when {
        root.has("lorebookVersion") && root.has("entries") -> root
        root.optJSONObject("lorebook")?.has("entries") == true -> root.getJSONObject("lorebook")
        root.optJSONObject("content")?.optJSONObject("lorebook")?.has("entries") == true -> root.getJSONObject("content").getJSONObject("lorebook")
        else -> null
    }

    private fun novelAiEntries(book: JSONObject): List<LorebookCast.Entry> {
        val categories = book.optJSONArray("categories")?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.associate { it.optString("id") to it.optString("name") }
        }.orEmpty()
        val entries = book.optJSONArray("entries") ?: JSONArray()
        require(entries.length() <= MAX_ENTRIES) { "This lorebook has too many entries." }
        return (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.map { item ->
            LorebookCast.Entry(item.optString("id"), item.optString("displayName"), strings(item.optJSONArray("keys")), item.optString("text"),
                categories[item.optString("category")].orEmpty(), item.optBoolean("enabled", true))
        }
    }

    private fun worldInfoEntries(value: Any?): List<LorebookCast.Entry> {
        val items = when (value) {
            is JSONObject -> value.keys().asSequence().mapNotNull { value.optJSONObject(it) }.toList()
            is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
            else -> emptyList()
        }
        require(items.size <= MAX_ENTRIES) { "This lorebook has too many entries." }
        return items.map { item ->
            LorebookCast.Entry("", item.optString("comment").ifBlank { item.optString("name") },
                strings(item.optJSONArray("key") ?: item.optJSONArray("keys")), item.optString("content"), item.optString("category"),
                !item.optBoolean("disable", false) && item.optBoolean("enabled", true))
        }
    }

    private fun cardEntries(root: JSONObject): List<LorebookCast.Entry> {
        val data = root.optJSONObject("data") ?: root
        val own = LorebookCast.Entry("", data.optString("name"), emptyList(),
            listOf("description", "personality", "scenario").joinToString("\n") { data.optString(it) }, "Characters")
        return listOf(own) + worldInfoEntries(data.optJSONObject("character_book")?.opt("entries"))
    }
}
