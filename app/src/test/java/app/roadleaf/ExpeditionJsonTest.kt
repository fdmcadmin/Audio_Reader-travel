package app.roadleaf

import app.roadleaf.importing.ExpeditionJson
import app.roadleaf.playback.AdaptiveCast
import app.roadleaf.playback.AdaptiveVoices
import app.roadleaf.playback.BookVoiceCast
import app.roadleaf.playback.CastCharacter
import app.roadleaf.playback.CharacterPronoun
import app.roadleaf.playback.DialogueState
import app.roadleaf.playback.HayaiVoices
import app.roadleaf.playback.LearnedCast
import app.roadleaf.playback.SceneCast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpeditionJsonTest {
    @Test fun paragraphMetadataKeepsDialogueAndNarrationSeparate() {
        val paragraphs = listOf(
            "She turned. “Stay here.” Mira tapped the display. “I’ll be back.”",
            "I hope the lights stay on.",
            "The lights flickered again."
        )
        val attributions = listOf(
            ExpeditionJson.ParagraphAttribution(listOf("Narrator", "Mira"), "dialogue"),
            ExpeditionJson.ParagraphAttribution(listOf("Mira"), "thought"),
            ExpeditionJson.ParagraphAttribution(listOf("Narrator"), "narration")
        )
        val cast = BookVoiceCast(narratorVoice = "default", femaleNarratorVoice = "female-narrator",
            maleNarratorVoice = "male-narrator", characters = listOf(
                CastCharacter("Mira", voice = "mira", pronoun = CharacterPronoun.SHE),
                CastCharacter("Bram", voice = "bram", pronoun = CharacterPronoun.HE)))
        var state = DialogueState(sceneViewpoint = "bram")
        val voices = paragraphs.zip(attributions).map { (text, metadata) ->
            val plan = SceneCast.plan(text, cast, state, explicitSpeakers = metadata.quoteSpeakers,
                thoughtSpeaker = metadata.thoughtSpeaker)
            assertEquals(text, plan.utterances.joinToString("") { it.text })
            state = plan.next
            plan.utterances.map { it.voice }
        }
        assertEquals(listOf(
            listOf("male-narrator", "mira", "male-narrator", "mira"),
            listOf("female-narrator"), listOf("male-narrator")
        ), voices)
        assertEquals(listOf("Mira"), attributions.first().copy(mode = "").quoteSpeakers)
    }

    @Test fun namedSupportingDialogueCanUseAnUnusedPiperVoiceOnItsFirstLine() {
        val model = "vits-piper-en_US-libritts-high"
        val narrator = "$model#2#speaker_2"
        val supporting = "$model#3#speaker_3"
        val cast = BookVoiceCast(exclusiveVoices = true, narratorVoice = narrator, maleNarratorVoice = narrator)
        val voices = AdaptiveVoices(engine = HayaiVoices.ENGINE, numberedPiper = listOf(narrator, supporting))
        val attribution = ExpeditionJson.ParagraphAttribution(listOf("Narrator", "Guard 1"), "dialogue")
        val text = "“You can go ahead.”"
        val adaptation = AdaptiveCast.prepare(cast, LearnedCast(), voices, "chapter", 0, text,
            explicitSpeakers = attribution.dialogueSpeakers)
        val plan = SceneCast.plan(text, adaptation.cast, DialogueState(), explicitSpeakers = attribution.quoteSpeakers)
        assertEquals(supporting, plan.utterances.single().voice)
    }

    @Test fun advancesOnlyThroughConsecutiveValidatedChapterNames() {
        assertEquals(listOf(1, 2), ExpeditionJson.contiguousNumbers(null, listOf(2, 4, 1)))
        assertEquals(listOf(3, 4), ExpeditionJson.contiguousNumbers(2, listOf(4, 3, 6)))
        assertEquals(emptyList<Int>(), ExpeditionJson.contiguousNumbers(2, listOf(4, 5)))
        assertEquals(emptyList<Int>(), ExpeditionJson.contiguousNumbers(null, listOf(2, 3)))
        assertEquals(3, ExpeditionJson.chapterNumber("Meridian Hunt - Chapter 3 - Crossing.json"))
        assertNull(ExpeditionJson.chapterNumber("NEXT.json"))
        assertNull(ExpeditionJson.chapterNumber("Meridian Hunt - Chapter 3 - Crossing.txt"))
    }
    @Test fun legacyFirstChapterReadsTitleOnceAndAllowsJsonSuccessors() {
        val name = "Meridian Hunt - Chapter 1 - Operation Loose Thread.txt"
        val source = "THE MERIDIAN HUNT\nChapter One – Operation Loose Thread\n\n" + "The crew entered the freighter. ".repeat(4)
        val chapter = ExpeditionJson.parseLegacyFirst(name, source)
        assertEquals(1, chapter.number)
        assertEquals("Operation Loose Thread", chapter.title)
        assertTrue(chapter.text.startsWith("Operation Loose Thread\n\nThe crew"))
        assertTrue("Chapter One" !in chapter.text)
        assertEquals(listOf(1, 2, 3), ExpeditionJson.contiguousNumbers(null, listOf(chapter.number, 2, 3)))
        assertTrue(runCatching { ExpeditionJson.parseLegacyFirst("The Pirate Expedition.txt", source) }.isFailure)
    }
    @Test fun partsHaveStableIdsAndPlayInChapterOrder() {
        val keys = ExpeditionJson.orderedKeys(listOf(1 to null, 2 to null, 3 to null, 4 to null,
            5 to null, 5 to 2, 5 to 1, 6 to null))
        assertEquals(listOf(1 to null, 2 to null, 3 to null, 4 to null, 5 to 1, 5 to 2, 6 to null), keys)
        assertEquals("book:5:part:1", ExpeditionJson.chapterId("book", 5 to 1))
        assertEquals(5 to 1, ExpeditionJson.keyFromId("book", "book:5:part:1"))
        assertEquals("One", ExpeditionJson.partLabel(1))
        assertEquals("book:5", ExpeditionJson.chapterId("book", 5 to null))
        assertEquals(5, ExpeditionJson.chapterNumber("Meridian Hunt - Chapter 5 Part 1 - The Crossing.json"))
        assertEquals(1, ExpeditionJson.chapterPart("Meridian Hunt - Chapter 5 Part 1 - The Crossing.json"))
    }
    @Test fun missingPartStopsThatChapterWithoutSkippingAhead() {
        assertEquals(listOf(1 to null, 2 to null, 3 to 1), ExpeditionJson.orderedKeys(
            listOf(1 to null, 2 to null, 3 to 1, 3 to 3, 4 to null)))
        assertEquals(emptyList<Pair<Int, Int?>>(), ExpeditionJson.orderedKeys(listOf(2 to null, 3 to null)))
        assertEquals(listOf(1 to null, 2 to null, 3 to null), ExpeditionJson.orderedKeys(
            listOf(1 to null, 2 to null, 3 to null, 3 to 2)))
    }
    @Test fun correctedTextKeepsTheCurrentParagraphOrItsNumber() {
        val before = "Title\n\nFirst paragraph.\n\nSecond paragraph.\n\nThird paragraph."
        val after = "New title\n\nFirst paragraph revised.\n\nSecond paragraph.\n\nThird paragraph."
        assertEquals(after.indexOf("Second paragraph."), ExpeditionJson.relocatePosition(before, before.indexOf("Second paragraph."), after))
        assertEquals(after.indexOf("First paragraph revised."), ExpeditionJson.relocatePosition(before, before.indexOf("First paragraph."), after))
    }
}
