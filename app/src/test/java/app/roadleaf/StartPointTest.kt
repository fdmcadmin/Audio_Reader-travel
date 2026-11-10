package app.roadleaf

import app.roadleaf.playback.StartPoint
import app.roadleaf.sources.WebSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartPointTest {
    private val text = "The ferry left at dawn.\n\nBy noon the island was a green line, and the gulls had stopped. Don't wait, she said."

    @Test fun tappingInsideAWordSelectsTheWholeWord() {
        val island = text.indexOf("island")
        assertEquals(island until island + 6, StartPoint.word(text, island + 3))
        val dont = text.indexOf("Don't")
        assertEquals(dont until dont + 5, StartPoint.word(text, dont + 4))
    }

    @Test fun tappingPunctuationOrSpacePicksTheNearestWord() {
        val line = text.indexOf("line,")
        assertEquals(line until line + 4, StartPoint.word(text, line + 4))
        val and = text.indexOf(", and") + 2
        assertEquals(and until and + 3, StartPoint.word(text, and - 1))
        assertNull(StartPoint.word("   ...   ", 2))
        assertNull(StartPoint.word("", 0))
    }

    @Test fun paragraphsKeepAbsoluteOffsetsAndSkipBlankLines() {
        val parts = StartPoint.paragraphs(text)
        assertEquals(listOf(0, text.indexOf("By noon")), parts.map { it.first })
        parts.forEach { (start, line) -> assertEquals(line, text.substring(start, start + line.length)) }
        assertEquals(1, StartPoint.paragraphAt(parts, text.indexOf("gulls")))
        assertEquals(0, StartPoint.paragraphAt(parts, 0))
    }

    @Test fun longLinesSplitAtSpacesWithoutLosingText() {
        val long = (1..400).joinToString(" ") { "word$it" }
        val parts = StartPoint.paragraphs(long, piece = 100)
        assertTrue(parts.size > 1 && parts.all { it.second.length <= 100 })
        assertEquals(long, parts.joinToString("") { it.second })
        parts.forEach { (start, line) -> assertEquals(line, long.substring(start, start + line.length)) }
    }

    @Test fun veryLongChaptersShowAWindowAroundThePlace() {
        assertEquals(0 until 50, StartPoint.window(50, 10))
        val window = StartPoint.window(1_000_000, 900_000, limit = 300_000)
        assertTrue(900_000 in window && window.count() == 300_000)
        assertTrue(0 in StartPoint.window(1_000_000, 0, limit = 300_000))
    }

    @Test fun previewShowsTheFirstWords() {
        assertEquals("gulls had stopped. Don't wait,", StartPoint.preview(text, text.indexOf("gulls"), words = 5))
    }

    @Test fun aSelectedWordIsFoundInTheChapterText() {
        val page = "The road was long. The road was dark.\nShe took the road home."
        val first = page.indexOf("road")
        val second = page.indexOf("road", first + 1)
        val third = page.indexOf("road", second + 1)
        assertEquals(second, WebSelection.locate(page, "road", "The road was long. The ", " was dark."))
        assertEquals(third, WebSelection.locate(page, "road", "She took the ", " home."))
        assertEquals(first, WebSelection.locate(page, "road", "The ", " was long. The road"))
        assertEquals(second, WebSelection.locate(page, "Road", "The  road was\nlong.   The ", " was   dark."))
        assertEquals(page.indexOf("dark"), WebSelection.locate(page, "dark", "", ""))
        assertEquals(third, WebSelection.locate(page, "“road", "She took the", " home."))
        assertNull(WebSelection.locate(page, "lantern", "", ""))
        assertNull(WebSelection.locate(page, "  ... ", "", ""))
    }
}
