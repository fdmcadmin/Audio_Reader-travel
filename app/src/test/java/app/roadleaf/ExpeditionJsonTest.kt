package app.roadleaf

import app.roadleaf.importing.ExpeditionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpeditionJsonTest {
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
