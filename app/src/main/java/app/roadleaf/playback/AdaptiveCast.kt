package app.roadleaf.playback

import java.security.MessageDigest
import java.util.Locale

enum class LearnedGender { UNKNOWN, MALE, FEMALE }
data class LearnedCharacter(
    val name: String, val turns: Int = 0, val windows: Int = 0, val viewpoint: Int = 0, val viewpointWindows: Int = 0,
    val maleCues: Int = 0, val femaleCues: Int = 0, val voice: String = "", val engine: String = "",
    val attitudeScore: Int = 0, val attitudeWindows: Int = 0
) {
    val gender get() = when { maleCues >= 2 && femaleCues == 0 -> LearnedGender.MALE; femaleCues >= 2 && maleCues == 0 -> LearnedGender.FEMALE; else -> LearnedGender.UNKNOWN }
}
data class LearnedCast(val windows: Int = 0, val main: String = "", val people: List<LearnedCharacter> = emptyList(), val seen: List<String> = emptyList(),
    val perspectives: List<ChapterPerspective> = emptyList())

/** These voice families are explicitly labelled by the installed engine, not inferred from character names. */
data class AdaptiveVoices(val engine: String = "", val narrator: String = "", val male: List<String> = emptyList(), val female: List<String> = emptyList()) {
    val maleDefault get() = male.firstOrNull { it != narrator }.orEmpty()
    val femaleDefault get() = female.firstOrNull { it != narrator }.orEmpty()
    fun main(gender: LearnedGender): String {
        val pool = when (gender) { LearnedGender.MALE -> male; LearnedGender.FEMALE -> female; else -> emptyList() }
        val shared = if (gender == LearnedGender.MALE) maleDefault else femaleDefault
        return pool.firstOrNull { it != narrator && it != shared } ?: pool.firstOrNull { it != narrator }.orEmpty()
    }
    companion object {
        fun from(catalog: SpeechCatalog, settings: ListeningSettings): AdaptiveVoices {
            val compatible = catalog.voices.filter {
                it.localeTag.equals(settings.localeTag, true) ||
                    (catalog.enginePackage == NativeKokoro.ENGINE_ID &&
                        it.localeTag.startsWith("en-", ignoreCase = true) && settings.localeTag.startsWith("en-", ignoreCase = true))
            }
            val narrator = settings.voiceName.ifBlank { compatible
                .sortedWith(compareBy<OfflineVoiceOption> { it.latency }.thenByDescending { it.quality }.thenBy { it.name }).firstOrNull()?.name.orEmpty() }
            val voices = compatible.map { it.name }
            if (catalog.enginePackage == NativeKokoro.ENGINE_ID) return AdaptiveVoices(
                catalog.enginePackage, narrator,
                voices.filter { NativeKokoro.speakerName(it)?.let { name -> name.startsWith("am_") || name.startsWith("bm_") } == true },
                voices.filter { NativeKokoro.speakerName(it)?.let { name -> name.startsWith("af_") || name.startsWith("bf_") } == true })
            if (catalog.enginePackage != "com.brahmadeo.supertonic.tts") return AdaptiveVoices(catalog.enginePackage, narrator)
            return AdaptiveVoices(catalog.enginePackage, narrator,
                voices.filter { Regex("en-supertonic-M[0-9]+").matches(it) }.sorted(),
                voices.filter { Regex("en-supertonic-F[0-9]+").matches(it) }.sorted())
        }
    }
}

