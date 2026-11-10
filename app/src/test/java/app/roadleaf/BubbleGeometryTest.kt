package app.roadleaf

import app.roadleaf.browser.BubbleGeometry
import app.roadleaf.browser.BubbleGeometry.Side
import app.roadleaf.browser.BubbleGeometry.Spot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleGeometryTest {
    private val width = 1080
    private val height = 2400
    private val size = 154
    private val margin = 16

    @Test fun aDroppedBubbleSettlesOnTheNearerSide() {
        assertEquals(Side.LEFT, BubbleGeometry.side(100, width))
        assertEquals(Side.LEFT, BubbleGeometry.side(539, width))
        assertEquals(Side.RIGHT, BubbleGeometry.side(540, width))
        assertEquals(Side.RIGHT, BubbleGeometry.side(1000, width))
    }

    @Test fun theBubbleStaysOnScreenAtAnyStoredHeight() {
        assertEquals(720, BubbleGeometry.top(Spot(Side.RIGHT, 0.3f), height, size, margin))
        assertEquals(margin, BubbleGeometry.top(Spot(Side.LEFT, 0f), height, size, margin))
        assertEquals(height - size - margin, BubbleGeometry.top(Spot(Side.LEFT, 1f), height, size, margin))
        assertEquals(720, BubbleGeometry.top(Spot(Side.LEFT, Float.NaN), height, size, margin))
        // A screen smaller than the bubble still gives a usable spot.
        assertEquals(margin, BubbleGeometry.top(Spot(), 100, size, margin))
    }

    @Test fun aStoredHeightSurvivesRotation() {
        val stored = BubbleGeometry.height(1200, height)
        assertEquals(0.5f, stored, 0.0001f)
        // The same spot on a landscape screen keeps its place relative to the screen.
        assertEquals(540, BubbleGeometry.top(Spot(Side.RIGHT, stored), 1080, size, margin))
        assertEquals(BubbleGeometry.DEFAULT_HEIGHT, BubbleGeometry.height(10, 0), 0f)
        assertEquals(1f, BubbleGeometry.height(5000, height), 0f)
    }

    @Test fun onlyADropOnTheCloseTargetCloses() {
        val fromBottom = 240
        val radius = 150
        val targetY = height - fromBottom
        assertTrue(BubbleGeometry.overClose(width / 2, targetY, width, height, fromBottom, radius))
        assertTrue(BubbleGeometry.overClose(width / 2 + 100, targetY + 100, width, height, fromBottom, radius))
        assertFalse(BubbleGeometry.overClose(width / 2 + 120, targetY + 120, width, height, fromBottom, radius))
        assertFalse(BubbleGeometry.overClose(width - 80, targetY, width, height, fromBottom, radius))
        assertFalse(BubbleGeometry.overClose(width / 2, height / 2, width, height, fromBottom, radius))
    }
}
