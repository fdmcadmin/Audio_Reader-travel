package app.roadleaf

import app.roadleaf.playback.*
import org.junit.Assert.*
import org.junit.Test

class NarrationBufferTest {
    @Test fun characterCastingGetsExtraPreparationWhileSingleVoiceAndOwnerModeStayBounded() {
        val cast = BookVoiceCast(mainVoice = "first", femaleVoice = "second")
        assertEquals(3, NarrationBuffer.parts(ResourceMode.ECONOMY, cast))
        assertEquals(3, NarrationBuffer.parts(ResourceMode.ECONOMY, BookVoiceCast(), listOf("first", "second")))
        assertEquals(2, NarrationBuffer.parts(ResourceMode.ECONOMY, cast.copy(enabled = false)))
        assertEquals(2, NarrationBuffer.parts(ResourceMode.ECONOMY, cast.copy(femaleVoice = "first")))
        assertEquals(2, NarrationBuffer.parts(ResourceMode.ECONOMY, BookVoiceCast(), listOf("only", "only")))
        assertEquals(4, NarrationBuffer.parts(ResourceMode.BALANCED, cast))
    }
    @Test fun smallerNeuralRequestsPreserveEveryLinkingWordAndUnicodeCharacter() {
        val text = ("He went to the spring. She followed him along the river, then they returned together. ".repeat(20)) + "Mira’s map showed the way."
        val parts = SpeechUtterances.split(text, 300)
        assertTrue(parts.size > 2 && parts.all { it.length <= 300 })
        assertEquals(text, parts.joinToString(""))
        assertEquals(20, Regex("He went to the spring").findAll(parts.joinToString("")).count())
    }
    @Test fun neuralInputRemovesParagraphFormattingOnlyAfterSpeakerPlanning() {
        val original = "Mira’s map was folded.\n\nHe went\u00A0to the spring.\r\n\tShe followed."
        val prepared = SpeechUtterances.singleParagraph(original)
        assertEquals("Mira’s map was folded. He went to the spring. She followed.", prepared)
        assertEquals(original.filterNot(Char::isWhitespace), prepared.filterNot(Char::isWhitespace))
        assertTrue(original.contains("\n\n")); assertFalse(prepared.contains('\n'))
    }
}
