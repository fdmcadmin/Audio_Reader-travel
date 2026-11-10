package app.roadleaf.playback

import java.io.Reader

/** Original source offsets, never an estimate based on speech duration or generated wording. */
object ParagraphResume {
    fun start(text: String, offset: Int): Int {
        var position = offset.coerceIn(0, text.length)
        if (position == text.length) while (position > 0 && text[position - 1].isWhitespace()) position--
        val at = text.lastIndexOf('\n', position - 1) + 1
        var next = at; while (next < position && text[next].isWhitespace()) next++
        return next
    }
    fun start(reader: Reader, offset: Int): Int {
        var at = 0; var beginning = 0; var indentation = true; var lastContent = 0
        val buffer = CharArray(4096)
        while (at < offset) {
            val count = reader.read(buffer, 0, minOf(buffer.size, offset - at)); if (count < 0) break
            for (i in 0 until count) {
                val char = buffer[i]; at++
                if (char == '\n') { beginning = at; indentation = true }
                else if (indentation && char.isWhitespace()) beginning = at else { indentation = false; if (!char.isWhitespace()) lastContent = beginning }
            }
        }
        return if (at < offset || reader.read() < 0) lastContent else beginning
    }
    data class Relocated(val offset: Int, val matched: Boolean)
    fun relocate(old: String, offset: Int, updated: String): Relocated {
        val at = start(old, offset)
        val paragraph = old.substring(at, old.indexOf('\n', at).takeIf { it >= 0 } ?: old.length).trim()
        if (paragraph.isBlank()) return Relocated(0, false)
        val matches = Regex("(?m)^[ \\t]*${Regex.escape(paragraph)}[ \\t]*$").findAll(updated).take(2).toList()
        if (matches.size == 1) return Relocated(start(updated, matches.single().range.first), true)
        // Unchanged prefixes are safe even if a generic paragraph appears more than once.
        if (updated.startsWith(old.take(at + paragraph.length))) return Relocated(at, true)
        return Relocated(0, false)
    }
}
