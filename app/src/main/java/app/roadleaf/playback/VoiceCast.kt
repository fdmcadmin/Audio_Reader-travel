package app.roadleaf.playback

import java.util.Locale

enum class CastRole(val label: String) { MALE("Male voice"), FEMALE("Female voice"), CUSTOM("Dedicated voice"), NARRATOR("Narrator"), AUTO("Learn from story") }
enum class CharacterPronoun(val label: String, val subject: String) { AUTO("Learn from text", ""), HE("He / him", "he"), SHE("She / her", "she"), THEY("They / them", "they") }
data class CastCharacter(val name: String, val aliases: String = "", val role: CastRole = CastRole.CUSTOM, val voice: String = "",
    val delivery: CharacterDelivery = CharacterDelivery.FOLLOW_STORY, val startsLower: Boolean = false, val learnedLower: Boolean? = null,
    val pronoun: CharacterPronoun = CharacterPronoun.AUTO, val profileId: String = "")
data class BookVoiceCast(
    val enabled: Boolean = true, val mainCharacter: String = "", val mainVoice: String = "",
    val maleVoice: String = "", val femaleVoice: String = "", val characters: List<CastCharacter> = emptyList(),
    val adaptive: Boolean = true, val followViewpoint: Boolean = true, val chapterViewpoints: Map<String, String> = emptyMap(),
    val mainPronoun: CharacterPronoun = CharacterPronoun.AUTO, val storyVoice: String = "", val narratorVoice: String = "", val sceneOnly: Boolean = false, val profileSetId: String = ""
) {
    fun validated(): BookVoiceCast {
        require(mainCharacter.length <= 240 && characters.size <= 64) { "Keep the cast to 64 characters and short names/aliases." }
        characters.forEach { require(it.name.isNotBlank() && it.name.length <= 80 && it.aliases.length <= 240) { "Enter a short character name and aliases." } }
        require(characters.map { it.name.trim().lowercase(Locale.ROOT) }.distinct().size == characters.size) { "Each character needs a unique name." }
        require(profileSetId.length <= 120 && characters.all { it.profileId.length <= 120 }) { "Keep profile identifiers short." }
        require(characters.map { it.profileId }.filter(String::isNotBlank).let { it.distinct().size == it.size }) { "Character profile IDs must be unique." }
        require(chapterViewpoints.size <= 64 && chapterViewpoints.all { (key, name) -> key.isNotBlank() && key.length <= 2048 && name.isNotBlank() && name.length <= 80 }) { "Keep chapter narrator choices to 64 short entries." }
        return copy(mainCharacter = mainCharacter.trim(), characters = characters.map { it.copy(name = it.name.trim(), aliases = it.aliases.trim()) })
    }
}
data class DialogueState(val closingQuote: Char? = null, val actor: String = "narrator", val lead: String = "", val pitchScale: Float = 1f,
    val narrativeLead: String = "", val turnTail: String = "", val sceneNames: Set<String> = emptySet(), val sceneExplicit: Boolean = false)
data class CastUtterance(val text: String, val voice: String = "", val pitchScale: Float = 1f)
data class CastPlan(val utterances: List<CastUtterance>, val next: DialogueState)

