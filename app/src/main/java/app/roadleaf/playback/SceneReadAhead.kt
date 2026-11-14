package app.roadleaf.playback

/**
 * Offline, book-local narration planning. Reads the current speech span plus a bounded preview
 * of the next span. Does not change story text, Lorebooks, or the book's saved voice assignments.
 *
 * A name appearing in dialogue or an ordinary mention cannot alone move the narrator.
 * Confirmed thoughts, direct sensory experience, or explicit POV markers can do so.
 */
data class SceneNarratorChoice(val cast: BookVoiceCast, val viewpoint: String)

object SceneReadAhead {
    private const val THINK = "(?:thought|wondered|realized|realised|felt|noticed|remembered|feared|hoped|decided|imagined|considered)"
    private val povHeader = Regex("""(?im)^\s*\[POV\s*:\s*([^\]\n]+)\]""")
    private val focalAction = "(?:walked|stepped|crossed|entered|left|turned|stopped|paused|stood|sat|knelt|moved|reached|opened|closed|looked|watched|listened|waited|searched|followed|approached|returned|ran|rushed|held|touched|pulled|pushed|took|placed|lifted|lowered|shook|smiled|frowned|sighed)"

    private fun aliases(person: CastCharacter): List<String> =
        (listOf(person.name) + person.aliases.split(',')).map(String::trim).filter { it.length >= 2 }

    private fun names(cast: BookVoiceCast): List<Pair<String, List<String>>> {
        val main = cast.mainCharacter.split(',').map(String::trim).filter { it.length >= 2 }
        return (listOfNotNull(main.firstOrNull()?.let { it to main }) +
            cast.characters.map { it.name to aliases(it) }).distinctBy { it.first.lowercase() }
    }

    private fun nameIn(header: String, choices: List<Pair<String, List<String>>>): String? =
        choices.firstOrNull { (_, labels) -> labels.any { it.equals(header.trim(), true) } }?.first

    private fun voice(cast: BookVoiceCast, name: String): String {
        if (cast.mainCharacter.split(',').any { it.trim().equals(name, true) }) return cast.mainVoice
        val person = cast.characters.firstOrNull {
            it.name.equals(name, true) || aliases(it).any { alias -> alias.equals(name, true) }
        } ?: return ""
        return when (person.role) {
            CastRole.CUSTOM, CastRole.AUTO -> person.voice
            CastRole.MALE -> cast.maleVoice
            CastRole.FEMALE -> cast.femaleVoice
            CastRole.NARRATOR -> cast.narratorVoice
        }
    }

    fun prepare(cast: BookVoiceCast, text: String, previous: DialogueState, lookahead: String = ""): SceneNarratorChoice {
        if (!cast.enabled || !cast.followViewpoint) return SceneNarratorChoice(cast, "")
        val people = names(cast)
        if (people.isEmpty()) return SceneNarratorChoice(cast, "")
        val explicit = povHeader.find(text)?.groupValues?.get(1)?.let { nameIn(it, people) }

        // Quoted speech is not evidence of who is narrating the scene.
        val here = DialogueQuotes.mask(text, previous.closingQuote)
        val ahead = DialogueQuotes.mask(lookahead.take(450), previous.closingQuote.takeIf { text.isEmpty() })
        val scores = people.map { (name, labels) ->
            val score = labels.distinct().sumOf { label ->
                val token = "(?<![\\p{L}\\p{N}_])" + Regex.escape(label) + "(?![\\p{L}\\p{N}_])"
                val thoughts = Regex(token + "\\s+(?:quietly\\s+)?" + THINK, RegexOption.IGNORE_CASE)
                    .findAll(here).count()
                val sensory = Regex("(?:through|from|inside)\\s+" + token +
                    "(?:['’]s)?\\s+(?:eyes|mind|perspective|thoughts)\\b", RegexOption.IGNORE_CASE)
                    .findAll(here).count()
                val internal = Regex(token + "(?:['’]s)?\\s+(?:heart|mind|pulse|fear)\\b", RegexOption.IGNORE_CASE)
                    .findAll(here).count()
                val future = Regex(token + "\\s+" + THINK, RegexOption.IGNORE_CASE).findAll(ahead).count()
                // Repeated grammatical subjects can establish a third-person focal
                // character; a single mention or quoted dialogue cannot.
                val actions = Regex("(?:^|[.!?]\\s+|\\n)\\s*" + token + "\\s+" + focalAction + "\\b", RegexOption.IGNORE_CASE)
                    .findAll(here).count()
                thoughts * 3.0 + sensory * 3.0 + internal * 1.0 + future * 0.8 + actions * 1.4
            }
            name to score
        }.sortedByDescending { it.second }
        val winner = scores.firstOrNull()
        val runner = scores.getOrNull(1)?.second ?: 0.0
        val inferred = winner?.takeIf { it.second >= 2.5 && it.second >= runner + 1.4 }?.first
        // Once a scene has a confirmed focus, second-person references can keep that
        // voice. "You" alone never identifies a character or moves the narrator.
        val selected = explicit ?: inferred ?: previous.viewpoint.takeIf { old ->
            people.any { (name, labels) -> name.equals(old, true) || labels.any { it.equals(old, true) } }
        }.orEmpty()
        if (selected.isBlank()) return SceneNarratorChoice(cast, "")
        val chosenVoice = voice(cast, selected).ifBlank { return SceneNarratorChoice(cast, selected) }
        return SceneNarratorChoice(cast.copy(storyVoice = chosenVoice), selected)
    }
}
