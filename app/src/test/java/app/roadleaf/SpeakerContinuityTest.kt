package app.roadleaf

import app.roadleaf.playback.*
import org.junit.Assert.*
import org.junit.Test

class SpeakerContinuityTest {
    private val profile = BookVoiceCast(mainCharacter = "Arin", mainVoice = "arin", mainPronoun = CharacterPronoun.HE,
        maleVoice = "shared-man", femaleVoice = "shared-woman", characters = listOf(
            CastCharacter("Mira", voice = "mira", pronoun = CharacterPronoun.SHE),
            CastCharacter("Bram", voice = "bram", pronoun = CharacterPronoun.HE)))
    private fun voice(plan: CastPlan, words: String) = plan.utterances.first { words in it.text }.voice
    @Test fun descriptiveSpeechAndSameTurnContinuationKeepDedicatedIdentity() {
        val text = "\"The river,\" Mira was direct, her voice almost a whisper as she opened a map. \"Have you found the bridge?\""
        val plan = VoiceCastPlanner(profile).plan(text)
        assertEquals("mira", voice(plan, "The river")); assertEquals("mira", voice(plan, "Have you found"))
        assertEquals(text, plan.utterances.joinToString("") { it.text })
    }
    @Test fun expressiveNamedTagsAndReversedSpeechTagsAreRecognized() {
        val plan = VoiceCastPlanner(profile).plan("\"Is it safe?\" Mira’s eyes showed a spark of interest. \"The current,\" Mira narrowed her eyes. \"Yes,\" replied Bram. \"Wait,\" Mira cut him off gently.")
        assertEquals("mira", voice(plan, "Is it safe")); assertEquals("mira", voice(plan, "The current"))
        assertEquals("bram", voice(plan, "Yes")); assertEquals("mira", voice(plan, "Wait"))
    }
    @Test fun aRevealedSpeakerIsSeparateFromTheObserver() {
        val plan = VoiceCastPlanner(profile).plan("\"May I help?\" Bram turned to find Mira standing beside the gate.")
        assertEquals("mira", voice(plan, "May I help"))
    }
    @Test fun anActionInsideOneSpeechTurnAndALeadingActionKeepTheCharacter() {
        val plan = VoiceCastPlanner(profile).plan("\"Careful,\" Mira gently pushed the map back onto the table. \"The ink is wet.\"\n\nBram unfolded his coat. \"Let it dry.\"")
        assertEquals("mira", voice(plan, "Careful")); assertEquals("mira", voice(plan, "ink is wet"))
        assertEquals("bram", voice(plan, "Let it dry"))
    }
    @Test fun rightCurlyThoughtQuotesAndWordApostrophesStaySeparate() {
        val text = "’I should wait,’ Arin thought near Bram’s boat. ’The water isn’t safe.’"
        val plan = VoiceCastPlanner(profile).plan(text)
        assertEquals("arin", voice(plan, "I should wait")); assertEquals("arin", voice(plan, "water isn’t safe"))
        assertNull(plan.next.closingQuote); assertEquals(text, plan.utterances.joinToString("") { it.text })
    }
    @Test fun pronounActionBeatsWithinOneTurnKeepTheEstablishedPerson() {
        val text = "Mira sat beside Bram. \"The bridge is safe,\" she winked. \"We can cross.\""
        val plan = VoiceCastPlanner(profile).plan(text)
        assertEquals("mira", voice(plan, "bridge is safe")); assertEquals("mira", voice(plan, "We can cross"))
    }
    @Test fun inlineQuotedLabelsStayWithTheViewpointNarration() {
        val plan = VoiceCastPlanner(profile.copy(storyVoice = "bram")).plan("Bram counted three \"examples\" in the lesson and remembered the \"Quiet Boy\" label.")
        assertTrue(plan.utterances.all { it.voice == "bram" })
    }
    @Test fun pronounsKeepARecentUnambiguousCharacterAcrossWindows() {
        val planner = VoiceCastPlanner(profile)
        val first = planner.plan("Mira examined the map. \"The river is deep,\" Mira replied. Bram waited beside the road. ")
        val next = planner.plan("\"Stay close,\" she murmured. \"I will,\" Bram answered.", first.next)
        assertEquals("mira", voice(next, "Stay close")); assertEquals("bram", voice(next, "I will"))
        assertTrue(next.next.narrativeLead.length <= 720)
    }
    @Test fun quotedNamesDoNotStealTheSpeakerOrCreatePronounEvidence() {
        val plan = VoiceCastPlanner(profile).plan("\"Mira, wait,\" Bram said. \"Why?\" she asked.")
        assertEquals("bram", voice(plan, "Mira, wait")); assertEquals("shared-woman", voice(plan, "Why"))
    }
    @Test fun ambiguousOrExpiredPronounEvidenceDoesNotPickADedicatedVoice() {
        val cast = profile.copy(characters = profile.characters + CastCharacter("Nora", voice = "nora", pronoun = CharacterPronoun.SHE))
        assertEquals("shared-woman", voice(VoiceCastPlanner(cast).plan("Mira stood beside Nora. \"Wait,\" she said."), "Wait"))
        assertEquals("shared-woman", voice(VoiceCastPlanner(profile).plan("Mira waited. " + "A quiet river flowed. ".repeat(50) + "\"Wait,\" she said."), "Wait"))
    }
    @Test fun unrelatedMovementAndNextTurnIntroductionsDoNotClaimThePreviousLine() {
        assertEquals("", voice(VoiceCastPlanner(profile).plan("\"Wait.\" Mira walked away."), "Wait"))
        val plan = VoiceCastPlanner(profile).plan("\"Unknown.\" Mira said, \"Mine.\"")
        assertEquals("", voice(plan, "Unknown")); assertEquals("mira", voice(plan, "Mine"))
    }
    @Test fun viewpointNarrationDoesNotChangeDialogOrUseAMentionedMc() {
        val cast = profile.copy(chapterViewpoints = mapOf("chapter-36" to "Bram"))
        val active = AdaptiveCast.prepare(cast, LearnedCast(3), AdaptiveVoices(), "chapter-36", 0, "").cast
        val text = "Bram recalled Arin’s advice. \"Arin showed me the map,\" Bram said. \"Good,\" Mira replied. The river was quiet."
        val plan = VoiceCastPlanner(active).plan(text)
        assertEquals("bram", voice(plan, "recalled")); assertEquals("bram", voice(plan, "river was quiet"))
        assertEquals("mira", voice(plan, "Good")); assertTrue(plan.utterances.none { it.voice == "arin" })
        assertEquals("Arin", active.mainCharacter); assertEquals("arin", active.mainVoice)
    }
    @Test fun speakerBeyondSourceBoundaryIsChosenBeforeTheQuoteStarts() {
        val planner = VoiceCastPlanner(profile)
        val firstText = "Bram waited. \"The river looks"
        val following = " deep,\" Mira said. The sun came out."
        val first = planner.plan(firstText, lookahead = following)
        assertEquals("mira", voice(first, "river looks"))
        assertEquals(firstText, first.utterances.joinToString("") { it.text })
        assertFalse(first.next.lead.contains("sun")); assertFalse(first.next.narrativeLead.contains("sun"))
        val second = planner.plan(following, first.next)
        assertEquals("mira", voice(second, "deep"))
        assertNull(second.next.closingQuote)
    }
    @Test fun aClosedQuoteCanUseAnAdjacentTagWithoutSpeakingTheNextWindow() {
        val text = "\"Wait here.\""
        val plan = VoiceCastPlanner(profile).plan(text, lookahead = " Mira whispered. Bram watched.")
        assertEquals("mira", voice(plan, "Wait")); assertEquals(text, plan.utterances.joinToString("") { it.text })
        assertEquals("", plan.next.narrativeLead.trim())
    }
    @Test fun distantLookaheadDoesNotInventASpeaker() {
        val text = "\"Wait"
        val plan = VoiceCastPlanner(profile).plan(text, lookahead = " quietly".repeat(300) + ",\" Mira said.")
        assertEquals("", voice(plan, "Wait")); assertEquals(text, plan.utterances.joinToString("") { it.text })
    }
    @Test fun automaticViewpointsNeedRepeatedEvidenceAndStayChapterLocal() {
        var state = LearnedCast()
        repeat(2) { index -> state = AdaptiveCast.prepare(profile, state, AdaptiveVoices(), "one", index * 100,
            "Bram wondered about the map. Bram noticed the river.").next }
        assertEquals("bram", AdaptiveCast.prepare(profile, state, AdaptiveVoices(), "one", 300, "Quiet.").cast.storyVoice)
        assertEquals("", AdaptiveCast.prepare(profile, state, AdaptiveVoices(), "two", 0, "Quiet.").cast.storyVoice)
        val claimed = AdaptiveCast.prepare(profile, LearnedCast(), AdaptiveVoices(), "one", 0, "Arin said, \"Bram wondered about the map. Bram noticed the river.\"").next
        assertTrue(claimed.perspectives.isEmpty())
    }
    @Test fun explicitNarratorAndNewBookDefaultsRemainAvailable() {
        val cast = profile.copy(chapterViewpoints = mapOf("one" to "@narrator"))
        val state = LearnedCast(3, perspectives = listOf(ChapterPerspective("one", listOf(ViewpointEvidence("Bram", 5, 3)))))
        assertEquals("", AdaptiveCast.prepare(cast, state, AdaptiveVoices(), "one", 0, "Quiet.").cast.storyVoice)
        assertEquals("", AdaptiveCast.prepare(profile, LearnedCast(), AdaptiveVoices(), "one", 0, "Quiet.").cast.storyVoice)
        assertEquals("", AdaptiveCast.prepare(profile.copy(followViewpoint = false), state, AdaptiveVoices(), "one", 0, "Quiet.").cast.storyVoice)
    }
    @Test fun explicitlyIntroducedUnquotedQuestionsKeepTheNamedVoiceAndReturnToProse() {
        val text = "Bram drew a map. Mira kept a steady flow of precise questions: Where is the river? Which bridge is safest? How long will it take?\n\nBram folded the map."
        val plan = VoiceCastPlanner(profile.copy(storyVoice = "bram")).plan(text)
        for (words in listOf("Where is", "Which bridge", "How long")) assertEquals("mira", voice(plan, words))
        assertEquals("bram", voice(plan, "Bram drew")); assertEquals("bram", voice(plan, "Bram folded"))
        assertEquals(text, plan.utterances.joinToString("") { it.text })
    }
    @Test fun unquotedQuestionsRetainIdentityAcrossAWindowWithoutClaimingUnrelatedQuestions() {
        val planner = VoiceCastPlanner(profile.copy(storyVoice = "bram"))
        val first = planner.plan("Mira asked: Where is the", lookahead = " bridge? How far?\n\nBram wondered about the weather.")
        assertEquals("mira", voice(first, "Where is"))
        val next = planner.plan(" bridge? How far?\n\nBram wondered about the weather.", first.next)
        assertEquals("mira", voice(next, "bridge")); assertEquals("mira", voice(next, "How far"))
        assertEquals("bram", voice(next, "weather"))
        val unrelated = planner.plan("Someone asked: Where is the bridge?\n\nMira read a note. Which road should they take?")
        assertTrue(unrelated.utterances.all { it.voice == "bram" })
        val quoted = planner.plan("\"Mira asked: Where is the bridge?\" Bram said.")
        assertEquals("bram", voice(quoted, "Where is"))
    }
    @Test fun unknownDialogueUsesTheChapterNarratorWithoutUsingTheBookMc() {
        val cast = profile.copy(storyVoice = "bram", characters = profile.characters + CastCharacter("Guide", role = CastRole.NARRATOR))
        val plan = VoiceCastPlanner(cast).plan("Bram waited. \"What?\"\n\n\"Stay close,\" Guide said.")
        assertTrue(plan.utterances.all { it.voice == "bram" })
    }
}
