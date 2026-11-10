package app.roadleaf

import app.roadleaf.playback.*
import org.junit.Assert.*
import org.junit.Test

class BookNarratorTest {
    private val profile = BookVoiceCast(mainCharacter = "Mira", mainVoice = "lead", narratorVoice = "book-narrator",
        mainPronoun = CharacterPronoun.SHE, femaleVoice = "supporting", maleVoice = "male")
    @Test fun theFirstUnlearnedPassageUsesTheBookNarratorIncludingDialogue() {
        val active = AdaptiveCast.prepare(profile, LearnedCast(), AdaptiveVoices(narrator = "global"), "one", 0,
            "Mira said, \"The road is clear.\"").cast
        assertEquals("book-narrator", active.storyVoice)
        assertTrue(VoiceCastPlanner(active).plan("Mira said, \"The road is clear.\"").utterances.all { it.voice == "book-narrator" })
    }
    @Test fun narratorOnlyKeepsTheOverrideWithoutActivatingCharacterCasting() {
        val active = AdaptiveCast.prepare(profile.copy(enabled = false), LearnedCast(4), AdaptiveVoices(), "one", 0, "").cast
        val plan = VoiceCastPlanner(active).plan("Mira said, \"Ready?\"")
        assertEquals(1, plan.utterances.size); assertEquals("book-narrator", plan.utterances.single().voice)
    }
    @Test fun theNarratorFirstOpeningRetainsAnUnfinishedDialogueBoundary() {
        val state = AdaptiveCast.prepare(profile, LearnedCast(), AdaptiveVoices(narrator = "global"), "one", 0, "Mira said, \"We should stay").cast
        val first = VoiceCastPlanner(state).plan("Mira said, \"We should stay")
        assertTrue(first.utterances.all { it.voice == "book-narrator" })
        assertNotNull(first.next.closingQuote)
        val next = VoiceCastPlanner(profile).plan(" together.\"", first.next)
        assertEquals("lead", next.utterances.first { "together" in it.text }.voice)
        assertNull(next.next.closingQuote)
    }
    @Test fun proseAndUncertainDialogueUseTheBookVoiceWhileTheLeadStaysDistinct() {
        val active = AdaptiveCast.prepare(profile.copy(adaptive = false), LearnedCast(), AdaptiveVoices(), "one", 0, "").cast
        val text = "A bird crossed the sky. \"Who is there?\"\n\n\"Only me,\" Mira replied."
        val plan = VoiceCastPlanner(active).plan(text)
        assertEquals(text, plan.utterances.joinToString("") { it.text })
        assertTrue(plan.utterances.first { it.text.contains("bird") }.voice == "book-narrator")
        assertEquals("lead", plan.utterances.first { it.text.contains("Only me") }.voice)
    }
    @Test fun chapterChoicesWinAndNarratorChoiceReturnsToTheBookOverride() {
        val selected = profile.copy(chapterViewpoints = mapOf("one" to "Mira", "two" to "@narrator"))
        assertEquals("lead", ChapterPerspectives.voice(selected, LearnedCast(), "one"))
        assertEquals("book-narrator", ChapterPerspectives.voice(selected, LearnedCast(), "two"))
        assertEquals("book-narrator", ChapterPerspectives.voice(selected, LearnedCast(), "three"))
        assertEquals("", ChapterPerspectives.voice(BookVoiceCast(), LearnedCast(), "three"))
    }
    @Test fun automaticSharedVoicesExcludeTheBookNarratorAndOtherProfilesAreUnchanged() {
        val voices = AdaptiveVoices("engine", "global", listOf("male"), listOf("female-a", "female-b", "female-c"))
        val femaleBook = BookVoiceCast(narratorVoice = "female-a")
        val next = AdaptiveCast.prepare(femaleBook, LearnedCast(1), voices, "one", 0, "Quiet.").cast
        assertEquals("female-b", next.femaleVoice)
        val other = AdaptiveCast.prepare(BookVoiceCast(), LearnedCast(1), voices, "one", 0, "Quiet.").cast
        assertEquals("female-a", other.femaleVoice); assertEquals("", other.narratorVoice)
        assertEquals("global", voices.narrator)
    }
}
