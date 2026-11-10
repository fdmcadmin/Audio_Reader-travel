package app.roadleaf.playback

import java.text.Normalizer

/** Spoken text only: checkpoints continue to refer to the original, unmodified character spans. */
object NarrationText {
    const val VERSION = 5
    private val label = Regex("^\\s*chapter\\s+([0-9]+|[ivxlcdm]+)\\b\\s*[:.\\-–—]?\\s*", RegexOption.IGNORE_CASE)
    private val footerLine = Regex("^(?:read novels online & discover free stories|privacy policy(?:\\s*&\\s*terms of use)?|terms of (?:use|service)|contact\\s*[-–—]\\s*sitemap|all rights reserved\\.?|use arrow keys .*|(?:prev(?:ious)?|next) chapter|\\d+\\s*/\\s*\\d+|report(?: this)? chapter|(?:add to|back to) (?:library|top)|table of contents|chapter list|font size|reader settings|bookmark|comments?|share)\\s*$", RegexOption.IGNORE_CASE)
    // Any site: "Read more at example.com"-style promotions and the current site's own address.
    private const val DOMAIN = "(?:https?://)?(?:www[.．])?[a-z0-9][a-z0-9-]{1,40}(?:[.．][a-z0-9-]{2,40})?[.．](?:com|net|org|io|co|me|xyz|info|site|online|top|cc|to|tv|us|uk|ca|biz|club|fun|vip|link|pro|app)(?:/[^\\s<>\\\"“”‘’]*)?(?![\\p{L}\\p{N}_])"
    private val sitePromotion = Regex("(?:\\b(?:read|find|follow|visit|source|updated|available|translated|hosted|discover|enjoy)(?:[^.!?\\n]{0,70})|\\b(?:this|the) (?:chapter|content|novel|translation)(?:[^.!?\\n]{0,90}))\\s+$DOMAIN(?:[^.!?\\n]{0,40}[.!])?", RegexOption.IGNORE_CASE)
    private fun siteAddress(site: String?): Regex? {
        val host = runCatching { java.net.URI(site ?: return null).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return null
        if (host == "freewebnovel.com" || !host.contains('.')) return null
        val pattern = host.split('.').joinToString("[.．]") { Regex.escape(it) }
        return Regex("(?<![\\p{L}\\p{N}_@])(?:https?://)?(?:www[.．])?$pattern(?:/[^\\s<>\\\"“”‘’]*)?(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    }
    private fun otherSites(text: String, site: String?): String {
        var result = sitePromotion.replace(text, " ")
        siteAddress(site)?.let { result = it.replace(result, " ") }
        return result.replace(Regex("[\\t ]{2,}"), " ").replace(Regex("(?m)^ +| +$"), "")
    }
    private val symbolsOnly = Regex("^[\\s\\p{P}\\p{S}]+$")
    // Watermarks can be in the middle of a story paragraph, rather than a separate footer line.
    private val brand = "free[\\s\\u200B-\\u200D\\uFEFF]*web[\\s\\u200B-\\u200D\\uFEFF]*novel"
    private val siteToken = Regex("(?<![\\p{L}\\p{N}_])(?:https?://)?(?:www[.．])?$brand(?:[\\s\\u200B-\\u200D\\uFEFF]*[.．][\\s\\u200B-\\u200D\\uFEFF]*com)?(?:/[^\\s<>\\\"“”‘’]*)?(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    private val promotion = Regex("(?:\\b(?:read|find|follow|visit|source|updated|available)(?:[^.!?\\n]{0,70})|\\b(?:this|the) (?:chapter|content|novel)(?:[^.!?\\n]{0,90}))\\s+(?:https?://)?(?:www\\.)?$brand(?:\\s*[.．]\\s*com)?(?:/[^\\s<>\\\"“”‘’]*)?[.!]?", RegexOption.IGNORE_CASE)
    private fun webpageText(text: String): String {
        val cleaned = if (Normalizer.normalize(text, Normalizer.Form.NFKC) == text) siteToken.replace(promotion.replace(text, " "), " ") else {
            // Match decorative mathematical letters without normalizing the story itself.
            val starts = mutableListOf<Int>(); val ends = mutableListOf<Int>()
            val folded = buildString {
                var index = 0
                while (index < text.length) {
                    val end = index + Character.charCount(text.codePointAt(index))
                    val plain = Normalizer.normalize(text.substring(index, end), Normalizer.Form.NFKC)
                    append(plain); repeat(plain.length) { starts.add(index); ends.add(end) }; index = end
                }
            }
            val ranges = (promotion.findAll(folded) + siteToken.findAll(folded)).map { starts[it.range.first] until ends[it.range.last] }.sortedBy { it.first }.toList()
            buildString {
                var cursor = 0
                for (range in ranges) {
                    if (range.last < cursor) continue
                    if (range.first >= cursor) { append(text.substring(cursor, range.first)); append(' ') }
                    cursor = maxOf(cursor, range.last + 1)
                }
                append(text.substring(cursor))
            }
        }
        return cleaned.replace(Regex("[\\t ]{2,}"), " ").replace(Regex("(?m)^ +| +$"), "")
    }
    fun title(value: String): String {
        var result = value.trim()
        var number: String? = null
        repeat(5) {
            val prefix = label.find(result)
            if (prefix != null) { number = prefix.groupValues[1]; result = result.drop(prefix.value.length).trim() }
        }
        number?.let { result = result.replace(Regex("^(?:${Regex.escape(it)}\\s*[:.\\-–—]\\s*)+", RegexOption.IGNORE_CASE), "") }
        return result.trim()
    }
    fun displayTitle(value: String): String {
        val prefix = label.find(value) ?: return value
        val spoken = title(value)
        return if (spoken.isBlank()) "Chapter ${prefix.groupValues[1]}" else "Chapter ${prefix.groupValues[1]}: $spoken"
    }
    /** [site] is the page address for webpage text, so that site's own address is never spoken. */
    fun part(original: String, chapterTitle: String, opening: Boolean, webpage: Boolean, site: String? = null): String {
        fun spoken(text: String) = otherSites(webpageText(text), site)
        val heading = title(if (webpage) spoken(chapterTitle).trim(' ', '|', '-', '–', '—', ':') else chapterTitle)
        val lines = original.lines().toMutableList()
        if (opening) {
            while (lines.isNotEmpty()) {
                val first = lines.first().trim()
                if (first.isBlank() || first.equals(chapterTitle.trim(), true) ||
                    (label.containsMatchIn(first) && (title(first).isBlank() || title(first).equals(heading, true)))) lines.removeAt(0)
                else break
            }
        }
        val filtered = lines.filterNot { line ->
            val text = line.trim()
            (webpage && ((text.isNotBlank() && spoken(text).none(Char::isLetterOrDigit)) || footerLine.matches(text) ||
                Regex("^(?:©|copyright)\\s*\\d{4}.*free\\s*web\\s*novel.*$", RegexOption.IGNORE_CASE).matches(text))) ||
                (text.isNotBlank() && symbolsOnly.matches(text))
        }.joinToString("\n")
        val body = (if (webpage) spoken(filtered) else filtered)
            // A reply containing only punctuation is an unspoken beat, not invented dialogue.
            .replace(Regex("[?？]{2,}[!！]*"), "?")
            .replace(Regex("[!！]{2,}"), "!")
            .replace(Regex("[\"“‘']\\s*[?!？！…]+\\s*[\"”’']"), "\n\n")
            .replace(Regex("(?m)(:\\s*)[?!？！…]+\\s*$"), "$1\n")
            .replace(Regex("\\n{3,}"), "\n\n").trim()
        return listOf(if (opening) heading else "", body).filter(String::isNotBlank).joinToString("\n\n")
    }
    /** Expand unambiguous English reading conventions after applying the owner's overrides. */
    fun english(text: String): String {
        var result = text
        mapOf("Mr" to "Mister", "Mrs" to "Missus", "Ms" to "Miz", "Dr" to "Doctor", "Prof" to "Professor").forEach { (short, spoken) ->
            result = result.replace(Regex("\\b$short\\.(?=\\s+\\p{Lu})"), spoken)
        }
        return result.replace(Regex("\\bNo\\.\\s*(?=\\d)"), "Number ")
            .replace(Regex("\\bLv\\.\\s*(?=\\d)", RegexOption.IGNORE_CASE), "Level ")
    }
}