/** Pure, bounded learning. Render using prior evidence; commit next only when the source span is queued. */
object AdaptiveCast {
    const val MAX_PEOPLE = 64
    const val MAX_SEEN = 256
    private val povVerbs = "thought|wondered|realized|realised|decided|remembered|noticed|felt"
    private val viewpointVerbs = povVerbs.split('|').toSet()
    private val speechVerbs = DeliveryCues.VERBS.split('|').filterNot { it in viewpointVerbs }.joinToString("|")
    private val capitalWord = Regex("(?<![\\p{L}\\p{N}_])[\\p{Lu}][\\p{L}'’\\-]+")
    private val nameWord = "[\\p{Lu}][\\p{L}'’\\-]{1,30}"
    private val named = Regex("(?<![\\p{L}\\p{N}_])($nameWord(?:[ \\t]+$nameWord){0,2})[ \\t]+(?:(?:quietly|softly|calmly|quickly|suddenly|finally)[ \\t]+)?($speechVerbs|$povVerbs)\\b")
    private val stop = setOf("he", "she", "they", "it", "we", "you", "the", "a", "an", "this", "that", "there", "then", "but", "and", "yes", "no", "someone", "everyone", "nobody", "chapter", "narrator")
    private val title = Regex("^(?:Captain|Lord|Lady|Sir|Miss|Mister|Doctor|Professor|Prince|Princess|King|Queen)\\s+")
    data class Plan(val cast: BookVoiceCast, val next: LearnedCast)
    private fun id(value: String) = value.trim().lowercase(Locale.ROOT)
    fun stamp(chapter: String, offset: Int, text: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$chapter\u0000$offset\u0000$text".toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    fun prepare(profile: BookVoiceCast, prior: LearnedCast, voices: AdaptiveVoices, chapter: String, offset: Int,
        text: String, context: DialogueState = DialogueState()): Plan {
        if (!profile.enabled) return Plan(profile, prior)
        if (!profile.adaptive) return Plan(profile.copy(storyVoice = ChapterPerspectives.voice(profile, prior, chapter)), prior)
        val bookVoices = voices.copy(narrator = profile.narratorVoice.ifBlank { voices.narrator })
        val bindings = prior.people.toMutableList()
        val configuredMain = profile.mainCharacter.split(',').firstOrNull()?.trim().orEmpty()
        val mainName = configuredMain.ifBlank { prior.main }
        val male = profile.maleVoice.ifBlank { bookVoices.maleDefault }
        val female = profile.femaleVoice.ifBlank { bookVoices.femaleDefault }
        var mainVoice = profile.mainVoice
        val automatic = mutableListOf<CastCharacter>()
        val reserved = (listOf(bookVoices.narrator, male, female, profile.mainVoice,
            bookVoices.main(LearnedGender.MALE), bookVoices.main(LearnedGender.FEMALE)) +
            profile.characters.map { it.voice } + bindings.filter { it.engine == voices.engine }.map { it.voice }).filter(String::isNotBlank).toMutableSet()
        val mainPerson = bindings.firstOrNull { id(it.name) == id(mainName) }
        bindings.indices.forEach { index ->
            var person = bindings[index]
            val isMain = mainName.isNotBlank() && id(person.name) == id(mainName)
            val ready = person.turns >= 2 && person.windows >= 2
            if (prior.windows > 0 && isMain && person.turns > 0) {
                val choice = profile.mainVoice.ifBlank {
                    person.voice.takeIf { person.engine == voices.engine }.orEmpty().ifBlank { bookVoices.main(person.gender) }
                }
                mainVoice = choice
                if (choice.isNotBlank() && (person.voice != choice || person.engine != voices.engine)) person = person.copy(voice = choice, engine = voices.engine)
            } else if (prior.windows > 0 && ready && profile.characters.none { id(it.name) == id(person.name) && it.role != CastRole.AUTO }) {
                val recurring = person.turns >= 6 && person.windows >= 3 &&
                    (mainPerson?.let { person.turns * 2 >= it.turns } ?: (person.turns >= 8 && person.windows >= 4))
                var dedicated = person.voice.takeIf { person.engine == voices.engine && person.gender != LearnedGender.UNKNOWN }.orEmpty()
                if (dedicated.isBlank() && recurring) {
                    val pool = when (person.gender) { LearnedGender.MALE -> voices.male; LearnedGender.FEMALE -> voices.female; else -> emptyList() }
                    dedicated = pool.firstOrNull { it !in reserved }.orEmpty()
                    if (dedicated.isNotBlank()) { person = person.copy(voice = dedicated, engine = voices.engine); reserved.add(dedicated) }
                }
                val role = when { dedicated.isNotBlank() -> CastRole.CUSTOM; person.gender == LearnedGender.MALE -> CastRole.MALE; person.gender == LearnedGender.FEMALE -> CastRole.FEMALE; else -> CastRole.NARRATOR }
                // An unknown learned name must not suppress a clear, local "she said" / "he said" tag.
                // Explicit owner assignments to Narrator remain in profile.characters and keep priority.
                if (role != CastRole.NARRATOR && profile.characters.none { id(it.name) == id(person.name) }) automatic.add(CastCharacter(person.name, role = role, voice = dedicated))
            }
            bindings[index] = person
        }
        fun pronoun(person: LearnedCharacter?) = when (person?.gender) { LearnedGender.MALE -> CharacterPronoun.HE; LearnedGender.FEMALE -> CharacterPronoun.SHE; else -> CharacterPronoun.AUTO }
        // New books begin with narration. A linked collection has already supplied its
        // speaker identities, so its tagged voices can work on the first window.
        val linked = profile.profileSetId.isNotBlank()
        val active = if (prior.windows == 0) profile.copy(mainVoice = profile.mainVoice.takeIf { linked }.orEmpty(), maleVoice = "", femaleVoice = "",
            characters = if (linked) profile.characters.filter { it.role != CastRole.AUTO && it.voice.isNotBlank() }
                else if (profile.sceneOnly) profile.characters.map { it.copy(role = CastRole.NARRATOR) } else emptyList(),
            storyVoice = ChapterPerspectives.voice(profile, prior, chapter))
            else profile.copy(mainCharacter = profile.mainCharacter.ifBlank { mainName }, mainVoice = mainVoice, maleVoice = male, femaleVoice = female,
                mainPronoun = profile.mainPronoun.takeUnless { it == CharacterPronoun.AUTO } ?: pronoun(mainPerson),
                characters = (automatic + profile.characters).map { character ->
                    val person = bindings.firstOrNull { id(it.name) == id(character.name) }
                    character.copy(role = if (character.role != CastRole.AUTO) character.role else when {
                        person != null && person.engine == voices.engine && person.voice.isNotBlank() -> CastRole.CUSTOM
                        person?.gender == LearnedGender.FEMALE -> CastRole.FEMALE
                        person?.gender == LearnedGender.MALE -> CastRole.MALE
                        else -> CastRole.NARRATOR
                    }, voice = if (character.role == CastRole.AUTO) person?.takeIf { it.engine == voices.engine }?.voice.orEmpty() else character.voice,
                        learnedLower = person?.takeIf { it.attitudeWindows >= 2 }?.let { it.attitudeScore < 0 },
                        pronoun = character.pronoun.takeUnless { it == CharacterPronoun.AUTO } ?: pronoun(person))
                })
        val next = if (profile.sceneOnly && SceneCast.breaks.replace(text, "").isBlank()) prior
            else observe(prior.copy(people = bindings), profile, chapter, offset, text, context)
        return Plan(if (prior.windows == 0) active else active.copy(storyVoice = ChapterPerspectives.voice(active, prior, chapter)), next)
    }

