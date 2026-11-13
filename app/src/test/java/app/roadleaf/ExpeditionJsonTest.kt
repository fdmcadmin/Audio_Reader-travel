package app.roadleaf

import app.roadleaf.importing.ExpeditionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
