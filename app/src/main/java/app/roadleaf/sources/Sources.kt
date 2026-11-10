package app.roadleaf.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

enum class Capability { CATALOG, EPUB_DOWNLOAD, BROWSER, FILE_IMPORT }
data class SourceBook(val title: String, val author: String, val pageUrl: String, val epubUrl: String? = null)
sealed interface SourceAction {
    data class Download(val url: String, val sourceId: String) : SourceAction
    data class Browser(val url: String, val guidance: String) : SourceAction
    data object ImportFile : SourceAction
}
interface StorySource {
    val id: String
    val name: String
    val capabilities: Set<Capability>
    fun accepts(uri: URI): Boolean
    fun resolve(uri: URI): SourceAction
    suspend fun search(query: String): List<SourceBook> = emptyList()
}

object SourcePolicy {
    fun host(uri: URI) = uri.host?.lowercase()?.removePrefix("www.") ?: ""
    fun secure(uri: URI): Boolean = uri.scheme.equals("https", true) && uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    fun permittedDownload(uri: URI, sourceId: String): Boolean {
        if (!secure(uri)) return false
        return when (sourceId) {
            "gutenberg" -> host(uri) == "gutenberg.org" && (uri.path.startsWith("/ebooks/") || uri.path.startsWith("/cache/epub/") || uri.path.startsWith("/files/"))
            "ao3" -> host(uri) in setOf("archiveofourown.org", "download.archiveofourown.org") && uri.path.startsWith("/downloads/") && uri.path.endsWith(".epub", true)
            else -> false
        }
    }
}

class GutenbergSource : StorySource {
    override val id = "gutenberg"
    override val name = "Project Gutenberg"
    override val capabilities = setOf(Capability.CATALOG, Capability.EPUB_DOWNLOAD, Capability.BROWSER)
    override fun accepts(uri: URI) = SourcePolicy.host(uri) == "gutenberg.org"
    override fun resolve(uri: URI): SourceAction = if (SourcePolicy.permittedDownload(uri, id) && (uri.path.endsWith(".epub") || uri.path.contains(".epub."))) SourceAction.Download(uri.toString(), id) else SourceAction.Browser(uri.toString(), "Browse public-domain titles or search the OPDS catalog.")
    override suspend fun search(query: String): List<SourceBook> {
        val url = "https://www.gutenberg.org/ebooks/search.opds/?query=" + URLEncoder.encode(query.take(200), "UTF-8")
        val bytes = SafeHttp.get(url, 2 * 1024 * 1024) { SourcePolicy.secure(it) && SourcePolicy.host(it) == "gutenberg.org" }
        return parseFeed(bytes.toString(Charsets.UTF_8), url)
    }
    internal fun parseFeed(xml: String, base: String): List<SourceBook> {
        val doc = Jsoup.parse(xml, base, Parser.xmlParser())
        return doc.getElementsByTag("entry").take(40).mapNotNull { entry ->
            val links = entry.getElementsByTag("link")
            val epub = links.firstOrNull { it.attr("type") == "application/epub+zip" && it.attr("rel").contains("acquisition") }?.attr("href")?.let { URI(base).resolve(it).toString() }
            val page = links.firstOrNull { it.attr("type") == "text/html" }?.attr("href")?.let { URI(base).resolve(it).toString() } ?: epub
            page?.let { SourceBook(entry.getElementsByTag("title").first()?.text().orEmpty(), entry.getElementsByTag("author").first()?.text().orEmpty(), it, epub) }
        }
    }
}

class Ao3Source : StorySource {
    override val id = "ao3"
    override val name = "Archive of Our Own"
    override val capabilities = setOf(Capability.EPUB_DOWNLOAD, Capability.BROWSER, Capability.FILE_IMPORT)
    override fun accepts(uri: URI) = SourcePolicy.host(uri) in setOf("archiveofourown.org", "download.archiveofourown.org")
    override fun resolve(uri: URI): SourceAction = if (SourcePolicy.permittedDownload(uri, id)) SourceAction.Download(uri.toString(), id) else SourceAction.Browser(uri.toString(), "Use AO3’s Download → EPUB in your browser, then import the file. Login and restricted works stay in your browser. You can also paste an official EPUB download link you are authorized to access.")
}
class BrowserSource(override val id: String, override val name: String, private val hosts: Set<String>) : StorySource {
    override val capabilities = setOf(Capability.BROWSER, Capability.FILE_IMPORT)
    override fun accepts(uri: URI) = SourcePolicy.host(uri) in hosts
    override fun resolve(uri: URI) = SourceAction.Browser(uri.toString(), "Bookmark or open in browser. Import only a file the author or platform permits you to use. Chapter downloading is not offered for this source.")
}
class AuthorFileSource : StorySource {
    override val id = "author-file"
    override val name = "Author-direct / local EPUB or TXT"
    override val capabilities = setOf(Capability.FILE_IMPORT)
    override fun accepts(uri: URI) = false
    override fun resolve(uri: URI) = SourceAction.ImportFile
}
class NovelAiSource : StorySource {
    override val id = "novelai"
    override val name = "NovelAI — your stories"
    override val capabilities = setOf(Capability.BROWSER, Capability.FILE_IMPORT)
    override fun accepts(uri: URI) = SourcePolicy.host(uri) in setOf("novelai.net", "www.novelai.net")
    override fun resolve(uri: URI) = SourceAction.Browser(uri.toString(), "Use NovelAI · Read in my browser in Library for the optional editor-only accessibility connection. Keep your existing browser sign-in, choose the story, tap its writing box and Read story. It follows completed paragraphs while the tab is visible. TXT export/import remains available. Full lorebooks stay in your personal collection.")
}
class SourceRegistry {
    val sources: List<StorySource> = listOf(GutenbergSource(), Ao3Source(), BrowserSource("royal-road", "Royal Road", setOf("royalroad.com")), BrowserSource("webnovel", "Webnovel", setOf("webnovel.com", "m.webnovel.com")), NovelAiSource(), AuthorFileSource())
    fun source(url: String): StorySource? = runCatching { URI(url).takeIf(SourcePolicy::secure)?.let { uri -> sources.firstOrNull { it.accepts(uri) } } }.getOrNull()
    fun resolve(url: String): SourceAction {
        val uri = URI(url.trim())
        require(SourcePolicy.secure(uri)) { "Use a secure HTTPS link." }
        return source(url)?.resolve(uri) ?: SourceAction.Browser(uri.toString(), "Open the author’s site, download an authorized EPUB/TXT, then use Import file.")
    }
}

/** No cookies, credential collection, HTML crawling, lock bypass or automatic retry. */
object SafeHttp {
    suspend fun get(url: String, limit: Int, allowed: (URI) -> Boolean): ByteArray = withContext(Dispatchers.IO) {
        var current = URI(url)
        repeat(5) {
            require(allowed(current)) { "Download destination is not allowed for this source." }
            val connection = URL(current.toString()).openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "Roadleaf/0.1 (personal offline reader; user-initiated requests)")
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    current = current.resolve(connection.getHeaderField("Location") ?: error("Missing redirect location"))
                } else {
                    require(code == 200) { "Source returned HTTP $code. Open it in your browser; downloads are not retried automatically." }
                    require(connection.contentLengthLong <= limit) { "File exceeds the download limit." }
                    return@withContext connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= limit) { "File exceeds the download limit." }; output.write(buffer, 0, count) }
                        output.toByteArray()
                    }
                }
            } finally { connection.disconnect() }
        }
        error("Too many redirects")
    }
}