    private fun observe(prior: LearnedCast, profile: BookVoiceCast, chapter: String, offset: Int, text: String, context: DialogueState): LearnedCast {
        val fingerprint = stamp(chapter, offset, text)
        if (fingerprint in prior.seen) return prior
        val lead = context.lead.takeLast(180)
        val input = lead + text
        val safeLead = if (context.closingQuote == null && lead.indices.none { DialogueQuotes.opening(lead, it) != null }) lead else " ".repeat(lead.length)
        val masked = safeLead + DialogueQuotes.mask(text, context.closingQuote)
        val aliases = buildMap {
            profile.mainCharacter.split(',').map(String::trim).filter(String::isNotBlank).forEach { put(id(it), profile.mainCharacter.substringBefore(',').trim()) }
            profile.characters.forEach { c -> (listOf(c.name) + c.aliases.split(',')).map(String::trim).filter(String::isNotBlank).forEach { put(id(it), c.name) } }
        }
        fun attitude(name: String): Int {
            val cues = (listOf(name) + aliases.filterValues { id(it) == id(name) }.keys).distinct().map {
                DeliveryCues.attitude(masked.substring(lead.length), it, profile.mainCharacter.ifBlank { prior.main })
            }.filter { it != 0 }.distinct()
            return cues.singleOrNull() ?: 0
        }
        val people = prior.people.associateBy { id(it.name) }.toMutableMap()
        val turns = mutableMapOf<String, Int>(); val viewpoints = mutableMapOf<String, Int>()
        for (match in named.findAll(masked)) {
            if (match.range.last < lead.length && !(lead.substring(match.range.last + 1).trim().matches(Regex("[,;:—–-]*")) &&
                    text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 }?.let { DialogueQuotes.opening(text, it) } != null)) continue
            val rawName = match.groupValues[1].trim()
            val clean = title.replace(rawName, "")
            if (clean.split(Regex("\\s+")).any { id(it) in stop } || clean.length > 80) continue
            val name = aliases[id(rawName)] ?: aliases[id(clean)] ?: clean
            val key = id(name)
            if (key !in people && people.size >= MAX_PEOPLE) continue
            val verb = match.groupValues[2]
            val isViewpoint = verb in viewpointVerbs
            val around = input.substring((match.range.first - 160).coerceAtLeast(0), (match.range.last + 100).coerceAtMost(input.length))
            if (!isViewpoint && around.indices.none { DialogueQuotes.opening(around, it) != null } && context.closingQuote == null) continue
            people.putIfAbsent(key, LearnedCharacter(name))
            if (isViewpoint) viewpoints[key] = (viewpoints[key] ?: 0) + 1 else turns[key] = (turns[key] ?: 0) + 1
        }
        // A name on one side of the SAME quoted turn and he/she on the other is explicit evidence.
        // Do not carry an untagged speaker across turns or infer gender from a name.
        val linked = mutableMapOf<String, MutableSet<String>>()
        val immediate = Regex("^[\\s,;:—–-]*$")
        val afterPronoun = Regex("^\\s*[,—–-]?\\s*(he|she)\\s+(?:\\p{L}+\\s+){0,2}(?:$speechVerbs)\\b", RegexOption.IGNORE_CASE)
        val beforePronoun = Regex("\\b(he|she)\\s+(?:\\p{L}+\\s+){0,2}(?:$speechVerbs)\\b[\\s,;:—–-]*$", RegexOption.IGNORE_CASE)
        for (quote in DialogueQuotes.spans(text, context.closingQuote)) {
            if (quote.start < 0) continue
            val beforeEnd = lead.length + quote.start
            val afterStart = lead.length + quote.end + 1
            val before = input.substring((beforeEnd - 180).coerceAtLeast(0), beforeEnd)
            val after = input.substring(afterStart, (afterStart + 180).coerceAtMost(input.length))
            fun actualName(match: MatchResult): Boolean = title.replace(match.groupValues[1], "").split(Regex("\\s+")).none { id(it) in stop }
            val left = named.findAll(before).lastOrNull()?.takeIf { actualName(it) && immediate.matches(before.substring(it.range.last + 1)) }
            val right = named.find(after)?.takeIf { actualName(it) && immediate.matches(after.substring(0, it.range.first)) }
            val name = left?.groupValues?.get(1) ?: right?.groupValues?.get(1) ?: continue
            val afterTag = afterPronoun.find(after)?.takeUnless { tag ->
                val tail = after.substring(tag.range.last + 1).trimStart(' ', '\n', '\t', ',', ':', ';', '—', '–', '-')
                tail.isNotEmpty() && DialogueQuotes.opening(tail, 0) != null
            }
            val pronoun = if (left != null) afterTag?.groupValues?.get(1) else beforePronoun.find(before)?.groupValues?.get(1)
            if (pronoun == null) continue
            val clean = title.replace(name.trim(), "")
            val key = id(aliases[id(name)] ?: aliases[id(clean)] ?: clean)
            if (key in people) linked.getOrPut(key) { mutableSetOf() }.add(pronoun.lowercase(Locale.ROOT))
        }
        profile.characters.forEach { character ->
            if (people.size < MAX_PEOPLE && attitude(character.name) != 0)
                people.putIfAbsent(id(character.name), LearnedCharacter(character.name))
        }
        for ((key, old) in people.toMap()) {
            val variants = (listOf(old.name) + aliases.filterValues { id(it) == key }.keys).distinct().joinToString("|") { Regex.escape(it) }
            fun genderCue(pronoun: String, noun: String): Boolean {
                val expression = Regex("(?<![\\p{L}\\p{N}_])(?:$variants)(?![\\p{L}\\p{N}_])(?:[, ]+(?:was |is )?(?:a|an|the) (?:$noun)\\b|[^.!?\\n]{0,60}\\bas $pronoun\\b|[^.!?\\n]{0,60}[.!?]\\s+$pronoun\\b)", RegexOption.IGNORE_CASE)
                val prefix = Regex("^(?:$variants)", RegexOption.IGNORE_CASE)
                return expression.findAll(masked).any { match ->
                    val tail = match.value.drop(prefix.find(match.value)?.value?.length ?: match.value.length)
                        .replace(Regex("\\b(?:He|She)\\b"), "")
                    match.range.last >= lead.length && !capitalWord.containsMatchIn(tail)
                }
            }
            val m = genderCue("he", "boy|man|male") || "he" in linked[key].orEmpty()
            val f = genderCue("she", "girl|woman|female") || "she" in linked[key].orEmpty()
            val count = turns[key] ?: 0; val pov = viewpoints[key] ?: 0
            val attitude = attitude(old.name)
            val initialScore = if (old.attitudeWindows == 0 && profile.characters.any { id(it.name) == key && it.startsLower }) -2 else old.attitudeScore
            if (count > 0 || pov > 0 || m || f || attitude != 0) people[key] = old.copy(
                turns = (old.turns + count).coerceAtMost(10000), windows = (old.windows + if (count > 0) 1 else 0).coerceAtMost(10000),
                viewpoint = (old.viewpoint + pov).coerceAtMost(10000), viewpointWindows = (old.viewpointWindows + if (pov > 0) 1 else 0).coerceAtMost(10000),
                maleCues = (old.maleCues + if (m) 1 else 0).coerceAtMost(100), femaleCues = (old.femaleCues + if (f) 1 else 0).coerceAtMost(100),
                attitudeScore = if (attitude != 0) (initialScore + attitude).coerceIn(-2, 2) else old.attitudeScore,
                attitudeWindows = (old.attitudeWindows + if (attitude != 0) 1 else 0).coerceAtMost(10000))
        }
        var main = prior.main
        if (main.isBlank() && profile.mainCharacter.isBlank()) {
            val ranked = people.values.sortedByDescending { it.viewpoint * 4 + it.turns }
            val first = ranked.firstOrNull()
            val nextScore = ranked.getOrNull(1)?.let { it.viewpoint * 4 + it.turns } ?: 0
            if (first != null && first.turns >= 4 && first.windows >= 3 && first.viewpoint >= 3 && first.viewpointWindows >= 2 &&
                first.viewpoint * 4 + first.turns >= nextScore * 2 + 3) main = first.name
        }
        val perspectiveCounts = viewpoints.mapNotNull { (key, count) -> people[key]?.let { it.name to count } }.toMap()
        return LearnedCast((prior.windows + 1).coerceAtMost(1000000), main, people.values.toList(), (prior.seen + fingerprint).takeLast(MAX_SEEN),
            ChapterPerspectives.observe(prior.perspectives, chapter, perspectiveCounts))
    }
}
