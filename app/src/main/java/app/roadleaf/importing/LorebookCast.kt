package app.roadleaf.importing

import app.roadleaf.playback.CastCharacter
import app.roadleaf.playback.CastRole
import app.roadleaf.playback.CharacterPronoun
import java.util.Locale

/**
 * Turns lorebook entries into cast characters. Only a character's name, nicknames and a pronoun hint
 * are kept; the entry text is read to recognise characters and is then discarded.
 */
object LorebookCast {
    const val MAX_CHARACTERS = 64

    /** One lorebook entry in a format-neutral shape. [text] is used only while importing. */
    data class Entry(val id: String = "", val name: String = "", val keys: List<String> = emptyList(), val text: String = "",
        val category: String = "", val enabled: Boolean = true)

    data class Result(val characters: List<CastCharacter>, val found: Int)

    private val characterCategory = Regex("character|people|person|\\bcast\\b|\\bnpc|protagonist|antagonist|companion|\\bparty\\b|\\bcrew\\b|\\bmembers?\\b", RegexOption.IGNORE_CASE)
    private val otherCategory = Regex("place|location|world|item|object|faction|organi[sz]ation|\\blore\\b|event|history|concept|magic|species|race|setting|city|cities|planet|ship|weapon|style|rule|instruction|author", RegexOption.IGNORE_CASE)
    private val profileField = Regex("(?i)(?:^|[\\[;\\n{(]|\\s)\\s*(gender|sex|pronouns?|age|species|race|appearance|personality|occupation|height|hair|eyes|likes|dislikes)\\s*[:=]")
    private val preset = Regex("^\\d+[ \\t]+.+[—–-][ \\t]*Preset$")
    private val genderField = Regex("(?i)\\b(gender|sex)\\s*[:=]\\s*\"?([a-z][a-z-]*)")
    private val pronounField = Regex("(?i)\\bpronouns?\\s*[:=]\\s*\"?(she|he|they)\\b")
    private val she = Regex("(?i)\\b(she|her|hers|herself)\\b")
    private val he = Regex("(?i)\\b(he|him|his|himself)\\b")

    /** A pronoun hint from labelled fields first, then from clearly dominant pronouns in the text. */
    fun pronoun(text: String): CharacterPronoun {
        genderField.find(text)?.groupValues?.get(2)?.lowercase(Locale.ROOT)?.let { value ->
            when (value) {
                "female", "woman", "girl", "f", "feminine", "lady" -> return CharacterPronoun.SHE
                "male", "man", "boy", "m", "masculine" -> return CharacterPronoun.HE
                "nonbinary", "non-binary", "enby", "agender", "genderfluid", "neutral" -> return CharacterPronoun.THEY
            }
        }
        pronounField.find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT)?.let { value ->
            return when (value) { "she" -> CharacterPronoun.SHE; "he" -> CharacterPronoun.HE; else -> CharacterPronoun.THEY }
        }
        val female = she.findAll(text).count()
        val male = he.findAll(text).count()
        return when {
            female >= 3 && female >= male * 2 -> CharacterPronoun.SHE
            male >= 3 && male >= female * 2 -> CharacterPronoun.HE
            else -> CharacterPronoun.AUTO
        }
    }

    private fun usableKey(key: String) = key.isNotBlank() && !key.startsWith('/') && !key.contains('&') && !key.startsWith("char_") && key.trim().length <= 80

    private fun looksLikeName(value: String): Boolean {
        val words = value.trim().split(Regex("\\s+"))
        return words.size in 1..4 && words.all { word -> word.first().isUpperCase() && word.all { it.isLetter() || it == '\'' || it == '’' || it == '-' || it == '.' } }
    }

    /** Whether an entry describes a person rather than a place, item or rule. */
    fun isCharacter(entry: Entry): Boolean {
        if (!entry.enabled) return false
        if (characterCategory.containsMatchIn(entry.category)) return true
        if (entry.category.isNotBlank() && otherCategory.containsMatchIn(entry.category)) return false
        if (entry.id.startsWith("char_") || preset.matches(entry.name.trim())) return true
        val fields = profileField.findAll(entry.text).map { it.groupValues[1].lowercase(Locale.ROOT) }.toSet()
        val female = she.findAll(entry.text).count()
        val male = he.findAll(entry.text).count()
        val dominant = female >= 4 && female >= male * 2 || male >= 4 && male >= female * 2
        val named = looksLikeName(name(entry).orEmpty())
        return named && (fields.size >= 2 || fields.any { it == "gender" || it == "sex" || it.startsWith("pronoun") } || dominant)
    }

    /** The character's display name, without numbering, "— Preset" or bracketed notes; the first key otherwise. */
    fun name(entry: Entry): String? {
        val display = entry.name.replace(Regex("\\s*[\\[(][^\\])]*[\\])]\\s*"), " ").replace(Regex("^\\d+[ \\t]+"), "")
            .replace(Regex("[ \\t]*[—–-][ \\t]*Preset$"), "").replace(Regex("\\s+"), " ").trim()
        return display.takeIf { it.isNotBlank() && it.length <= 80 } ?: entry.keys.firstOrNull(::usableKey)?.trim()
    }

    fun characters(entries: List<Entry>, limit: Int = MAX_CHARACTERS): Result {
        val result = linkedMapOf<String, CastCharacter>()
        var found = 0
        for (entry in entries) {
            if (!isCharacter(entry)) continue
            val name = name(entry) ?: continue
            val key = name.lowercase(Locale.ROOT)
            val aliases = entry.keys.filter(::usableKey).map(String::trim).filterNot { it.equals(name, true) }
            val existing = result[key]
            if (existing != null) {
                val merged = (existing.aliases.split(',') + aliases).map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }
                result[key] = existing.copy(aliases = merged.joinToString(",").take(240),
                    pronoun = existing.pronoun.takeUnless { it == CharacterPronoun.AUTO } ?: pronoun(entry.text))
                continue
            }
            found++
            if (result.size >= limit) continue
            result[key] = CastCharacter(name, aliases.distinctBy { it.lowercase(Locale.ROOT) }.joinToString(",").take(240), CastRole.AUTO,
                pronoun = pronoun(entry.text), profileId = entry.id.take(120))
        }
        return Result(result.values.toList(), found)
    }
}
