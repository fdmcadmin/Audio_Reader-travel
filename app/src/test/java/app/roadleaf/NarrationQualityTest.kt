package app.roadleaf

import app.roadleaf.playback.*
import app.roadleaf.data.Book
import app.roadleaf.data.Chapter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class NarrationQualityTest {
    @Test fun speaksTitleOnceWithoutChapterNumber() {
        val title = "Chapter 26: Chapter 26 - The Quiet Spring"
        assertEquals("Chapter 26: The Quiet Spring", NarrationText.displayTitle(title))
        val spoken = NarrationText.part("$title\n\nHe went down the path.", title, true, true)
        assertEquals("The Quiet Spring\n\nHe went down the path.", spoken)
        assertEquals("", NarrationText.title("Chapter XVI"))
        assertEquals("He read Chapter 26 again.", NarrationText.part("He read Chapter 26 again.", title, false, true))
    }
    @Test fun removesOnlyWebsiteAndNavigationLines() {
        val original = "He went to the spring.\nFreeWebNovel.com\nRead more chapters at freewebnovel.com\n© 2026 FreeWebNovel.com. All rights reserved.\nNext chapter\n\"We should visit the old library,\" he said."
        assertEquals("He went to the spring.\n\"We should visit the old library,\" he said.", NarrationText.part(original, "", false, true))
        assertTrue(NarrationText.part("FreeWebNovel.com", "", false, false).isNotEmpty())
    }
    @Test fun removesInlineWatermarksAndPromotionsWithoutJoiningStoryWords() {
        val text = "He went freewebnovel.com to the spring. This content is taken from FreeWebNovel.com. She waited."
        assertEquals("He went to the spring. She waited.", NarrationText.part(text, "", false, true))
        assertEquals("The map was open. He went home.", NarrationText.part("The map was open. Read the latest chapters at https://freewebnovel.com/novel/example/chapter-1 He went home.", "", false, true))
        assertEquals("He went home.", NarrationText.part("Read more at freewebnovel.com. He went home.", "", false, true))
        assertEquals("A free web novelist wrote the book.", NarrationText.part("A free web novelist wrote the book.", "", false, true))
    }
    @Test fun removesSpacedOrHiddenSiteNamesAndTitleBranding() {
        val text = "He went free\u200Bweb\u200Bnovel.com to the spring.\nFree Web Novel ． com"
        assertEquals("He went to the spring.", NarrationText.part(text, "", false, true))
        assertEquals("A Quiet Road\n\nHe went home.", NarrationText.part("He went home.", "Chapter 8: A Quiet Road | FreeWebNovel.com", true, true))
    }
    @Test fun decorativeWatermarksAreRemovedWithoutNormalizingTheStory() {
        val story = "He wrote x² and kept the ﬂower."
        val branded = "He wrote x² 𝒻𝘳ℯℯ𝑤ℯ𝒷𝘯ℴ𝓋ℯ𝘭.𝑐ℴ𝑚 and kept the ﬂower."
        assertEquals(story, NarrationText.part(branded, "", false, true))
        assertEquals("He went home.", NarrationText.part("Read more at 𝗳𝚛𝚎𝚎𝘄𝕖𝕓𝕟𝕠𝚟𝚎𝕝.𝗰𝕠𝐦. He went home.", "", false, true))
        assertEquals(branded, NarrationText.part(branded, "", false, false))
    }
    @Test fun punctuationRepliesAreUnspokenAndRealQuestionsRemain() {
        assertEquals("", NarrationText.part("???\n***\n\"???\"", "Chapter 2", false, true))
        val result = NarrationText.part("Arlo: ???\n\"Where did he go???\"\nShe waited!!!", "", false, true)
        assertFalse(result.contains("???")); assertFalse(result.contains("!!!"))
        assertTrue(result.contains("Where did he go?")); assertTrue(result.contains("She waited!"))
    }
    @Test fun wholeWordFixesWinBeforeEnglishConventions() {
        val fix = SpeechText.pronouncer(listOf("Dr." to "Doc", "Arlo" to "Wren"))
        assertEquals("Doc Wren went with Mister Wells to Number 2 at Level 4.",
            NarrationText.english(fix("Dr. Arlo went with Mr. Wells to No. 2 at Lv. 4.")))
        assertEquals("he went to the spring", SpeechText.pronounce("he went to the spring", listOf("he" to "he")))
    }
    @Test fun streamingAndUtterancesPreserveShortWordsAndOrder() {
        val text = "He went to the spring. She stood beside him, and he went back for his bag. ".repeat(100)
        val raw = SpeechChunkReader(StringReader(text), openingLengths = listOf(360, 720, 1200)).use { reader ->
            generateSequence { reader.next() }.toList()
        }
        assertEquals(text, raw.joinToString("") { it.text })
        val spoken = raw.flatMap { SpeechUtterances.split(it.text) }.joinToString("")
        assertEquals(text.split(Regex("\\s+")).filter(String::isNotEmpty), spoken.split(Regex("\\s+")).filter(String::isNotEmpty))
        raw.zipWithNext().forEach { (a, b) -> assertFalse(a.text.last().isLetter() && b.text.first().isLetter()) }
    }
    private val cast = BookVoiceCast(true, "Arlo, Arli", "mc", "male", "female", listOf(
        CastCharacter("Mira", "Captain Mira", CastRole.CUSTOM, "mira"), CastCharacter("Bram", role = CastRole.MALE)))
    @Test fun canceledPreparationCannotConsumeOrSkipAStagedSpeechWindow() {
        val book = Book("test", "Original fixture", "Roadleaf", "author-file")
        val chapter = Chapter("one", book.id, 0, "Chapter 1", "")
        val text = "He went to the spring and returned with his bag. ".repeat(100)
        ChapterNarrationQueue(listOf(chapter), mapOf(book.id to book), open = { StringReader(text) }).use { queue ->
            val first = requireNotNull(queue.peek()); val second = requireNotNull(queue.peekFollowing())
            assertFalse(queue.exhausted)
            assertEquals(first, queue.peek()); assertEquals(second, queue.peekFollowing()) // canceled render retried.
            queue.consume(); assertEquals(second, queue.peek())
            val parts = mutableListOf(first)
            while (queue.peek() != null) { parts.add(requireNotNull(queue.peek())); queue.peekFollowing(); queue.consume() }
            assertTrue(queue.exhausted); assertEquals(text, parts.joinToString("") { it.text })
        }
    }
    private fun voice(text: String): String = VoiceCastPlanner(cast).plan(text).utterances.first { it.text.contains('"') || it.text.contains('“') }.voice
    @Test fun confirmedMcKeepsHisOwnVoiceAndAliases() {
        assertEquals("mc", voice("Arlo said, \"I will go.\""))
        assertEquals("mc", voice("\"I will go,\" Arlo whispered."))
        assertEquals("mc", voice("Arli asked, “Are you ready?”"))
    }
    @Test fun explicitGenderTagsUseRolesWithoutNameGuessing() {
        assertEquals("female", voice("\"Wait,\" she said."))
        assertEquals("male", voice("He quietly asked, \"Why?\""))
        assertEquals("", voice("\"Wait.\" Alex crossed the room."))
        assertEquals("", voice("\"Wait,\" they said."))
    }
    @Test fun singleQuotedFemaleDialogueAndContractionsUseTheRightVoice() {
        val text = "Arlo's map was open. 'Don't leave it,' she replied. ‘I won't,’ Arlo answered."
        val plan = VoiceCastPlanner(cast).plan(text)
        assertEquals(text, plan.utterances.joinToString("") { it.text })
        assertEquals("female", plan.utterances.first { "Don't leave it" in it.text }.voice)
        assertEquals("mc", plan.utterances.first { "I won't" in it.text }.voice)
        assertNull(plan.next.closingQuote)
    }
    @Test fun allClearlyFemaleRepliesUseTheFemaleVoiceIncludingSingleQuotes() {
        val text = listOf("\"First,\" she said.", "'Second,' she replied.", "“Third,” she quietly answered.", "‘Fourth,’ she whispered.").joinToString("\n")
        val plan = VoiceCastPlanner(cast).plan(text)
        listOf("First", "Second", "Third", "Fourth").forEach { word -> assertEquals("female", plan.utterances.first { word in it.text }.voice) }
        assertEquals(text, plan.utterances.joinToString("") { it.text })
    }
    @Test fun previousSentenceAndAnExplicitNarratorAssignmentStaySeparate() {
        val text = "Arlo said nothing. \"Wait,\" she answered."
        assertEquals("female", voice(text))
        val narrator = cast.copy(characters = listOf(CastCharacter("Mira", role = CastRole.NARRATOR)))
        assertTrue(VoiceCastPlanner(narrator).plan("Mira said, \"Wait,\" she added.").utterances.all { it.voice.isBlank() })
    }
    @Test fun aKnownSpeakerKeepsTheSameVoiceAcrossAnOpenSingleQuote() {
        val planner = VoiceCastPlanner(cast)
        val first = planner.plan("Arlo said, 'He went ")
        val next = planner.plan("back to the spring,' he added.", first.next)
        assertEquals("mc", next.utterances.first().voice)
        assertNull(next.next.closingQuote)
    }
    @Test fun elidedNarrationDoesNotLeaveAnOpenDialogueQuote() {
        val text = "'Twas a quiet morning. He waited 'til dawn. Arlo said, \"Don't leave.\""
        val plan = VoiceCastPlanner(cast).plan(text)
        assertNull(plan.next.closingQuote)
        assertEquals("mc", plan.utterances.first { "Don't leave" in it.text }.voice)
        assertEquals(text, plan.utterances.joinToString("") { it.text })
    }
    @Test fun namedRecurringVoiceWinsAndActionIsNotSpeechEvidence() {
        assertEquals("mira", voice("\"Wait,\" Captain Mira replied."))
        assertEquals("male", voice("Bram answered, \"I heard it.\""))
        assertEquals("", voice("\"Wait.\" Arlo walked away."))
    }
    @Test fun castConservesDialoguePunctuationAndApostrophes() {
        val text = "Arlo's bag was open. Arlo said, “Don't leave it.” Mira answered, \"I won't.\""
        val plan = VoiceCastPlanner(cast).plan(text)
        assertEquals(text, plan.utterances.joinToString("") { it.text })
        assertEquals(text.takeLast(180), plan.next.lead); assertNull(plan.next.closingQuote)
        assertEquals(text, VoiceCastPlanner(cast.copy(enabled = false)).plan(text).utterances.single().text)
    }
    @Test fun openQuoteAndLeadingSpeechTagSurviveWindowBoundaries() {
        val planner = VoiceCastPlanner(cast)
        val first = planner.plan("Arlo said, \"He went ")
        assertEquals("mc", first.utterances.last().voice)
        val second = planner.plan("to the spring.\" The birds sang.", first.next)
        assertEquals("mc", second.utterances.first().voice)
        assertEquals("to the spring.\" The birds sang.", second.utterances.joinToString("") { it.text })
        val tag = planner.plan("Arlo said, ")
        assertEquals("mc", planner.plan("\"He went.\"", tag.next).utterances.single().voice)
        assertEquals(first, planner.plan("Arlo said, \"He went ")) // planning is transactional, safe to retry after cancellation.
    }
    @Test fun chapterClockNeverRestartsForPrivateSpeechFiles() {
        val clock = ChapterAudioClock(); clock.reset("chapter:one", 5000)
        assertEquals(5000, clock.append("chapter:one", 1800, false).startMs)
        assertNull(clock.duration("chapter:one"))
        assertEquals(6800, clock.append("chapter:one", 2500, true).startMs)
        assertEquals(9300L, clock.duration("chapter:one"))
        assertEquals(0, clock.append("chapter:two", 1200, true).startMs)
        assertEquals(1200L, clock.duration("chapter:two"))
    }
    private fun wave(file: File, rate: Int, frames: Int, channels: Int = 1, sample: Short = 1234): File {
        val bytes = frames * channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + bytes)
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16).put("data".toByteArray()).putInt(bytes)
        val data = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) { data.putShort(sample) }
        file.outputStream().use { it.write(header.array()); it.write(data.array()) }; return file
    }
    @Test fun joiningKeepsEveryPcmSampleAndAddsOnlyTheRequestedBeat() {
        val directory = Files.createTempDirectory("roadleaf-wave").toFile()
        try {
            val a = wave(File(directory, "a.wav"), 8000, 80)
            val b = wave(File(directory, "b.wav"), 8000, 160, sample = -2345)
            val joined = WaveAudio.join(listOf(a, b), directory, gapMs = 10)
            assertEquals(40, WaveAudio.durationMs(joined))
            val bytes = ByteBuffer.wrap(joined.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            repeat(80) { assertEquals(1234, bytes.getShort(44 + it * 2).toInt()) }
            repeat(80) { assertEquals(0, bytes.getShort(44 + (80 + it) * 2).toInt()) }
            repeat(160) { assertEquals(-2345, bytes.getShort(44 + (160 + it) * 2).toInt()) }
            assertEquals(joined, WaveAudio.join(listOf(a, b), directory, gapMs = 10))
            assertEquals(400, WaveAudio.durationMs(WaveAudio.pause(directory)))
        } finally { directory.deleteRecursively() }
    }
    @Test fun mixedVoiceRatesAndStereoAreStreamedWithoutLosingDuration() {
        val directory = Files.createTempDirectory("roadleaf-wave").toFile()
        try {
            val a = wave(File(directory, "a.wav"), 8000, 80)
            val b = wave(File(directory, "b.wav"), 16000, 160, 2, sample = 1234)
            val joined = WaveAudio.join(listOf(a, b), directory, gapMs = 0)
            assertEquals(20, WaveAudio.durationMs(joined)); assertEquals(1, WaveAudio.layout(joined).channels)
            val pcm = ByteBuffer.wrap(joined.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            repeat(160) { assertEquals(1234, pcm.getShort(44 + it * 2).toInt()) }
        } finally { directory.deleteRecursively() }
    }
}
