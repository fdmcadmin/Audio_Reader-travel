package app.roadleaf.browser

/** Where Roadleaf's floating bubble sits over a browser, and whether a drag ended on its close target. */
object BubbleGeometry {
    enum class Side { LEFT, RIGHT }

    /** A screen side and a height as a fraction of the screen, so the spot survives rotation. */
    data class Spot(val side: Side = Side.RIGHT, val height: Float = DEFAULT_HEIGHT)

    const val DEFAULT_HEIGHT = 0.3f

    /** The side nearer to where the bubble was let go. */
    fun side(centerX: Int, screenWidth: Int): Side = if (centerX * 2 < screenWidth) Side.LEFT else Side.RIGHT

    /** The bubble's top edge for [spot], kept fully on screen. */
    fun top(spot: Spot, screenHeight: Int, size: Int, margin: Int): Int {
        val lowest = (screenHeight - size - margin).coerceAtLeast(margin)
        val wanted = if (spot.height.isFinite()) (spot.height * screenHeight).toInt() else (DEFAULT_HEIGHT * screenHeight).toInt()
        return wanted.coerceIn(margin, lowest)
    }

    /** The fraction stored for a bubble whose top edge is at [top]. */
    fun height(top: Int, screenHeight: Int): Float =
        if (screenHeight <= 0) DEFAULT_HEIGHT else (top.toFloat() / screenHeight).coerceIn(0f, 1f)

    /** True when the bubble's centre is within [radius] of the close target, centred [fromBottom] above the bottom edge. */
    fun overClose(centerX: Int, centerY: Int, screenWidth: Int, screenHeight: Int, fromBottom: Int, radius: Int): Boolean {
        val dx = (centerX - screenWidth / 2).toLong()
        val dy = (centerY - (screenHeight - fromBottom)).toLong()
        return dx * dx + dy * dy <= radius.toLong() * radius
    }
}