/** Bounded local attribution. Names and explicit he/she tags are evidence; unclear lines use narration. */
class VoiceCastPlanner(private val cast: BookVoiceCast) {
    private val verbs = DeliveryCues.VERBS
    private val actors = buildMap {
        fun aliases(value: String, actor: String) { value.split(',').map(String::trim).filter(String::isNotEmpty).forEach { put(it.lowercase(Locale.ROOT), actor) } }
        cast.characters.forEach { aliases(it.name + "," + it.aliases, "name:${it.name}") }
        aliases(cast.mainCharacter, "main") // The MC's fixed assignment takes priority over generic roles.
    }
    private val names = actors.keys.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
    private val afterName = names.takeIf(String::isNotBlank)?.let { Regex("^\\s*[,—–-]?\\s*($it)\\s+(?:\\p{L}+\\s+){0,2}(?:$verbs)\\b", RegexOption.IGNORE_CASE) }
    private val beforeName = names.takeIf(String::isNotBlank)?.let { Regex("(?<![\\p{L}\\p{N}_])($it)\\s+(?:\\p{L}+\\s+){0,2}(?:$verbs)\\b[^\"“”‘’'\\n.!?]{0,80}$", RegexOption.IGNORE_CASE) }
    private val afterPronoun = Regex("^\\s*[,—–-]?\\s*(he|she|they)\\s+(?:\\p{L}+\\s+){0,2}(?:$verbs)\\b", RegexOption.IGNORE_CASE)
    private val beforePronoun = Regex("\\b(he|she|they)\\s+(?:\\p{L}+\\s+){0,2}(?:$verbs)\\b[^\"“”‘’'\\n.!?]{0,80}$", RegexOption.IGNORE_CASE)
    private val reversed = names.takeIf(String::isNotBlank)?.let { Regex("^\\s*[,—–-]?\\s*(?:$verbs)\\s+($it)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE) }
    private val beats = "(?:was\\s+(?:direct|blunt)|cut\\b[^\"“”\\n.!?]{0,30}\\boff|narrowed\\s+(?:his|her|their)\\s+eyes|pointed\\s+out|began\\b|noticed\\b|announced\\b)"
    private val expressive = names.takeIf(String::isNotBlank)?.let { Regex("^\\s*[,—–-]?\\s*($it)(?:\\s+$beats|['’]s\\s+(?:voice\\s+came|eyes\\s+showed))", RegexOption.IGNORE_CASE) }
    private val vocalDescription = names.takeIf(String::isNotBlank)?.let { Regex("^\\s*[,—–-]?\\s*($it)\\s+[^\"“”\\n.!?]{0,70}\\b(?:his|her|their)\\s+(?:voice|tone)\\b", RegexOption.IGNORE_CASE) }
    private val revelation = names.takeIf(String::isNotBlank)?.let { Regex("^\\s*\\p{Lu}\\p{L}*\\s+(?:turned|looked)\\s+(?:back\\s+|around\\s+)?to\\s+(?:find|see)\\s+($it)\\s+(?:standing|waiting|beside|behind|at)\\b", RegexOption.IGNORE_CASE) }
    private val actionSubject = names.takeIf(String::isNotBlank)?.let { Regex("^\\s*[,—–-]?\\s*(?:Then\\s+|Suddenly,?\\s+)?($it)(?![\\p{L}\\p{N}_])(?:\\s+|['’]s\\s+)", RegexOption.IGNORE_CASE) }
    private val actionPronoun = Regex("^\\s*[,—–-]?\\s*(he|she|they)\\s+(?:(?:gently|softly|quietly|slowly)\\s+)?(?:winked|nodded|leaned|pinched|clicked|shook|arched|made|pulled|pushed|ruffled|smiled|sighed|looked|pointed|spread|decided|panted)\\b", RegexOption.IGNORE_CASE)
    private val questionIntroduction = names.takeIf(String::isNotBlank)?.let { Regex("(?<![\\p{L}\\p{N}_])($it)\\s+(?:asked|inquired|wondered|wanted\\s+to\\s+know|[^\\n.!?:;]{0,96}\\bquestions)\\s*:\\s*", RegexOption.IGNORE_CASE) }
    private val directQuestion = Regex("^(?:how|what|when|where|why|which|who|can|could|would|should|is|are|was|were|did|do|does|have|has|had)\\b[^.!?\\n]{0,300}\\?", RegexOption.IGNORE_CASE)
    private fun pairedActionPronoun(text: String): String? {
        val next = text.indices.firstOrNull { DialogueQuotes.opening(text, it) != null } ?: return null
        val gap = text.substring(0, next)
        return if (gap.length <= 180 && !Regex("\\n\\s*\\n").containsMatchIn(gap)) actionPronoun.find(gap)?.groupValues?.get(1) else null
    }
    private fun post(regex: Regex?, text: String): MatchResult? = regex?.find(text)?.takeUnless {
        val tail = text.substring(it.range.last + 1).trimStart(' ', '\n', '\t', ',', ':', ';', '—', '–', '-')
        tail.isNotEmpty() && DialogueQuotes.opening(tail, 0) != null
    }
    private fun namedAfter(text: String): String? = sequenceOf(post(afterName, text), post(reversed, text), post(expressive, text), post(vocalDescription, text), revelation?.find(text))
        .filterNotNull().firstOrNull()?.groupValues?.get(1)?.let { actors[it.lowercase(Locale.ROOT)] }
    private fun actionBetweenQuotes(text: String): String? {
        val next = text.indices.firstOrNull { DialogueQuotes.opening(text, it) != null } ?: return null
        val gap = text.substring(0, next)
        if (gap.length > 180 || Regex("\\n\\s*\\n").containsMatchIn(gap)) return null
        // A bare speech introduction belongs to the following quote, not the previous one.
        if (sequenceOf(afterName, reversed, afterPronoun).filterNotNull().any { regex ->
            regex.find(gap)?.let { gap.substring(it.range.last + 1).trim(' ', '\n', '\t', ',', ':', ';', '—', '–', '-').isEmpty() } == true
        }) return null
        return actionSubject?.find(gap)?.groupValues?.get(1)?.let { actors[it.lowercase(Locale.ROOT)] }
    }
    private fun actionBefore(text: String): String? {
        val paragraph = text.substringAfterLast("\n\n").trim().trimEnd('.', '!', '?')
        val sentence = paragraph.split(Regex("[.!?]\\s+")).lastOrNull().orEmpty()
        if (sentence.length > 180 || sentence.any { it in "\"“”‘’'" && !it.isLetter() }) return null
        return actionSubject?.find(sentence)?.groupValues?.get(1)?.let { actors[it.lowercase(Locale.ROOT)] }
    }
    private fun pronounActor(pronoun: String, narrative: String, fallback: String): String {
        val wanted = pronoun.lowercase(Locale.ROOT)
        val eligible = buildSet {
            if (cast.mainPronoun.subject == wanted) add("main")
            cast.characters.filter { it.pronoun.subject == wanted || (it.pronoun == CharacterPronoun.AUTO &&
                ((it.role == CastRole.FEMALE && wanted == "she") || (it.role == CastRole.MALE && wanted == "he"))) }.forEach { add("name:${it.name}") }
        }
        val activeAliases = actors.filterValues { it in eligible }
        fun mentionedIn(text: String) = activeAliases.filterKeys {
            Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(it)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }.values.distinct()
        val scene = narrative.takeLast(720)
        // An explicit recent subject beats older people in the scene; two possible people
        // in that same statement remain ambiguous.
        for (sentence in scene.split(Regex("[.!?\\n]+\\s*")).filter(String::isNotBlank).takeLast(3).asReversed()) {
            val local = activeAliases.filterKeys { Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(it)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE).containsMatchIn(sentence) }.values.distinct()
            if (local.size > 1) break
            val subject = actionSubject?.find(sentence)?.groupValues?.get(1)?.let { actors[it.lowercase(Locale.ROOT)] }
            if (subject in eligible && local.size == 1) return subject!!
        }
        val mentioned = mentionedIn(scene)
        if (mentioned.size == 1) return mentioned.single()
        return when (wanted) { "he" -> "male"; "she" -> "female"; else -> fallback }
    }
    private fun actor(before: String, after: String, narrative: String, gap: String, fallback: String): String {
        namedAfter(after)?.let { return it }
        actionBetweenQuotes(after)?.let { return it }
        beforeName?.findAll(before.takeLast(180))?.lastOrNull()?.groupValues?.get(1)?.let { return actors[it.lowercase(Locale.ROOT)] ?: fallback }
        post(afterPronoun, after)?.groupValues?.get(1)?.let { return pronounActor(it, narrative, fallback) }
        pairedActionPronoun(after)?.let { return pronounActor(it, narrative, fallback) }
        beforePronoun.findAll(before.takeLast(180)).lastOrNull()?.groupValues?.get(1)?.let { return pronounActor(it, narrative, fallback) }
        if (gap.length <= 240 && !Regex("\\n\\s*\\n").containsMatchIn(gap)) namedAfter(gap)?.let { return it }
        actionBefore(gap)?.let { return it }
        if (gap.length <= 240 && !Regex("\\n\\s*\\n").containsMatchIn(gap)) actionPronoun.find(gap)?.groupValues?.get(1)?.let { return pronounActor(it, narrative, fallback) }
        return fallback
    }
    private fun voice(actor: String): String = when (actor) {
        "story", "narrator" -> cast.storyVoice.ifBlank { cast.narratorVoice }; "main" -> cast.mainVoice; "male" -> cast.maleVoice; "female" -> cast.femaleVoice
        else -> cast.characters.firstOrNull { "name:${it.name}" == actor }?.let {
            when (it.role) { CastRole.MALE -> cast.maleVoice; CastRole.FEMALE -> cast.femaleVoice; CastRole.CUSTOM, CastRole.AUTO -> it.voice; CastRole.NARRATOR -> cast.storyVoice.ifBlank { cast.narratorVoice } }
        }.orEmpty()
    }.ifBlank { cast.storyVoice.ifBlank { cast.narratorVoice } }
    private fun delivery(actor: String, before: String, after: String): Float {
        if (actor == "narrator" || actor == "story") return 1f
        val character = cast.characters.firstOrNull { "name:${it.name}" == actor }
        if (character?.role == CastRole.NARRATOR) return 1f
        when (character?.delivery) { CharacterDelivery.NATURAL -> return 1f; CharacterDelivery.LOWER -> return DeliveryCues.LOWER_PITCH; else -> Unit }
        val subject = when (actor) {
            "male" -> "he"; "female" -> "she"
            else -> actors.filterValues { it == actor }.keys.joinToString("|") { Regex.escape(it) }
        }
        return DeliveryCues.local(subject, before, after) ?: if (character?.let { it.learnedLower ?: it.startsLower } == true) DeliveryCues.LOWER_PITCH else 1f
    }
    fun plan(text: String, previous: DialogueState = DialogueState(), lookahead: String = ""): CastPlan {
        if (!cast.enabled) return CastPlan(listOf(CastUtterance(text, cast.narratorVoice)), DialogueState())
        val result = mutableListOf<CastUtterance>()
        fun append(value: String, actor: String, pitchScale: Float = 1f) {
            if (value.isEmpty()) return
            val voice = voice(actor)
            val last = result.lastOrNull()
            if (last != null && last.voice == voice && last.pitchScale == pitchScale) result[result.lastIndex] = last.copy(text = last.text + value)
            else result.add(CastUtterance(value, voice, pitchScale))
        }
        var cursor = 0
        var closing = previous.closingQuote
        var currentActor = previous.actor
        var pitchScale = previous.pitchScale
        var lastClosed = -1
        // The next already-staged source window supplies evidence only. Never emit it or
        // include it in the checkpoint; source offsets remain tied to this window.
        val evidence = text + lookahead.take(1800)
        fun afterQuote(end: Int?) = end?.let { evidence.substring(it + 1, (it + 241).coerceAtMost(evidence.length)) }.orEmpty()
        // Explicit named question introductions can contain direct speech without quotes.
        // Only bounded question sentences inherit that identity, never the following prose.
        val prefix = previous.narrativeLead.takeLast(720)
        val scene = prefix + DialogueQuotes.mask(evidence, previous.closingQuote)
        val questions = buildList {
            questionIntroduction?.findAll(scene)?.forEach { intro ->
                val speaker = actors[intro.groupValues[1].lowercase(Locale.ROOT)] ?: return@forEach
                var at = intro.range.last + 1; val limit = (at + 720).coerceAtMost(scene.length)
                repeat(8) {
                    val gapStart = at
                    while (at < limit && scene[at].isWhitespace()) at++
                    if (Regex("\\n\\s*\\n").containsMatchIn(scene.substring(gapStart, at))) return@forEach
                    val question = directQuestion.find(scene.substring(at, limit)) ?: return@forEach
                    val end = at + question.value.length
                    add(Triple(at - prefix.length, end - prefix.length, speaker)); at = end
                }
            }
        }
        fun appendStory(from: Int, until: Int) {
            var at = from
            for ((start, end, speaker) in questions) {
                val left = maxOf(at, start); val right = minOf(until, end)
                if (left >= right) continue
                append(text.substring(at, left), "story")
                append(text.substring(left, right), speaker, delivery(speaker, "", "")); at = right
            }
            append(text.substring(at, until), "story")
        }
        fun narrative(at: Int) = (previous.narrativeLead + DialogueQuotes.mask(text.substring(0, at), previous.closingQuote)).takeLast(720)
        while (cursor < text.length) {
            if (closing == null) {
                val start = (cursor until text.length).firstOrNull { DialogueQuotes.opening(text, it) != null } ?: break
                appendStory(cursor, start)
                closing = DialogueQuotes.opening(text, start)!!
                cursor = start
                val end = ((start + 1) until text.length).firstOrNull { DialogueQuotes.closes(text, it, closing!!) }
                val before = (previous.lead + text.substring(0, start)).takeLast(180)
                val evidenceEnd = end ?: (text.length until evidence.length).firstOrNull { DialogueQuotes.closes(evidence, it, closing!!) }
                val after = afterQuote(evidenceEnd)
                val gap = if (lastClosed >= 0) text.substring(lastClosed + 1, start) else previous.turnTail + text.substring(0, start)
                currentActor = actor(before, after, narrative(start), gap, "narrator"); pitchScale = delivery(currentActor, before, after)
                if (currentActor == "narrator" && end != null && end - start <= 62 && before.lastOrNull()?.isWhitespace() == true &&
                    before.trimEnd().lastOrNull()?.isLetterOrDigit() == true && text.substring(start + 1, end).none { it in ".!?:;\n" } &&
                    after.trimStart(' ', '\t', ',', ';', ':').firstOrNull()?.isLetterOrDigit() == true) currentActor = "story"
                if (end == null) { append(text.substring(cursor), currentActor, pitchScale); cursor = text.length }
                else { append(text.substring(cursor, end + 1), currentActor, pitchScale); lastClosed = end; cursor = end + 1; closing = null; currentActor = "narrator"; pitchScale = 1f }
            } else {
                val end = (cursor until text.length).firstOrNull { DialogueQuotes.closes(text, it, closing!!) }
                if (end == null) { append(text.substring(cursor), currentActor, pitchScale); cursor = text.length }
                else {
                    // Once a continued quote has a known speaker, its voice must not change halfway through.
                    if (currentActor == "narrator") {
                        val after = afterQuote(end)
                        currentActor = actor("", after, narrative(cursor), "", currentActor); pitchScale = delivery(currentActor, "", after)
                    }
                    append(text.substring(cursor, end + 1), currentActor, pitchScale); lastClosed = end; cursor = end + 1; closing = null; currentActor = "narrator"; pitchScale = 1f
                }
            }
        }
        if (cursor < text.length) appendStory(cursor, text.length)
        val tail = if (closing != null) "" else if (lastClosed >= 0) text.substring(lastClosed + 1) else previous.turnTail + text
        return CastPlan(result, DialogueState(closing, currentActor, text.takeLast(180), pitchScale, narrative(text.length), tail.takeLast(240)))
    }
}

object SpeechUtterances {
    /** Planning keeps paragraphs; a neural provider gets plain spaces after attribution. */
    fun singleParagraph(text: String): String = text.replace(Regex("[\\s\\u00A0\\u2007\\u202F]+"), " ").trim()
    /** Keep whole words/sentences together to reduce rushed or swallowed linking words. */
    fun split(text: String, limit: Int = 550): List<String> = SpeechText.chunks(text, limit).map { it.text }
}
