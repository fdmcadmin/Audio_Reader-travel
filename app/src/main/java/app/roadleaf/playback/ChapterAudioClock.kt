package app.roadleaf.playback

/** Timing for a chapter assembled incrementally; audio files and text remain bounded. */
class ChapterAudioClock {
    data class Span(val startMs: Long, val durationMs: Long)
    private var chapter: String? = null
    private var endMs = 0L
    private val totals = mutableMapOf<String, Long>()
    fun reset(firstChapter: String? = null, startMs: Long = 0) { chapter = firstChapter; endMs = startMs.coerceAtLeast(0); totals.clear() }
    fun append(key: String, durationMs: Long, last: Boolean): Span {
        require(durationMs > 0)
        if (chapter != key) { chapter = key; endMs = 0 }
        val span = Span(endMs, durationMs)
        endMs += durationMs
        if (last) totals[key] = endMs
        return span
    }
    fun duration(key: String): Long? = totals[key]
}
