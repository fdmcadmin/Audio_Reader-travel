package app.roadleaf.playback

/**
 * Choosing where reading starts inside one chapter. Offsets index the stored chapter text, the same
 * offsets the narration queue and saved progress use, so a chosen word starts speech exactly there.
 */
object StartPoint {
    private fun letter(c: Char) = c.isLetterOrDigit()
    private fun joiner(c: Char) = c == '\'' || c == '’' || c == '-'

    /** The whole word at [offset], or the next word close after it; null when there is no word nearby. */
    fun word(text: String, offset: Int): IntRange? {
        if (text.isEmpty()) return null
        var at = offset.coerceIn(0, text.lastIndex)
        if (!letter(text[at])) {
            at = if (at > 0 && letter(text[at - 1])) at - 1
                else (at until minOf(text.length, at + 40)).firstOrNull { letter(text[it]) } ?: return null
        }
        var start = at
        while (start > 0 && (letter(text[start - 1]) || start > 1 && joiner(text[start - 1]) && letter(text[start - 2]))) start--
        var end = at
        while (end + 1 < text.length && (letter(text[end + 1]) || end + 2 < text.length && joiner(text[end + 1]) && letter(text[end + 2]))) end++
        return start..end
    }

    /** Displayable paragraphs with their start offsets. Very long lines are split at spaces. */
    fun paragraphs(text: String, piece: Int = 1500): List<Pair<Int, String>> {
        require(piece >= 20)
        val result = mutableListOf<Pair<Int, String>>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            var start = lineStart
            while (start < newline) {
                var end = minOf(newline, start + piece)
                if (end < newline) { val space = text.lastIndexOf(' ', end - 1); if (space > start) end = space + 1 }
                val part = text.substring(start, end)
                if (part.isNotBlank()) result += start to part.trimEnd('\r')
                start = end
            }
            lineStart = newline + 1
        }
        return result
    }

    /** Index of the paragraph that contains [offset], for opening the picker where reading is now. */
    fun paragraphAt(paragraphs: List<Pair<Int, String>>, offset: Int): Int =
        paragraphs.indexOfLast { it.first <= offset }.coerceAtLeast(0)

    /** The first few words from [start], shown before confirming. */
    fun preview(text: String, start: Int, words: Int = 8): String =
        text.substring(start.coerceIn(0, text.length)).trimStart().split(Regex("\\s+")).take(words).joinToString(" ").take(90)

    /** A window of very long text around [offset], so the picker stays light. Offsets stay absolute. */
    fun window(length: Int, offset: Int, limit: Int = 300_000): IntRange {
        if (length <= limit) return 0 until length
        val start = (offset - limit / 3).coerceIn(0, length - limit)
        return start until start + limit
    }
}
