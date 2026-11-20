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

    private val split = BookVoiceCast(followViewpoint = false, adaptive = false, narratorVoice = "neutral",
        femaleNarratorVoice = "female-narrator", maleNarratorVoice = "male-narrator",
        characters = listOf(
            CastCharacter("Mira", pronoun = CharacterPronoun.SHE, voice = "mira-dialogue"),
            CastCharacter("Bram Alder", aliases = "Bram", pronoun = CharacterPronoun.HE, voice = "bram-dialogue")))

    @Test fun oppositeSpeakerKeepsTheirDialogueWithoutTakingOverTheScene() {
        val scene = SceneCast.plan("[POV: Mira]\nMira watched the door. Bram said, “Wait here.” She kept her eyes on the door.",
            split, DialogueState())
        assertEquals("bram-dialogue", scene.utterances.single { "Wait here" in it.text }.voice)
        assertEquals("female-narrator", scene.utterances.single { "kept her eyes" in it.text }.voice)
        assertTrue(scene.utterances.none { "[POV:" in it.text })
        val following = SceneCast.plan("[POV: Bram]\nBram watched the corridor. Mira said, “I am ready.” He nodded.",
            split, scene.next)
        assertEquals("mira-dialogue", following.utterances.single { "I am ready" in it.text }.voice)
        assertEquals("male-narrator", following.utterances.single { "He nodded" in it.text }.voice)
        assertEquals("male-narrator", SceneCast.plan("He waited.", split, following.next).utterances.single().voice)
        assertEquals("neutral", SceneCast.plan("---\nAn empty corridor.", split, following.next).utterances.single().voice)
        val source = "[POV: Mira]\nMira looked outside.\n"
        val resumed = SceneCast.before(split, source.reader(), source.length)
        assertEquals("female-narrator", SceneCast.plan("She breathed.", split, resumed).utterances.single().voice)
    }

    @Test fun repeatedInteriorEvidenceCanChooseANarratorWithManualDialogue() {
        val opening = AdaptiveCast.prepare(split, LearnedCast(), AdaptiveVoices(), "one", 0,
            "Mira wondered about the passage. Mira remembered the key.")
        assertEquals("neutral", opening.cast.storyVoice)
        val following = AdaptiveCast.prepare(split, opening.next, AdaptiveVoices(), "one", 70,
            "Bram said, “I know.” Mira realized where the door led.")
        assertEquals("female-narrator", following.cast.storyVoice)
        assertEquals("male-narrator", ChapterPerspectives.voice(split.copy(chapterViewpoints = mapOf("two" to "Bram")), following.next, "two"))
    }

    @Test fun piperAutomaticCastKeepsBothNarratorsAndDialogueFallbacksReserved() {
        fun piper(speaker: Int) = "vits-piper-en_US-libritts-high#$speaker#speaker_$speaker"
        val voicePool = AdaptiveVoices(HayaiVoices.ENGINE, piper(0), numberedPiper = (0..6).map(::piper))
        val profile = BookVoiceCast(followViewpoint = false, narratorVoice = piper(0), femaleNarratorVoice = piper(0),
            maleNarratorVoice = piper(1), maleVoice = piper(2), femaleVoice = piper(3), exclusiveVoices = true,
            characters = listOf(CastCharacter("Mira", voice = piper(4)), CastCharacter("Tara", role = CastRole.AUTO)))
        assertThrows(IllegalArgumentException::class.java) {
            profile.copy(mainVoice = piper(1)).validated()
        }
        val first = AdaptiveCast.prepare(profile.validated(), LearnedCast(), voicePool, "one", 0, "Tara said, “Hello.”")
        assertEquals(piper(5), first.cast.characters.single { it.name == "Tara" }.voice)
    }
}
