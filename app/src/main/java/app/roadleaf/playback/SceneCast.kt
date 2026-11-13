package app.roadleaf.playback

import java.util.Locale
import java.io.Reader

/** Explicit scene breaks only. Character presence is local name/alias evidence, not semantic presence. */
object SceneCast {
    val breaks = Regex("(?im)^[ \\t]*(?:\\*(?:[ \\t]*\\*){2,}|-{3,}|_{3,}|⁂|#{3,}|\\[Scene[^\\]\\n]{0,1600}\\]|(?:Scene|Chapter)[ \\t]+(?:[0-9]+|[IVXLCDM]+)[^\\n]{0,120})[ \\t]*(?:\\n|$)")
    private var cachedAliases = emptyList<Pair<String, String>>()
    private var cachedMatcher: Regex? = null
    @Synchronized private fun names(cast: BookVoiceCast, text: String): Set<String> {
        val aliases = cast.characters.flatMap { person -> (person.name + "," + person.aliases).split(',').map(String::trim).filter(String::isNotBlank).map { it.lowercase(Locale.ROOT) to person.name.lowercase(Locale.ROOT) } } +
            cast.mainCharacter.split(',').map(String::trim).filter(String::isNotBlank).map { it.lowercase(Locale.ROOT) to "@main" }
        if (cachedAliases != aliases) {
            cachedAliases = aliases
            cachedMatcher = aliases.map { it.first }.distinct().sortedByDescending(String::length).takeIf { it.isNotEmpty() }?.let { keys ->
                Regex("(?<![\\p{L}\\p{N}_])(?:${keys.joinToString("|") { Regex.escape(it) }})(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
            }
        }
        val index = aliases.groupBy({ it.first }, { it.second })
        return cachedMatcher?.findAll(text)?.flatMap { index[it.value.lowercase(Locale.ROOT)].orEmpty().asSequence() }?.toSet().orEmpty()
    }
    fun before(cast: BookVoiceCast, reader: Reader, offset: Int): DialogueState {
        var state = DialogueState()
        SpeechChunkReader(reader, paragraphChunks = true).use { chunks ->
            while (true) {
                val part = chunks.next() ?: break
                if (part.offset >= offset) break
                val text = part.text.take(offset - part.offset)
                var at = 0
                for (boundary in breaks.findAll(text)) {
                    state = DialogueState(sceneNames = names(prepared, boundary.value), sceneExplicit = explicit(boundary.value))
                    at = boundary.range.last + 1
                }
                if (!state.sceneExplicit) state = state.copy(sceneNames = (state.sceneNames + names(cast, text.substring(at))).take(65).toSet())
            }
        }
        return state
    }
    private fun explicit(value: String) = Regex("(?i)\\[Scene(?:[ \\t]+cast)?[ \\t]*:").containsMatchIn(value)
    fun plan(text: String, cast: BookVoiceCast, previous: DialogueState, lookahead: String = ""): CastPlan {
        val prepared = SceneReadAhead.prepare(cast, text, previous, lookahead).cast
        if (!prepared.sceneOnly) return VoiceCastPlanner(prepared).plan(text, previous, lookahead)
        var state = previous
        val utterances = mutableListOf<CastUtterance>()
        fun section(value: String, ahead: String = "") {
            val explicit = state.sceneExplicit
            val active = (state.sceneNames + if (explicit) emptySet() else names(prepared, value + ahead)).take(65).toSet()
            val people = prepared.characters.filter { it.name.lowercase(Locale.ROOT) in active }
            val inactiveVoices = prepared.characters.filter { it !in people }.map { it.voice }.filter(String::isNotBlank).toSet() +
                if ("@main" !in active && prepared.mainVoice.isNotBlank()) setOf(prepared.mainVoice) else emptySet()
            val scoped = prepared.copy(sceneOnly = false, characters = people,
                mainCharacter = prepared.mainCharacter.takeIf { "@main" in active }.orEmpty(), mainVoice = prepared.mainVoice.takeIf { "@main" in active }.orEmpty(),
                storyVoice = prepared.storyVoice.takeUnless { it in inactiveVoices }.orEmpty())
            val result = VoiceCastPlanner(scoped).plan(value, state, ahead)
            utterances.addAll(result.utterances); state = result.next.copy(sceneNames = active, sceneExplicit = explicit)
        }
        var at = 0
        for (boundary in breaks.findAll(text)) {
            section(text.substring(at, boundary.range.first))
            state = DialogueState(sceneNames = names(cast, boundary.value), sceneExplicit = explicit(boundary.value))
            at = boundary.range.last + 1
        }
        section(text.substring(at), lookahead.substring(0, breaks.find(lookahead)?.range?.first ?: lookahead.length))
        return CastPlan(utterances, state)
    }
}
