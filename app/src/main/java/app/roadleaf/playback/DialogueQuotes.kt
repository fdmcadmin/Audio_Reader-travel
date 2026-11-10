package app.roadleaf.playback

/** Shared quote boundaries for attribution and learning; apostrophes inside words are not dialogue. */
internal object DialogueQuotes {
    data class Span(val start: Int, val end: Int)
    private val elision = Regex("^['‘’](?:tis|twas|twere|em|til|cause|bout|round|n)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    private fun escaped(text: String, index: Int): Boolean {
        var before = index - 1
        while (before >= 0 && text[before] == '\\') before--
        return (index - before - 1) % 2 != 0
    }
    fun opening(text: String, index: Int): Char? {
        if (escaped(text, index)) return null
        return when (text[index]) {
            '"' -> '"'
            '“' -> '”'
            '‘', '’', '\'' -> if ((index == 0 || !text[index - 1].isLetterOrDigit()) && !elision.containsMatchIn(text.substring(index, (index + 12).coerceAtMost(text.length))) &&
                index + 1 < text.length && !text[index + 1].isWhitespace()) {
                if (text[index] in listOf('‘', '’')) '’' else '\''
            } else null
            else -> null
        }
    }
    fun closes(text: String, index: Int, closing: Char): Boolean = text[index] == closing && !escaped(text, index) &&
        (closing !in listOf('\'', '’') || index + 1 == text.length || !text[index + 1].isLetterOrDigit())

    fun mask(text: String, initial: Char? = null): String {
        var closing = initial
        return buildString(text.length) {
            text.forEachIndexed { index, char ->
                if (closing == null) {
                    closing = opening(text, index)
                    append(if (closing == null) char else ' ')
                } else {
                    if (closes(text, index, closing!!)) closing = null
                    append(if (char == '\n') '\n' else ' ')
                }
            }
        }
    }
    fun spans(text: String, initial: Char? = null): List<Span> {
        var closing = initial
        var start = -1
        return buildList {
            text.forEachIndexed { index, _ ->
                if (closing == null) {
                    closing = opening(text, index)
                    if (closing != null) start = index
                } else if (closes(text, index, closing!!)) {
                    add(Span(start, index)); closing = null
                }
            }
        }
    }
}
