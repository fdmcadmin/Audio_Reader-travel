package app.roadleaf

import app.roadleaf.data.*
import app.roadleaf.playback.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Reader
import java.io.StringReader

class PlaybackEfficiencyTest {
    private class CountingReader(private val value: String) : Reader() {
        var reads = 0
        var closed = false
        override fun read(target: CharArray, offset: Int, length: Int): Int {
            if (reads >= value.length) return -1
            val count = minOf(length, value.length - reads)
            value.toCharArray(target, offset, reads, reads + count); reads += count
            return count
        }
        override fun close() { closed = true }
    }
    @Test fun firstSpeechWindowDoesNotReadAnEntireLongChapter() {
        val input = CountingReader("A very long chapter. ".repeat(100_000))
        SpeechChunkReader(input).use { reader -> assertNotNull(reader.next()); assertTrue(input.reads <= 1801) }
        assertTrue(input.closed)
    }
    @Test fun streamedTextMatchesOriginalBoundariesAndUnicode() {
        val text = "A dragon waits. 🐉 Éowyn speaks! ".repeat(200)
        val actual = buildList { SpeechChunkReader(StringReader(text), maxLength = 60).use { reader -> while (true) add(reader.next() ?: break) } }
        assertEquals(SpeechText.chunks(text, 60), actual)
        assertEquals(text, actual.joinToString("") { it.text })
        actual.forEach { assertFalse(Character.isHighSurrogate(it.text.last())); assertFalse(Character.isLowSurrogate(it.text.first())) }
    }
    @Test fun resumeBeginsAtSavedOriginalTextOffset() {
        val text = "Names change, original text offsets stay stable. ".repeat(80)
        val saved = SpeechText.chunks(text)[1].offset
        SpeechChunkReader(StringReader(text), saved).use { reader ->
            val first = requireNotNull(reader.next())
            assertEquals(saved, first.offset); assertEquals(text.substring(saved, saved + first.text.length), first.text)
        }
    }
    @Test fun fastOpeningRampsUpWithoutLosingTextOrBreakingUnicode() {
        val text = "A traveller reads the map. 🐉 The river leads toward home. ".repeat(250)
        val input = CountingReader(text)
        val chunks = buildList {
            SpeechChunkReader(input, openingLengths = listOf(360, 720, 1200)).use { reader ->
                add(requireNotNull(reader.next())); assertTrue(input.reads <= 361)
                while (true) add(reader.next() ?: break)
            }
        }
        assertTrue(chunks[0].text.length <= 360); assertTrue(chunks[1].text.length <= 720)
        assertTrue(chunks[2].text.length <= 1200); assertTrue(chunks.drop(3).any { it.text.length > 1200 })
        assertEquals(text, chunks.joinToString("") { it.text })
        chunks.forEach { assertEquals(it.text, text.substring(it.offset, it.offset + it.text.length)); assertFalse(Character.isHighSurrogate(it.text.last())) }
    }
    @Test fun fastResumeRestoresExactRecordedAudioSpanBeforeContinuing() {
        val text = "First, a traveller unfolds her map. Then she chooses the quiet road home. ".repeat(100)
        val book = Book("b", "Example", "Author", "author-file")
        val chapter = Chapter("c", "b", 0, "Chapter", "")
        val original = ChapterNarrationQueue(listOf(chapter), mapOf("b" to book), fastStart = true, open = { StringReader(text) }).use { requireNotNull(it.peek()) }
        val saved = Progress("b", "c", original.offset, 3500)
        ChapterNarrationQueue(listOf(chapter), mapOf("b" to book), saved, fastStart = true, resumeChars = original.text.length, open = { StringReader(text) }).use { queue ->
            assertEquals(original, queue.peek()); assertSame(queue.peek(), queue.peek())
            val rebuilt = StringBuilder()
            while (true) { val part = queue.peek() ?: break; rebuilt.append(part.text); queue.consume() }
            assertEquals(text, rebuilt.toString())
        }
    }
    @Test fun legacyWebResumeKeepsItsOriginalLongSegmentForSavedAudioMilliseconds() {
        val text = "The old reader saved a position inside this original passage. It must not skip ahead after an update. ".repeat(100)
        val book = Book("b", "Example", "Author", "author-file")
        val chapter = Chapter("c", "b", 0, "Chapter", "")
        val legacy = SpeechText.chunks(text)[1]
        ChapterNarrationQueue(listOf(chapter), mapOf("b" to book), Progress("b", "c", legacy.offset, 65_000), fastStart = true, open = { StringReader(text) }).use { queue ->
            assertEquals(legacy.text, queue.peek()?.text); assertEquals(legacy.offset, queue.peek()?.offset)
        }
    }
    @Test fun aStoryStartsWithAShortOpeningOnlyInItsFirstChapter() {
        val paragraph = "A traveller unfolds her map beside the river and chooses the quiet road home. ".repeat(12) + "\n"
        val text = paragraph.repeat(3)
        val book = Book("b", "Example", "Author", "author-file")
        val chapters = listOf(Chapter("one", "b", 0, "First", ""), Chapter("two", "b", 1, "Second", ""))
        ChapterNarrationQueue(chapters, mapOf("b" to book), paragraphChunks = true, quickOpening = true, open = { StringReader(text) }).use { queue ->
            val parts = buildList { while (true) { val part = queue.peek() ?: break; add(part); queue.consume() } }
            val first = parts.filter { it.chapter.id == "one" }
            val second = parts.filter { it.chapter.id == "two" }
            // The first passage is short and ends at a sentence; nothing is skipped or repeated.
            assertTrue(first[0].text.length <= 360 && first[0].text.trimEnd().endsWith("."))
            assertEquals(text, first.joinToString("") { it.text })
            // Later chapters are prepared ahead while the first one plays, so they keep whole paragraphs.
            assertEquals(paragraph, second[0].text)
            assertEquals(text, second.joinToString("") { it.text })
        }
        // Without it (an exact resume), the first passage is a whole paragraph, as before.
        ChapterNarrationQueue(chapters, mapOf("b" to book), paragraphChunks = true, open = { StringReader(text) }).use { assertEquals(paragraph, it.peek()?.text) }
    }
    @Test fun queueOnlyOpensCurrentChapterAndPeekSurvivesAbandonedRender() {
        val book = Book("book", "Example", "Author", "author-file")
        val chapters = (0..20).map { Chapter("c$it", book.id, it, "Chapter $it", "unused") }
        val opened = mutableListOf<CountingReader>()
        ChapterNarrationQueue(chapters, mapOf(book.id to book), open = { CountingReader("A chapter. ".repeat(1000)).also(opened::add) }).use { queue ->
            val part = requireNotNull(queue.peek())
            assertSame(part, queue.peek()); assertEquals(1, opened.size)
            queue.consume(); assertNotEquals(part.offset, queue.peek()?.offset)
        }
        assertTrue(opened.single().closed)
    }
    @Test fun queueClosesFinishedReadersAndPreservesChapterOrder() {
        val book = Book("b", "Example", "Author", "author-file")
        val chapters = listOf(Chapter("two", "b", 1, "Second", ""), Chapter("one", "b", 0, "First", ""))
        val readers = mutableListOf<CountingReader>()
        ChapterNarrationQueue(chapters, mapOf("b" to book), open = { CountingReader(it.title).also(readers::add) }).use { queue ->
            assertEquals("two", queue.peek()?.chapter?.id); queue.consume()
            assertEquals("one", queue.peek()?.chapter?.id); assertTrue(readers.first().closed); queue.consume()
            assertNull(queue.peek()); assertTrue(queue.exhausted)
        }
        assertTrue(readers.all { it.closed })
    }
    @Test fun changingVoiceEnginePitchOrTextCannotReuseWrongAudio() {
        val first = AudioCacheKey.create("engine", "voice", 1f, "hello")
        assertEquals(first, AudioCacheKey.create("engine", "voice", 1f, "hello"))
        assertEquals(5, setOf(first, AudioCacheKey.create("other", "voice", 1f, "hello"), AudioCacheKey.create("engine", "other", 1f, "hello"), AudioCacheKey.create("engine", "voice", 0.8f, "hello"), AudioCacheKey.create("engine", "voice", 1f, "goodbye")).size)
    }
    @Test fun invalidStoredTuningCannotReachSpeechEngine() {
        val safe = ListeningSettings(pitch = Float.NaN, speed = Float.POSITIVE_INFINITY).normalized()
        assertEquals(1f, safe.pitch); assertEquals(1f, safe.speed)
        assertEquals(0.7f, ListeningSettings(pitch = -10f).normalized().pitch)
        assertEquals(2f, ListeningSettings(speed = 200f).normalized().speed)
    }
}
