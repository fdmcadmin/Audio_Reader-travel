package app.roadleaf.playback

import java.util.Locale
import java.io.Reader

/** Explicit scene breaks only. Character presence is local name/alias evidence, not semantic presence. */
object SceneCast {
    val breaks = Regex("(?im)^[ \\t]*(?:\\*(?:[ \\t]*\\*){2,}|-{3,}|_{3,}|⁂|#{3,}|\\[Scene[^\\]\\n]{0,1600}\\]|(?:Scene|Chapter)[ \\t]+(?:[0-9]+|[IVXLCDM]+)[^\\n]{0,120})[ \\t]*(?:\\n|$)")
    private val thoughtTags = Regex("(?m)^[ \\t]*💭[ \\t]*([^\\n:]{1,80})['’]s[ \\t]+Thoughts[ \\t]*:", RegexOption.IGNORE_CASE)
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
                    state = DialogueState(sceneNames = names(cast, boundary.value), sceneExplicit = explicit(boundary.value))
                    at = boundary.range.last + 1
                }
                val tail = text.substring(at)
                if (!state.sceneExplicit) state = state.copy(sceneNames = (state.sceneNames + names(cast, tail)).take(65).toSet())
                thoughtTags.findAll(tail).lastOrNull()?.let { tag ->
                    viewpoint(cast, tag.groupValues[1]).takeIf(String::isNotBlank)?.let { state = state.copy(sceneViewpoint = it) }
                }
            }
        }
        return state
    }
    private fun explicit(value: String) = Regex("(?i)\\[Scene(?:[ \\t]+cast)?[ \\t]*:").containsMatchIn(value)
    private fun viewpoint(cast: BookVoiceCast, label: String): String = names(cast, label).firstOrNull().orEmpty()
    private fun viewpointVoice(cast: BookVoiceCast, viewpoint: String): String = when (viewpoint) {
        "@main" -> cast.mainVoice
        else -> cast.characters.firstOrNull { it.name.equals(viewpoint, true) }?.let { person -> when (person.role) {
            CastRole.CUSTOM, CastRole.AUTO -> person.voice
            CastRole.MALE -> cast.maleVoice
            CastRole.FEMALE -> cast.femaleVoice
            CastRole.NARRATOR -> cast.narratorVoice
        } }.orEmpty()
    }
    fun plan(text: String, cast: BookVoiceCast, previous: DialogueState, lookahead: String = ""): CastPlan {
        if (!cast.sceneOnly) return VoiceCastPlanner(cast).plan(text, previous, lookahead)
        var state = previous
        val utterances = mutableListOf<CastUtterance>()
        fun renderSection(value: String, ahead: String = "") {
            val explicit = state.sceneExplicit
            val active = (state.sceneNames + if (explicit) emptySet() else names(cast, value + ahead)).take(65).toSet()
            val people = cast.characters.filter { it.name.lowercase(Locale.ROOT) in active }
            val inactiveVoices = cast.characters.filter { it !in people }.map { it.voice }.filter(String::isNotBlank).toSet() +
                if ("@main" !in active && cast.mainVoice.isNotBlank()) setOf(cast.mainVoice) else emptySet()
            val storyVoice = viewpointVoice(cast, state.sceneViewpoint).ifBlank { cast.storyVoice.takeUnless { it in inactiveVoices }.orEmpty() }
            val scoped = cast.copy(sceneOnly = false, characters = people,
                mainCharacter = cast.mainCharacter.takeIf { "@main" in active }.orEmpty(), mainVoice = cast.mainVoice.takeIf { "@main" in active }.orEmpty(),
                storyVoice = storyVoice)
            val result = VoiceCastPlanner(scoped).plan(value, state, ahead)
            utterances.addAll(result.utterances); state = result.next.copy(sceneNames = active, sceneExplicit = explicit, sceneViewpoint = state.sceneViewpoint)
        }
        fun section(value: String, ahead: String = "") {
            var innerAt = 0
            for (tag in thoughtTags.findAll(value)) {
                if (tag.range.first > innerAt) renderSection(value.substring(innerAt, tag.range.first))
                viewpoint(cast, tag.groupValues[1]).takeIf(String::isNotBlank)?.let { state = state.copy(sceneViewpoint = it) }
                innerAt = tag.range.first
            }
            renderSection(value.substring(innerAt), ahead)
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
