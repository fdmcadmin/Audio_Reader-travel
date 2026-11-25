package app.roadleaf.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class SupertonicWeightedPackTest {
    private fun parse(vararg rows: String) = SupertonicWeightedPack.parse(
        ("roadleaf-supertonic-weighted-v1\n" + rows.joinToString("\n")).toByteArray()
    )

    @Test fun validatesNamesAnchorsAndWeights() {
        val pack = parse("M-Weighted-01\tM3=90,M1=10", "F-Weighted-01\tF4=90,F5=10")
        assertEquals(listOf(7, 5), pack.recipes.first().sources.map { it.index })
        assertEquals("F-Weighted-01", SupertonicWeightedPack.label(pack.recipes.last().id))
        assertThrows(IllegalArgumentException::class.java) { parse("M-Weighted-01\tM3=90,F1=10") }
        assertThrows(IllegalArgumentException::class.java) { parse("F-Weighted-01\tF3=90,F3=10") }
        assertThrows(IllegalArgumentException::class.java) { parse("F-Weighted-01\tF3=70,F2=25") }
        assertThrows(IllegalArgumentException::class.java) {
            parse("M-Weighted-01\tM3=90,M1=10", "M-Weighted-01\tM3=90,M2=10")
        }
    }

    @Test fun mergesBothChannelsWithoutRenumberingExistingSpeakers() {
        val stock = ByteBuffer.allocate(SupertonicVoiceIndex.tableSize(11).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        longArrayOf(11, 50, 256, 11, 8, 16).forEach(stock::putLong)
        for ((height, width) in listOf(50 to 256, 8 to 16)) {
            repeat(11) { voice ->
                repeat(height) {
                    repeat(width) { column ->
                        stock.putFloat(if (column == voice) voice + 2f else 0f)
                    }
                }
            }
        }
        val pack = parse("F-Weighted-01\tF1=90,F2=10")
        val merged = ByteBuffer.wrap(pack.merge(stock.array(), 11)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12L, merged.long)
        assertEquals(12L, merged.getLong(24))
        val scale = 2.1 / sqrt(1.8 * 1.8 + 0.3 * 0.3)
        for ((start, height, width) in listOf(Triple(48, 50, 256), Triple(48 + 12 * 50 * 256 * 4, 8, 16))) {
            val lastOriginal = start + 10 * height * width * 4
            assertEquals(12f, merged.getFloat(lastOriginal + 10 * 4), 0f)
            val added = start + 11 * height * width * 4
            assertEquals((1.8 * scale).toFloat(), merged.getFloat(added), 1e-6f)
            assertEquals((0.3 * scale).toFloat(), merged.getFloat(added + 4), 1e-6f)
        }
    }
}
