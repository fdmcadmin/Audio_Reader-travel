package app.roadleaf.sources

import java.net.URI
import java.util.Locale

data class WebBookLink(val bookUrl: String, val chapterUrl: String, val title: String, val chapterTitle: String, val site: String) {
    val readable get() = LiveWebPages.canonical(chapterUrl) != null
}

/** Shortcut identity only. Resolving a link does not fetch text. Chapters of one book share one shortcut. */
object WebBookLinks {
    private val chapterSegment = Regex("^(?:chapters?|chap|ch|c|part|episode|ep)[-_.]?\\d+(?:[-_.].*)?$", RegexOption.IGNORE_CASE)
    private val chapterWord = setOf("chapter", "chapters", "episode", "episodes", "part", "parts")
    private val siteNames = mapOf("freewebnovel.com" to "FreeWebNovel", "royalroad.com" to "Royal Road", "webnovel.com" to "Webnovel",
        "archiveofourown.org" to "AO3", "gutenberg.org" to "Project Gutenberg", "fanfiction.net" to "FanFiction", "fictionpress.com" to "FictionPress",
        "scribblehub.com" to "Scribble Hub", "wattpad.com" to "Wattpad")

    fun sharedUrl(text: String): String? = Regex("https://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text.take(4096))
        .mapNotNull { resolve(it.value.trimEnd('.', ',', ')', ']', '"'))?.chapterUrl }.firstOrNull()

    /** Any site: the path before a chapter segment (or a trailing chapter number) names the book. */
    private fun genericBookPath(segments: List<String>): String? {
        val index = segments.indexOfFirst { chapterSegment.matches(it) }.takeIf { it > 0 }
            ?: segments.indexOfFirst { it.lowercase() in chapterWord }.takeIf { it > 0 && it < segments.lastIndex }
        if (index != null) return "/" + segments.take(index).joinToString("/")
        if (segments.size >= 2 && segments.last().all(Char::isDigit)) return "/" + segments.dropLast(1).joinToString("/")
        return null
    }

    fun resolve(value: String): WebBookLink? = runCatching {
        val uri = URI(value.trim()).normalize()
        if (!SourcePolicy.secure(uri) || uri.host.isNullOrBlank() || value.length > 4096) return null
        val host = SourcePolicy.host(uri)
        val path = uri.path.orEmpty().ifBlank { "/" }
        val legacy = FreeWebNovel.canonical(uri)
        val segments = path.split('/').filter(String::isNotBlank)
        val fanfiction = host in setOf("fanfiction.net", "m.fanfiction.net", "fictionpress.com", "m.fictionpress.com")
        val bookPath = when {
            legacy != null -> path.substringBeforeLast('/')
            host == "royalroad.com" && segments.firstOrNull() == "fiction" && segments.size >= 2 -> "/fiction/${segments[1]}"
            host in setOf("webnovel.com", "m.webnovel.com") && segments.firstOrNull() == "book" && segments.size >= 2 -> "/book/${segments[1]}"
            host == "archiveofourown.org" && segments.firstOrNull() == "works" && segments.size >= 2 -> "/works/${segments[1]}"
            fanfiction && segments.firstOrNull() == "s" && segments.size >= 2 -> "/s/${segments[1]}"
            else -> genericBookPath(segments) ?: path.trimEnd('/').ifEmpty { "/" }
        }
        val canonicalHost = when (host) { "m.webnovel.com" -> "webnovel.com"; "m.fanfiction.net" -> "fanfiction.net"; "m.fictionpress.com" -> "fictionpress.com"; else -> host }
        val chapterUrl = legacy ?: LiveWebPages.canonical(uri.toString()) ?: URI("https", null, host, -1, path, uri.query, null).toASCIIString()
        val titlePath = when {
            host == "royalroad.com" && segments.size > 2 && segments[2] != "chapter" -> segments[2]
            fanfiction && segments.firstOrNull() == "s" && segments.size >= 4 -> segments[3]
            else -> bookPath.substringAfterLast('/')
        }
        val slug = titlePath.substringBeforeLast('_', titlePath)
        val title = slug.replace(Regex("[-_]+"), " ").replace(Regex("^\\d+\\s+"), "").trim().takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
            ?.split(' ')?.joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.US) } } ?: canonicalHost
        val chapter = Regex("/chapter[-/](\\d+)").find(path)?.groupValues?.get(1)?.let { "Chapter $it" }.orEmpty()
        WebBookLink("https://$canonicalHost$bookPath", chapterUrl, title.take(200), chapter, siteNames[canonicalHost] ?: canonicalHost)
    }.getOrNull()
}
