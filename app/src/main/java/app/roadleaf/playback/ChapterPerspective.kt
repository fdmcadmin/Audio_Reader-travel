package app.roadleaf.playback

import java.util.Locale

data class ViewpointEvidence(val name: String, val hits: Int = 0, val windows: Int = 0)
data class ChapterPerspective(val chapter: String, val evidence: List<ViewpointEvidence> = emptyList())

/** Chapter-local viewpoint evidence never replaces the book's fixed main-character identity. */
object ChapterPerspectives {
    const val MAX_CHAPTERS = 32
    fun observe(prior: List<ChapterPerspective>, chapter: String, counts: Map<String, Int>): List<ChapterPerspective> {
        if (counts.isEmpty() || chapter.length > 512) return prior
        val previous = prior.firstOrNull { it.chapter == chapter } ?: ChapterPerspective(chapter)
        val people = previous.evidence.associateBy { it.name.lowercase(Locale.ROOT) }.toMutableMap()
        counts.forEach { (name, count) ->
            val key = name.lowercase(Locale.ROOT); val old = people[key] ?: ViewpointEvidence(name)
            if (key in people || people.size < 16) people[key] = old.copy(hits = (old.hits + count).coerceAtMost(1000), windows = (old.windows + 1).coerceAtMost(1000))
        }
        return (prior.filterNot { it.chapter == chapter } + previous.copy(evidence = people.values.toList())).takeLast(MAX_CHAPTERS)
    }
    fun voice(profile: BookVoiceCast, state: LearnedCast, chapter: String): String {
        if (!profile.enabled) return profile.narratorVoice
        val selected = profile.chapterViewpoints[chapter] ?: if (profile.followViewpoint || profile.usesViewpointNarrators) {
            val ranked = state.perspectives.firstOrNull { it.chapter == chapter }?.evidence.orEmpty().sortedByDescending { it.hits }
            ranked.firstOrNull()?.takeIf { it.windows >= 2 && it.hits >= 3 && it.hits >= (ranked.getOrNull(1)?.hits ?: 0) * 2 + 1 }?.name
        } else null
        if (selected == null || selected == "@narrator") return profile.narratorVoice
        if (profile.usesViewpointNarrators) return narratorFor(profile, selected, state)
        fun matches(value: String) = value.split(',').any { it.trim().equals(selected, true) }
        if (matches(profile.mainCharacter)) return profile.mainVoice.ifBlank { profile.narratorVoice }
        return profile.characters.firstOrNull { matches(it.name + "," + it.aliases) }?.let {
            when (it.role) { CastRole.CUSTOM, CastRole.AUTO -> it.voice; CastRole.MALE -> profile.maleVoice; CastRole.FEMALE -> profile.femaleVoice; CastRole.NARRATOR -> profile.narratorVoice }
        }.orEmpty().ifBlank { profile.narratorVoice }
    }

    internal fun narratorFor(profile: BookVoiceCast, viewpoint: String, learned: LearnedCast = LearnedCast()): String {
        fun matches(value: String) = value.split(',').any { it.trim().equals(viewpoint, true) }
        val main = viewpoint == "@main" || matches(profile.mainCharacter)
        val person = profile.characters.firstOrNull { matches(it.name + "," + it.aliases) }
        val pronoun = if (main && profile.mainPronoun != CharacterPronoun.AUTO) profile.mainPronoun else person?.pronoun
        val gender = when (pronoun) {
            CharacterPronoun.SHE -> LearnedGender.FEMALE
            CharacterPronoun.HE -> LearnedGender.MALE
            CharacterPronoun.THEY -> LearnedGender.UNKNOWN
            else -> {
                when (person?.role) {
                    CastRole.FEMALE -> LearnedGender.FEMALE
                    CastRole.MALE -> LearnedGender.MALE
                    else -> learned.people.firstOrNull {
                        it.name.equals(person?.name ?: if (main) profile.mainCharacter.substringBefore(',').trim() else viewpoint, true)
                    }?.gender ?: LearnedGender.UNKNOWN
                }
            }
        }
        return when (gender) {
            LearnedGender.FEMALE -> profile.femaleNarratorVoice
            LearnedGender.MALE -> profile.maleNarratorVoice
            LearnedGender.UNKNOWN -> ""
        }.ifBlank { profile.narratorVoice }
    }
}
