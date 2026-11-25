package app.roadleaf

import app.roadleaf.playback.AsuVoiceBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsuPairingTest {
    private val voices = (0..160).map { "fixture-$it" }

    private fun recipe(vararg indices: Int): String {
        val family = if (indices.first() < 80) 'M' else 'F'
        return "roadleaf-asu:$family:" + indices.joinToString("~") { voices[it] }
    }

    @Test fun pairBanAppliesInBothOrdersAndAllSlotsWithoutMakingSavedRecipesUnplayable() {
        val original = voices.indices.map { i -> recipe(i, (i + 1) % 161, (i + 3) % 161, (i + 7) % 161) }
        val fixed = original.toMutableList().apply { this[0] = recipe(0, 11, 3, 14) }
        val bank = AsuVoiceBank(original, fixed, listOf(voices[0] to voices[1], voices[3] to voices[7]))
        assertEquals(161, bank.starters.size)
        assertTrue(bank.accepts(original[0]))
        assertFalse(bank.allowsNewRecipe(original[0]))
        assertFalse(bank.allowsNewRecipe(recipe(1, 0, 11, 14)))
        assertFalse(bank.allowsNewRecipe(recipe(0, 11, 3, 7)))
        assertFalse(bank.allowsNewRecipe(recipe(0, 11, 1, 14)))
        assertTrue(bank.allowsNewRecipe(fixed[0]))
        assertNotEquals(fixed[0], bank.next('M', setOf(original[0])))
        val used = fixed.toMutableSet()
        repeat(32) { index ->
            val next = bank.next(if (index % 2 == 0) 'M' else 'F', used)
            assertTrue(bank.allowsNewRecipe(next))
            assertTrue(used.add(next))
        }
    }
}
