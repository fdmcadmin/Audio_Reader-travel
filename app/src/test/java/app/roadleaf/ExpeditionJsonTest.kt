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
}
