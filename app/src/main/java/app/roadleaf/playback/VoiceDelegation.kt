package app.roadleaf.playback

import java.util.Locale

/**
 * Gives imported characters voices from the speech engine installed on this phone. Voices a file
 * names but the phone lacks are replaced; characters share a voice only when no unused one is left.
 */
object VoiceDelegation {
    enum class Gender { FEMALE, MALE, UNKNOWN }

    data class Result(
        val cast: BookVoiceCast,
        /** Characters (and the main character) given a voice by this pass. */
        val assigned: Int,
        /** Characters whose voice is also used by another character or the narrator. */
        val shared: List<String>,
        /** Voice IDs the file named that are not installed for this engine and accent. */
        val missing: List<String>,
        /** True when female/male characters were matched without any gender labels on the voices. */
        val unlabelled: Boolean,
        /** Installed voices available for characters, not counting the narrator. */
        val available: Int
    ) {
        val needsMoreVoices get() = shared.isNotEmpty() || available == 0
    }

    private val female = Regex("(^|[^a-z])(female|woman|girl)([^a-z]|$)")
    private val male = Regex("(^|[^a-z])(male|man|boy)([^a-z]|$)")

    /** Gender only where the engine labels it in the voice ID; never guessed from how a name sounds. */
    fun gender(voice: String): Gender {
        val id = voice.lowercase(Locale.ROOT)
        return when {
            Regex("en-supertonic-f\\d+").matches(id) -> Gender.FEMALE
            Regex("en-supertonic-m\\d+").matches(id) -> Gender.MALE
            female.containsMatchIn(id) -> Gender.FEMALE
            male.containsMatchIn(id) -> Gender.MALE
            else -> Gender.UNKNOWN
        }
    }

    private fun want(pronoun: CharacterPronoun) = when (pronoun) {
        CharacterPronoun.SHE -> Gender.FEMALE
        CharacterPronoun.HE -> Gender.MALE
        else -> Gender.UNKNOWN
    }

    fun assign(cast: BookVoiceCast, installed: List<String>, narrator: String): Result {
        val voices = installed.filter(String::isNotBlank).distinct()
        val usable = voices.toSet()
        val pool = voices.filter { it != narrator }
        val uses = mutableMapOf<String, Int>()
        if (narrator.isNotBlank()) uses[narrator] = 1
        val missing = linkedSetOf<String>()
        val shared = mutableListOf<String>()
        var assigned = 0
        var unlabelled = false
        // Keep every installed voice that is already chosen, so re-importing never reshuffles them.
        if (cast.mainVoice in usable) uses.merge(cast.mainVoice, 1, Int::plus)
        cast.characters.filter { it.role == CastRole.CUSTOM && it.voice in usable }.forEach { uses.merge(it.voice, 1, Int::plus) }

        fun take(wanted: Gender): String? {
            if (pool.isEmpty()) return null
            val unused = pool.filter { (uses[it] ?: 0) == 0 }
            val leastUsed = pool.sortedBy { uses[it] ?: 0 }
            val choice = when (wanted) {
                Gender.UNKNOWN -> unused.firstOrNull { gender(it) == Gender.UNKNOWN } ?: unused.firstOrNull() ?: leastUsed.first()
                else -> unused.firstOrNull { gender(it) == wanted } ?: unused.firstOrNull { gender(it) == Gender.UNKNOWN }
                    ?: leastUsed.firstOrNull { gender(it) == wanted } ?: leastUsed.firstOrNull { gender(it) == Gender.UNKNOWN } ?: leastUsed.first()
            }
            if (wanted != Gender.UNKNOWN && gender(choice) == Gender.UNKNOWN) unlabelled = true
            uses.merge(choice, 1, Int::plus)
            return choice
        }

        var mainVoice = cast.mainVoice
        if (cast.mainCharacter.isNotBlank() && mainVoice !in usable) {
            if (mainVoice.isNotBlank()) missing += mainVoice
            take(want(cast.mainPronoun))?.let { mainVoice = it; assigned++ }
        }
        val characters = cast.characters.map { character ->
            when {
                character.role == CastRole.NARRATOR -> character
                character.role == CastRole.CUSTOM && character.voice in usable -> character
                else -> {
                    if (character.role == CastRole.CUSTOM && character.voice.isNotBlank()) missing += character.voice
                    val voice = take(want(character.pronoun)) ?: return@map character
                    assigned++
                    character.copy(role = CastRole.CUSTOM, voice = voice)
                }
            }
        }
        // Dialogue defaults for speakers who are not in the cast, where the engine labels genders.
        fun default(current: String, wanted: Gender) = current.takeIf { it in usable }
            ?: pool.filter { gender(it) == wanted }.minByOrNull { uses[it] ?: 0 }.orEmpty()
        val result = cast.copy(mainVoice = mainVoice, characters = characters,
            femaleVoice = default(cast.femaleVoice, Gender.FEMALE), maleVoice = default(cast.maleVoice, Gender.MALE))
        val owners = (characters.filter { it.role == CastRole.CUSTOM }.map { it.name to it.voice } +
            listOfNotNull(cast.mainCharacter.substringBefore(',').trim().takeIf { it.isNotBlank() && mainVoice.isNotBlank() }?.let { it to mainVoice }))
        val counts = owners.groupingBy { it.second }.eachCount()
        owners.filter { (counts[it.second] ?: 0) > 1 || it.second == narrator && narrator.isNotBlank() }.forEach { shared += it.first }
        return Result(result, assigned, shared.distinct(), missing.toList(), unlabelled, pool.size)
    }
}
