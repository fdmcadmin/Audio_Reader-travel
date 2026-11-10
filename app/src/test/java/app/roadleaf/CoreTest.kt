package app.roadleaf

import app.roadleaf.importing.BookParser
import app.roadleaf.playback.SpeechText
import app.roadleaf.sources.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreTest {
    @Test fun chunksPreserveOriginalTextAndOffsets() {
        val text = "A curious dragon waited. ".repeat(400) + "A final sentence."
        val chunks = SpeechText.chunks(text, 120)
        assertEquals(text, chunks.joinToString("") { it.text })
        chunks.forEach { assertTrue(it.text.length <= 120); assertEquals(it.text, text.substring(it.offset, it.offset + it.text.length)) }
    }
    @Test fun pronunciationIsLiteralWholeWordAndNonRecursive() {
        assertEquals("Vay lith meets Valithian and dragon.", SpeechText.pronounce("Vaelith meets Valithian and wyrm.", listOf("Vaelith" to "Vay lith", "wyrm" to "dragon", "dragon" to "do not recurse")))
        assertEquals("dollar\$1", SpeechText.pronounce("A.B", listOf("A.B" to "dollar\$1")))
    }
    @Test fun noUniversalDownloadOrHostSpoofing() {
        val registry = SourceRegistry()
        listOf("https://www.royalroad.com/fiction/1", "https://www.webnovel.com/book/1", "https://archiveofourown.org/works/123").forEach { assertTrue(registry.resolve(it) is SourceAction.Browser) }
        assertTrue(registry.resolve("https://download.archiveofourown.org/downloads/123/book.epub") is SourceAction.Download)
        assertFalse(SourcePolicy.permittedDownload(URI("https://archiveofourown.org.evil.test/downloads/1/a.epub"), "ao3"))
        assertFalse(SourcePolicy.permittedDownload(URI("https://user:secret@archiveofourown.org/downloads/1/a.epub"), "ao3"))
        assertFalse(SourcePolicy.permittedDownload(URI("http://gutenberg.org/ebooks/1.epub"), "gutenberg"))
        assertFalse(SourcePolicy.permittedDownload(URI("https://royalroad.com/fiction/book.epub"), "royal-road"))
    }
    @Test fun opdsResolvesRelativeAcquisitionLinks() {
        val result = GutenbergSource().parseFeed("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Example</title><author><name>Author</name></author><link type="application/epub+zip" rel="http://opds-spec.org/acquisition" href="/ebooks/1.epub.noimages" /></entry></feed>""", "https://www.gutenberg.org/ebooks/search.opds/").single()
        assertEquals("https://www.gutenberg.org/ebooks/1.epub.noimages", result.epubUrl)
    }
    @Test fun txtRecognizesHeadingsAndBom() {
        val book = BookParser.txt("\uFEFFPreface\r\nChapter 1\r\nHello.\r\nChapter 2\r\nGoodbye.", "Story.txt")
        assertEquals(3, book.chapters.size); assertEquals("Chapter 2", book.chapters.last().title)
    }
    private fun epub(entries: Map<String, String>): File {
        val file = File.createTempFile("roadleaf-test", ".epub")
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, body) -> zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry() } }
        return file
    }
    @Test fun epubFollowsSpineRatherThanZipOrder() {
        val file = epub(mapOf("META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf" /></rootfiles></container>""", "OEBPS/content.opf" to """<package><metadata><dc:title>Example</dc:title><dc:creator>Author</dc:creator></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="b"/><itemref idref="a"/></spine></package>""", "OEBPS/a.xhtml" to "<html><body><h1>First</h1><p>Alpha</p></body></html>", "OEBPS/b.xhtml" to "<html><body><h1>Second</h1><p>Beta</p><script>Never speak me</script></body></html>"))
        try { val book = BookParser.epub(file); assertEquals("Example", book.title); assertEquals("Second", book.chapters.first().title); assertFalse(book.chapters.first().text.contains("Never speak me")) } finally { file.delete() }
    }
    @Test fun encryptedEpubIsRejected() {
        val file = epub(mapOf("META-INF/encryption.xml" to "<encryption/>"))
        try { assertThrows(IllegalArgumentException::class.java) { BookParser.epub(file) } } finally { file.delete() }
    }
    @Test fun epubUsesChapterHeadingBeforeRepeatedDocumentTitle() {
        val file = epub(mapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""",
            "book.opf" to """<package><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            "chapter.xhtml" to "<html><head><title>Repeated book title</title></head><body><h2>16. Test chapter</h2><p>Original test text.</p></body></html>"
        ))
        try { assertEquals("16. Test chapter", BookParser.epub(file).chapters.single().title) } finally { file.delete() }
    }
}
