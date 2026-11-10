package app.roadleaf.playback

data class SpeechChunk(val offset: Int, val text: String)
object SpeechText {
    fun chunkEnd(text: CharSequence, maxLength: Int): Int {
        if (text.length <= maxLength) return text.length
        var sentence = -1; var space = -1
        for (i in 0 until maxLength) { when (text[i]) { '.', '!', '?', '\n' -> sentence = i; ' ' -> space = i } }
        var end = when { sentence >= maxLength / 2 -> sentence + 1; space >= 0 -> space + 1; else -> maxLength }
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        require(end > 0) { "Chunk size cannot split a Unicode character." }
        return end
    }
    /** Offsets always address the original text, independent of spoken replacements. */
    fun chunks(text: String, maxLength: Int = 1800): List<SpeechChunk> {
        require(maxLength > 0)
        val result = mutableListOf<SpeechChunk>(); var start = 0
        while (start < text.length) {
            val end = start + chunkEnd(text.subSequence(start, minOf(text.length, start + maxLength + 1)), maxLength)
            val part = text.substring(start, end)
            if (part.isNotBlank()) result += SpeechChunk(start, part)
            start = end
        }
        return result
    }
    fun pronounce(text: String, rules: List<Pair<String, String>>): String = pronouncer(rules)(text)
    fun pronouncer(rules: List<Pair<String, String>>): (String) -> String {
        val valid = rules.filter { it.first.isNotBlank() && it.second.isNotBlank() }.sortedByDescending { it.first.length }
        if (valid.isEmpty()) return { it }
        val replacements = valid.associate { it.first.lowercase() to it.second }
        val expression = valid.joinToString("|") { "(?<![\\p{L}\\p{N}_])${Regex.escape(it.first)}(?![\\p{L}\\p{N}_])" }
        val regex = Regex(expression, RegexOption.IGNORE_CASE)
        return { text -> regex.replace(text) { replacements[it.value.lowercase()] ?: it.value } }
    }
}
