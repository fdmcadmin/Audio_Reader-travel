package app.roadleaf

import app.roadleaf.playback.*
import org.junit.Assert.*
import org.junit.Test

class AdaptiveCastTest {
    private val voices = AdaptiveVoices("engine", "M5", listOf("M1", "M2", "M3", "M4", "M5"), listOf("F1", "F2", "F3", "F4", "F5"))
    private fun plan(profile: BookVoiceCast = BookVoiceCast(), state: LearnedCast = LearnedCast(), index: Int = 0,
        text: String = "Arlo said, \"The path is clear.\" Arlo wondered about the road.", context: DialogueState = DialogueState()) =
        AdaptiveCast.prepare(profile, state, voices, "chapter", index * 1800, text, context)
    @Test fun newBooksDefaultToAutomaticButOpeningIsAllNarrator() {
        val profile = BookVoiceCast(mainCharacter = "Arlo", mainVoice = "M2", maleVoice = "M1", femaleVoice = "F1")
        assertTrue(profile.enabled && profile.adaptive)
        val first = plan(profile)
        assertEquals("", first.cast.mainVoice); assertEquals("", first.cast.maleVoice); assertEquals("", first.cast.femaleVoice)
        assertTrue(first.cast.characters.isEmpty())
        assertEquals(1, first.next.windows); assertEquals("Arlo", first.next.people.single().name)
        assertEquals("M2", plan(profile, first.next, 1).cast.mainVoice)
    }
    @Test fun repeatedClearTagsBuildSharedFemaleVoiceWithoutNameGuessing() {
        val text = "Mira was a woman. Mira said, \"Wait for the others.\""
        val one = plan(text = text).next
        assertEquals(LearnedGender.UNKNOWN, one.people.single().gender)
        assertTrue(plan(state = one, index = 1, text = text).cast.characters.isEmpty())
        val two = plan(state = one, index = 1, text = text).next
        val learned = plan(state = two, index = 2, text = text).cast
        assertEquals(CastRole.FEMALE, learned.characters.single().role)
        assertEquals("F1", learned.femaleVoice)
        assertEquals("F1", VoiceCastPlanner(learned).plan("Mira said, \"Ready.\"").utterances.last().voice)
    }
    @Test fun quotedDescriptionsAndPronounNamesAreNotIdentityEvidence() {
        val text = "Arlo said, \"Mira said he was a boy. She answered.\" He said, \"I agree.\""
        val next = plan(text = text).next
        assertEquals(listOf("Arlo"), next.people.map { it.name })
        assertEquals(LearnedGender.UNKNOWN, next.people.single().gender)
    }
    @Test fun aNearbyDifferentNamePreventsAmbiguousPronounAssociation() {
        val text = "Arlo said to Mira. She left the gate. Mira said, \"I will return.\""
        var state = plan(text = text).next
        state = plan(state = state, index = 1, text = text).next
        assertEquals(LearnedGender.UNKNOWN, state.people.first { it.name == "Arlo" }.gender)
        assertEquals(LearnedGender.FEMALE, state.people.first { it.name == "Mira" }.gender)
    }
    @Test fun ambiguousGenderReturnsToNarratorAndManualChoiceWins() {
        val person = LearnedCharacter("Mira", turns = 4, windows = 3, maleCues = 2, femaleCues = 2)
        val state = LearnedCast(3, people = listOf(person))
        assertTrue(plan(state = state).cast.characters.isEmpty())
        val manual = CastCharacter("Mira", role = CastRole.CUSTOM, voice = "chosen")
        assertEquals(manual, plan(BookVoiceCast(characters = listOf(manual)), state).cast.characters.single())
    }
    @Test fun speechFrequencyAloneDoesNotDeclareTheMainCharacter() {
        val loud = LearnedCharacter("Bram", turns = 100, windows = 20, maleCues = 3)
        val next = plan(state = LearnedCast(20, people = listOf(loud)), text = "Bram said, \"Listen.\"").next
        assertEquals("", next.main)
    }
    @Test fun dominantRepeatedViewpointCuesCanIdentifyAMainCharacter() {
        var state = LearnedCast()
        val text = "Arlo said, \"I can see a way.\" Arlo replied, \"We should go.\" Arlo thought about his choice. Arlo wondered about the lights. Arlo was a man."
        repeat(3) { state = plan(state = state, index = it, text = text).next }
        assertEquals("Arlo", state.main)
        val result = plan(state = state, index = 3, text = text)
        assertEquals("Arlo", result.cast.mainCharacter); assertEquals("M2", result.cast.mainVoice)
        val other = state.copy(people = state.people + LearnedCharacter("Bram", 500, 50, 500, 50))
        assertEquals("Arlo", plan(state = other, index = 4).next.main)
    }
    @Test fun tiedViewpointsRemainUncertainAndUserMcOverridesLearnedGuess() {
        val a = LearnedCharacter("Arlo", 8, 4, 4, 3, 2)
        val b = LearnedCharacter("Bram", 8, 4, 4, 3, 2)
        val state = LearnedCast(4, people = listOf(a, b))
        assertEquals("", plan(state = state, text = "A quiet morning followed.").next.main)
        val result = plan(BookVoiceCast(mainCharacter = "Arlo, Arli", mainVoice = "fixed"), state.copy(main = "Bram"))
        assertEquals("Arlo, Arli", result.cast.mainCharacter); assertEquals("fixed", result.cast.mainVoice)
        assertEquals("fixed", VoiceCastPlanner(result.cast).plan("Arli said, \"Wait.\"").utterances.last().voice)
    }
    @Test fun frequentSupportingCharactersEarnStableDistinctVoices() {
        val state = LearnedCast(10, "Arlo", listOf(
            LearnedCharacter("Arlo", 12, 6, 4, 3, 2), LearnedCharacter("Bram", 8, 4, maleCues = 2), LearnedCharacter("Mira", 8, 4, femaleCues = 2)))
        val first = plan(state = state)
        val cast = first.cast.characters.associateBy { it.name }
        assertEquals("M3", cast["Bram"]?.voice); assertEquals("F3", cast["Mira"]?.voice)
        val again = AdaptiveCast.prepare(BookVoiceCast(), first.next, voices.copy(male = voices.male.reversed(), female = voices.female.reversed()), "chapter", 1800, "Quiet roads.")
        assertEquals("M3", again.cast.characters.first { it.name == "Bram" }.voice)
        assertEquals("F3", again.cast.characters.first { it.name == "Mira" }.voice)
    }
    @Test fun rareCharactersDoNotConsumeDedicatedVoiceSlots() {
        val state = LearnedCast(4, "Arlo", listOf(LearnedCharacter("Arlo", 20, 6, maleCues = 2), LearnedCharacter("Bram", 2, 2, maleCues = 2)))
        assertEquals(CastRole.MALE, plan(state = state).cast.characters.single().role)
    }
    @Test fun noAvailableDedicatedVoiceKeepsSharedVoiceWithoutReplacingBindings() {
        val person = LearnedCharacter("Bram", 10, 5, maleCues = 2)
        val state = LearnedCast(5, people = listOf(person))
        val result = AdaptiveCast.prepare(BookVoiceCast(), state, voices.copy(male = listOf("M1", "M2")), "chapter", 0, "Quiet.")
        assertEquals(CastRole.MALE, result.cast.characters.single().role)
    }
    @Test fun canceledPlanningIsPureAndReplayingAPassageDoesNotDoubleLearn() {
        val prior = LearnedCast()
        val first = plan(state = prior)
        assertEquals(LearnedCast(), prior); assertEquals(first, plan(state = prior))
        val replay = plan(state = first.next)
        assertEquals(first.next.windows, replay.next.windows)
        assertEquals(first.next.people.map { it.turns }, replay.next.people.map { it.turns })
    }
    @Test fun narratorAndManualModesDoNotLearnOrOverwriteAssignments() {
        val off = BookVoiceCast(enabled = false)
        assertEquals(LearnedCast(), plan(off).next)
        val manual = BookVoiceCast(mainCharacter = "Arlo", mainVoice = "chosen", adaptive = false)
        assertEquals(manual, plan(manual).cast); assertEquals(LearnedCast(), plan(manual).next)
    }
    @Test fun voiceFamiliesRequireKnownEngineLabelsAndMatchingAccent() {
        val catalog = SpeechCatalog(emptyList(), listOf(OfflineVoiceOption("en-supertonic-M2", "en-US", 400, 200), OfflineVoiceOption("en-supertonic-F1", "en-GB", 400, 200)), "com.brahmadeo.supertonic.tts")
        assertEquals(listOf("en-supertonic-M2"), AdaptiveVoices.from(catalog, ListeningSettings()).male)
        assertTrue(AdaptiveVoices.from(catalog, ListeningSettings()).female.isEmpty())
        assertTrue(AdaptiveVoices.from(catalog.copy(enginePackage = "unknown.engine"), ListeningSettings()).male.isEmpty())
    }
    @Test fun aSpeechTagSplitBeforeTheQuoteCanStillBeLearned() {
        val result = plan(text = "\"We should go.\"", context = DialogueState(lead = "Arlo said, "))
        assertEquals("Arlo", result.next.people.single().name)
        val inside = plan(text = "Mira said she was a woman.\" Arlo replied, \"Wait.\"", context = DialogueState('"', "narrator", "Inside an open quote "))
        assertEquals(listOf("Arlo"), inside.next.people.map { it.name })
    }
    @Test fun anUnknownLearnedNameCannotHideAnExplicitFemaleTag() {
        val state = LearnedCast(3, people = listOf(LearnedCharacter("Mira", 4, 3)))
        val cast = plan(state = state).cast
        val dialogue = VoiceCastPlanner(cast).plan("Mira said, ‘Wait for the others,’ she added.")
        assertEquals("F1", dialogue.utterances.first { "Wait for" in it.text }.voice)
    }
    @Test fun repeatedTagsOnTheSameQuoteLearnGenderAndKeepNamedDialogueConsistent() {
        val text = "Mira said, ‘Wait for the others,’ she added."
        var state = plan(text = text).next
        assertEquals(LearnedGender.UNKNOWN, state.people.single().gender)
        state = plan(state = state, index = 1, text = text).next
        assertEquals(LearnedGender.FEMALE, state.people.single().gender)
        val cast = plan(state = state, index = 2, text = "A quiet road.").cast
        assertEquals("F1", VoiceCastPlanner(cast).plan("Mira said, 'I know the way.'").utterances.last().voice)
    }
    @Test fun matchingPronounBeforeAndNameAfterTheQuoteAlsoLearnButOtherTurnsDoNot() {
        var state = LearnedCast()
        repeat(2) { state = plan(state = state, index = it, text = "She replied, \"We should go,\" Mira said.").next }
        assertEquals(LearnedGender.FEMALE, state.people.single().gender)
        val other = plan(text = "Mira said, \"Wait.\" She said, \"No.\"").next
        assertEquals(LearnedGender.UNKNOWN, other.people.single().gender)
        assertEquals(0, other.people.single().femaleCues)
    }
    @Test fun quotedSingleQuoteClaimsAndWordApostrophesDoNotCreateCharacters() {
        val next = plan(text = "Arlo said, 'Mira said she was a woman. Don't believe it.'").next
        assertEquals(listOf("Arlo"), next.people.map { it.name })
        assertEquals(LearnedGender.UNKNOWN, next.people.single().gender)
    }
    @Test fun explicitMainVoiceSurvivesWhenTheNameIsNotYetLearned() {
        val cast = plan(BookVoiceCast(mainCharacter = "Arlo", mainVoice = "chosen"), LearnedCast(windows = 1), text = "A quiet morning.").cast
        assertEquals("chosen", cast.mainVoice)
    }
    @Test fun narratorMainFemaleAndMaleSuggestionsHaveDifferentVoiceIds() {
        val selected = listOf(voices.narrator, voices.main(LearnedGender.MALE), voices.femaleDefault, voices.maleDefault)
        assertEquals(4, selected.distinct().size)
        val jamesNarrator = voices.copy(narrator = "M2")
        assertNotEquals("M2", jamesNarrator.main(LearnedGender.MALE))
    }
    @Test fun learningStorageIsBoundedEvenForVeryLargeSerials() {
        val state = LearnedCast(1000, people = (0 until 64).map { LearnedCharacter("Name${('A'.code + it / 26).toChar()}${('A'.code + it % 26).toChar()}") },
            seen = (0 until 256).map { it.toString() })
        val result = plan(state = state, text = "Zelda said, \"Another road.\"").next
        assertEquals(64, result.people.size); assertEquals(256, result.seen.size); assertFalse(result.people.any { it.name == "Zelda" })
    }
}
