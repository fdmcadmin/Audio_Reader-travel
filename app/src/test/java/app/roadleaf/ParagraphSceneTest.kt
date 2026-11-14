package app.roadleaf

import app.roadleaf.playback.*
import app.roadleaf.importing.CharacterLorebook
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class ParagraphSceneTest {
    private val text = "First paragraph.\n\nSecond paragraph goes here.\n\nLast paragraph."
    private val cast = BookVoiceCast(adaptive = false, narratorVoice = "narrator", sceneOnly = true,
        characters = listOf(CastCharacter("Mira", "Captain Mira", voice = "mira"), CastCharacter("Casey", voice = "casey")))
    @Test fun paragraphStartUsesOriginalOffsetsAndSkipsBlankLines() {
        val at = text.indexOf("goes")
        assertEquals(text.indexOf("Second"), ParagraphResume.start(text, at))
        assertEquals(ParagraphResume.start(text, at), ParagraphResume.start(StringReader(text), at))
    }
    @Test fun paragraphStartHandlesBeginningEndAndIndentation() {
        assertEquals(0, ParagraphResume.start(text, 0))
        assertEquals(text.indexOf("Last"), ParagraphResume.start(StringReader(text), Int.MAX_VALUE))
        assertEquals(4, ParagraphResume.start("  \n Hi", 6))
        assertEquals(0, ParagraphResume.start("Last.\n\n", 7))
        assertEquals(0, ParagraphResume.start(StringReader("Last.\n\n"), Int.MAX_VALUE))
    }
    @Test fun appendedStoryRetainsParagraphRatherThanJumpingToNewEnd() {
        val at = text.indexOf("goes")
        assertEquals(ParagraphResume.Relocated(text.indexOf("Second"), true), ParagraphResume.relocate(text, at, "$text\nNew ending."))
    }
    @Test fun insertionBeforeSavedParagraphRelocatesItsExactText() {
        val updated = "New opening.\n$text"
        assertEquals(updated.indexOf("Second"), ParagraphResume.relocate(text, text.indexOf("goes"), updated).offset)
    }
    @Test fun editedOrAmbiguousParagraphNeverGuessesAnUnrelatedPosition() {
        assertFalse(ParagraphResume.relocate(text, text.indexOf("goes"), "Entirely changed.").matched)
        assertFalse(ParagraphResume.relocate("old\nRepeat.", 5, "new\nRepeat.\nRepeat.").matched)
    }
    @Test fun privateSpeechWindowsFollowParagraphsWithoutChangingSourceText() {
        val reader = SpeechChunkReader(StringReader(text), paragraphChunks = true)
        val chunks = generateSequence { reader.next() }.toList()
        assertEquals(3, chunks.size)
        assertEquals(text.indexOf("Second"), chunks[1].offset)
        assertTrue(chunks.all { it.text == text.substring(it.offset, it.offset + it.text.length) })
        assertEquals(text.filterNot(Char::isWhitespace), chunks.joinToString("") { it.text }.filterNot(Char::isWhitespace))
    }
    @Test fun longParagraphStaysBoundedAndRewindsToItsRealStart() {
        val long = "An opening.\n" + "word ".repeat(1000)
        val reader = SpeechChunkReader(StringReader(long), paragraphChunks = true)
        val chunks = generateSequence { reader.next() }.toList()
        assertTrue(chunks.all { it.text.length <= 1800 })
        assertEquals(12, ParagraphResume.start(long, chunks[2].offset))
    }
    @Test fun sceneBreakDropsPreviousCharacterAndDialogueContinuation() {
        val first = SceneCast.plan("Mira said, \"Stay here.\"", cast, DialogueState())
        assertTrue("mira" in first.next.sceneNames)
        val next = SceneCast.plan("\n***\nCasey said, \"Come along.\"", cast, first.next)
        assertFalse("mira" in next.next.sceneNames)
        assertTrue(next.utterances.any { it.voice == "casey" })
        assertFalse(next.utterances.any { it.voice == "mira" })
    }
    @Test fun lookaheadCannotActivateACharacterInTheNextScene() {
        val plan = SceneCast.plan("A quiet empty room.", cast, DialogueState(), "\n---\nMira said, \"Hello.\"")
        assertFalse("mira" in plan.next.sceneNames)
    }
    @Test fun explicitCastExcludesOffstageMentionsAndIsNotSpoken() {
        val plan = SceneCast.plan("[Scene: Casey]\nCasey said, \"Mira is away.\"", cast, DialogueState())
        assertTrue(plan.next.sceneExplicit)
        assertEquals(setOf("casey"), plan.next.sceneNames)
        assertFalse(plan.utterances.joinToString { it.text }.contains("[Scene:"))
    }
    @Test fun scenePresenceRestoresFromSourceBeforeAParagraphAfterRestart() {
        val source = "Mira entered.\nA long quiet pause.\n***\n[Scene: Casey]\nA later paragraph."
        val at = source.indexOf("A later")
        val state = SceneCast.before(cast, StringReader(source), at)
        assertEquals(setOf("casey"), state.sceneNames)
        assertTrue(state.sceneExplicit)
    }
    @Test fun wholeNamesDoNotTriggerInsideOtherWords() {
        val plan = SceneCast.plan("Miraandra waited for Caseyna.", cast, DialogueState())
        assertTrue(plan.next.sceneNames.isEmpty())
    }
    @Test fun sceneGateHandlesTwoScenesWithinOneSpeechWindow() {
        val plan = SceneCast.plan("Mira said, \"First.\"\n---\nCasey said, \"Second.\"", cast, DialogueState())
        assertTrue(plan.utterances.any { it.voice == "mira" }); assertTrue(plan.utterances.any { it.voice == "casey" })
        assertEquals(setOf("casey"), plan.next.sceneNames)
    }
    @Test fun lorebookMetadataImportsCharactersWithoutGroupsOrInstructions() {
        val people = CharacterLorebook.headers(listOf(CharacterLorebook.Header("char_one", "01 Mira — Preset", keys = listOf("Mira", "char_mira")),
            CharacterLorebook.Header("group", "Group — Flight", keys = listOf("Mira"))))
        assertEquals(1, people.size); assertEquals("Mira", people.single().name); assertEquals(CastRole.AUTO, people.single().role)
    }
    @Test fun disabledAndRegexEntriesDoNotExecuteActivationCode() {
        val people = CharacterLorebook.headers(listOf(CharacterLorebook.Header("char_one", "01 Mira — Preset", keys = listOf("/unsafe.*/iu")),
            CharacterLorebook.Header("char_two", "02 Casey — Preset", enabled = false, keys = listOf("Casey"))))
        assertEquals("Mira", people.single().name); assertEquals("", people.single().aliases)
    }
    @Test fun importingProfilesPreservesOwnerVoiceAndEnablesSceneGate() {
        val existing = cast.copy(sceneOnly = false)
        val merged = CharacterLorebook.merge(existing, listOf(CastCharacter("Mira", voice = "other"), CastCharacter("New Person", role = CastRole.AUTO)))
        assertTrue(merged.sceneOnly); assertEquals("mira", merged.characters.first { it.name == "Mira" }.voice); assertEquals(3, merged.characters.size)
    }
    @Test fun importingProfilesFillsAnAutomaticVoiceWithoutReplacingAnOwnerChoice() {
        val existing = BookVoiceCast(characters = listOf(
            CastCharacter("Aster", role = CastRole.AUTO), CastCharacter("Morrow", voice = "owner", role = CastRole.CUSTOM)))
        val incoming = listOf(CastCharacter("Aster", voice = "kokoro:2", role = CastRole.CUSTOM),
            CastCharacter("Morrow", voice = "kokoro:18", role = CastRole.CUSTOM))
        val merged = CharacterLorebook.merge(existing, incoming, "story-one")
        assertEquals("kokoro:2", merged.characters.first().voice)
        assertEquals(CastRole.CUSTOM, merged.characters.first().role)
        assertEquals("owner", merged.characters.last().voice)
    }
    @Test fun sceneMetadataDoesNotConsumeTheNarratorOnlyOpening() {
        val profile = cast.copy(adaptive = true)
        val adaptation = AdaptiveCast.prepare(profile, LearnedCast(), AdaptiveVoices(), "chapter", 0, "[Scene: Mira]\n")
        assertEquals(0, adaptation.next.windows)
        assertEquals(setOf("mira"), SceneCast.plan("[Scene: Mira]\n", adaptation.cast, DialogueState()).next.sceneNames)
    }
    @Test fun importedAutomaticCharacterCanUseItsLearnedVoice() {
        val person = LearnedCharacter("Mira", turns = 4, windows = 3, femaleCues = 3, voice = "learned", engine = "engine")
        val profile = cast.copy(adaptive = true, characters = listOf(CastCharacter("Mira", role = CastRole.AUTO)))
        val prepared = AdaptiveCast.prepare(profile, LearnedCast(windows = 3, people = listOf(person)), AdaptiveVoices(engine = "engine"), "chapter", 0, "Mira said, \"Come along.\"")
        assertEquals("learned", prepared.cast.characters.single().voice)
        assertEquals(CastRole.CUSTOM, prepared.cast.characters.single().role)
    }
    @Test fun learnedVoiceCannotReplaceAnOwnersDedicatedAssignment() {
        val person = LearnedCharacter("Mira", turns = 4, windows = 3, femaleCues = 3, voice = "learned", engine = "engine")
        val prepared = AdaptiveCast.prepare(cast.copy(adaptive = true), LearnedCast(windows = 3, people = listOf(person)), AdaptiveVoices(engine = "engine"), "chapter", 0, "Mira waited.")
        assertEquals("mira", prepared.cast.characters.first { it.name == "Mira" }.voice)
    }
    @Test fun collectionIdentityPreservesAVoiceAcrossCharacterRenames() {
        val old = BookVoiceCast(profileSetId = "personal", characters = listOf(CastCharacter("Old name", voice = "owner", profileId = "char_one")))
        val fresh = listOf(CastCharacter("New name", "New alias", role = CastRole.AUTO, profileId = "char_one"))
        val merged = CharacterLorebook.merge(old, fresh, "personal")
        assertEquals("New name", merged.characters.single().name)
        assertEquals("owner", merged.characters.single().voice)
        assertTrue(merged.characters.single().aliases.contains("Old name"))
    }
    @Test(expected = IllegalArgumentException::class) fun anotherStoriesProfileSetCannotSilentlyReplaceTheCollectionLink() {
        CharacterLorebook.merge(BookVoiceCast(profileSetId = "first"), listOf(CastCharacter("Name")), "other")
    }
    @Test fun largerCastHeadersStayWholeInFastStartAndDoNotSpeakMetadata() {
        val header = "[Scene: ${"Other, ".repeat(70)}Casey]\n"
        val reader = SpeechChunkReader(StringReader(header + "Mira was mentioned offstage."), openingLengths = listOf(360, 720, 1200), paragraphChunks = true)
        val first = requireNotNull(reader.next())
        assertEquals(header, first.text)
        val plan = SceneCast.plan(first.text + requireNotNull(reader.next()).text, cast, DialogueState())
        assertEquals(setOf("casey"), plan.next.sceneNames)
        assertFalse(plan.utterances.joinToString { it.text }.contains("[Scene:"))
    }
    @Test fun voicedCollectionProfilesRetainOwnerOverridesAndBindOnlyAnAppropriateMain() {
        val people = CharacterLorebook.headers(listOf(CharacterLorebook.Header("char_mira", "Mira", keys = listOf("Mira"), voice = "female-one", pronoun = CharacterPronoun.SHE, delivery = CharacterDelivery.LOWER)))
        val profiles = CharacterLorebook.Profiles("personal", people, "engine", "Casey", "male-one", "narrator", CharacterPronoun.HE)
        val added = CharacterLorebook.merge(BookVoiceCast(), profiles)
        assertEquals(CastRole.CUSTOM, added.characters.single().role)
        assertEquals("female-one", added.characters.single().voice)
        assertEquals(CharacterDelivery.LOWER, added.characters.single().delivery)
        assertEquals("Casey", added.mainCharacter); assertEquals("male-one", added.mainVoice)
        val owner = BookVoiceCast(mainCharacter = "Other", mainVoice = "owner-main", narratorVoice = "owner-narrator",
            characters = listOf(CastCharacter("Mira", voice = "owner-voice", profileId = "char_mira")))
        val retained = CharacterLorebook.merge(owner, profiles)
        assertEquals("owner-voice", retained.characters.single().voice)
        assertEquals("Other", retained.mainCharacter); assertEquals("owner-main", retained.mainVoice); assertEquals("owner-narrator", retained.narratorVoice)
    }
}
