package app.roadleaf.importing

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URI
import java.util.zip.ZipFile

data class ParsedChapter(val title: String, val text: String)
data class ParsedBook(val title: String, val author: String, val chapters: List<ParsedChapter>)

object BookParser {
    const val MAX_BYTES = 32 * 1024 * 1024
    fun txt(text: String, filename: String): ParsedBook {
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        require(clean.isNotBlank()) { "The text file is empty." }
        val headings = Regex("(?im)^(?:chapter|book|part|prologue|epilogue)\\b[^\\n]{0,120}$").findAll(clean).toList()
        val chapters = if (headings.isEmpty()) listOf(ParsedChapter(filename.substringBeforeLast('.'), clean)) else buildList {
            if (headings.first().range.first > 0) add(ParsedChapter("Introduction", clean.substring(0, headings.first().range.first).trim()))
            headings.forEachIndexed { index, match -> add(ParsedChapter(match.value.trim(), clean.substring(match.range.first, headings.getOrNull(index + 1)?.range?.first ?: clean.length).trim())) }
        }
        return ParsedBook(filename.substringBeforeLast('.'), "Unknown author", chapters)
    }
    fun epub(file: File): ParsedBook = ZipFile(file).use { zip ->
        require(zip.size() <= 10_000) { "Too many EPUB entries." }
        var total = 0
        fun read(path: String): String {
            require(!path.startsWith("/") && path.split('/').none { it == ".." }) { "Unsafe EPUB path." }
            val entry = zip.getEntry(path) ?: error("EPUB is missing $path")
            require(entry.size <= MAX_BYTES) { "EPUB entry too large." }
            val bytes = zip.getInputStream(entry).use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; require(total + output.size() + count <= MAX_BYTES) { "EPUB expanded text exceeds 32 MB." }; output.write(buffer, 0, count) }
                output.toByteArray()
            }
            total += bytes.size
            require(total <= MAX_BYTES) { "EPUB expanded text exceeds 32 MB." }
            return bytes.toString(Charsets.UTF_8)
        }
        require(zip.getEntry("META-INF/encryption.xml") == null) { "Encrypted EPUBs are not supported. Import an authorized DRM-free copy." }
        val container = Jsoup.parse(read("META-INF/container.xml"), "", Parser.xmlParser())
        val opfPath = container.getElementsByTag("rootfile").first()?.attr("full-path") ?: error("EPUB has no package document.")
        val opf = Jsoup.parse(read(opfPath), "", Parser.xmlParser())
        val manifest = opf.getElementsByTag("item").associateBy { it.attr("id") }
        val chapters = opf.getElementsByTag("itemref").filter { it.attr("linear") != "no" }.mapNotNull { ref ->
            val item = manifest[ref.attr("idref")] ?: error("Broken EPUB spine.")
            if (item.attr("media-type") !in setOf("application/xhtml+xml", "text/html")) return@mapNotNull null
            val resolved = URI("file:///" + opfPath).resolve(item.attr("href")).normalize()
            require(resolved.scheme == "file" && resolved.host == null) { "Remote EPUB spine is unsupported." }
            val html = Jsoup.parse(read(resolved.path.removePrefix("/")))
            html.select("script,style,nav").remove()
            val title = (html.selectFirst("h1,h2") ?: html.selectFirst("title"))?.text().orEmpty()
            html.select("p,div,br,h1,h2,h3,li").forEach { it.appendText("\n") }
            val text = html.body().wholeText().replace(Regex("[\\t ]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
            if (text.isBlank()) null else ParsedChapter(title.ifBlank { "Chapter ${ref.elementSiblingIndex() + 1}" }, text)
        }
        require(chapters.isNotEmpty()) { "No readable chapters in EPUB." }
        ParsedBook(opf.getElementsByTag("dc:title").first()?.text()?.ifBlank { null } ?: file.nameWithoutExtension, opf.getElementsByTag("dc:creator").first()?.text() ?: "Unknown author", chapters)
    }
}
