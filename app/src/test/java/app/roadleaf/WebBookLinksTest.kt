package app.roadleaf

import app.roadleaf.sources.WebBookLinks
import org.junit.Assert.*
import org.junit.Test

class WebBookLinksTest {
    @Test fun sharingABrowserOnlyBookKeepsItsOwnLink() {
        assertEquals("https://royalroad.com/fiction/123/a-quiet-road", WebBookLinks.sharedUrl("Read this book: https://www.royalroad.com/fiction/123/a-quiet-road."))
        assertNull(WebBookLinks.sharedUrl("Read this: http://example.com/book"))
    }
    @Test fun chaptersShareOneShortcutAndKeepTheirExactPage() {
        val first = requireNotNull(WebBookLinks.resolve("https://www.freewebnovel.com/novel/a-quiet-road/chapter-20?utm_source=test#top"))
        val next = requireNotNull(WebBookLinks.resolve("https://freewebnovel.com/novel/a-quiet-road/chapter-21"))
        assertEquals(first.bookUrl, next.bookUrl)
        assertEquals("https://freewebnovel.com/novel/a-quiet-road/chapter-20", first.chapterUrl)
        assertEquals("Chapter 21", next.chapterTitle)
        assertEquals("A Quiet Road", first.title)
        assertTrue(first.readable)
    }
    @Test fun otherSitesAreReadableAndKeepOneShortcutPerBook() {
        val rr = requireNotNull(WebBookLinks.resolve("https://royalroad.com/fiction/123/a-quiet-road/chapter/456/day-one"))
        assertEquals("https://royalroad.com/fiction/123", rr.bookUrl)
        assertTrue(rr.readable)
        val webnovel = requireNotNull(WebBookLinks.resolve("https://m.webnovel.com/book/a-quiet-road_123/chapter-one_456"))
        assertEquals("https://webnovel.com/book/a-quiet-road_123", webnovel.bookUrl)
        assertTrue(webnovel.readable)
        val author = requireNotNull(WebBookLinks.resolve("https://author.example/stories/a-quiet-road#read"))
        assertEquals("author.example", author.site)
        assertEquals("https://author.example/stories/a-quiet-road", author.chapterUrl)
        assertTrue(author.readable)
    }
    @Test fun genericChapterAddressesShareTheirBook() {
        val first = requireNotNull(WebBookLinks.resolve("https://www.scribblehub.com/read/123456-a-quiet-road/chapter/789/"))
        val second = requireNotNull(WebBookLinks.resolve("https://scribblehub.com/read/123456-a-quiet-road/chapter/790/"))
        assertEquals("https://scribblehub.com/read/123456-a-quiet-road", first.bookUrl)
        assertEquals(first.bookUrl, second.bookUrl)
        assertEquals("A Quiet Road", first.title)
        assertEquals("Scribble Hub", first.site)
        assertEquals("https://novels.example/novel/a-quiet-road", WebBookLinks.resolve("https://novels.example/novel/a-quiet-road/chapter-12")?.bookUrl)
        assertEquals("https://novels.example/book/a-quiet-road", WebBookLinks.resolve("https://novels.example/book/a-quiet-road/c12")?.bookUrl)
        assertEquals("https://stories.example/story/55", WebBookLinks.resolve("https://stories.example/story/55/3")?.bookUrl)
        val ffn = requireNotNull(WebBookLinks.resolve("https://m.fanfiction.net/s/123/4/a-quiet-road"))
        assertEquals("https://fanfiction.net/s/123", ffn.bookUrl)
        assertEquals("A Quiet Road", ffn.title)
        // Tracking parameters never split one chapter into two saved positions.
        assertEquals("https://novels.example/novel/a-quiet-road/chapter-12?page=2",
            WebBookLinks.resolve("https://novels.example/novel/a-quiet-road/chapter-12/?utm_source=feed&page=2#comments")?.chapterUrl)
    }
    @Test fun rejectsUnsafeOrInvalidShortcuts() {
        listOf("http://example.com/book", "javascript:alert(1)", "file:///book.txt", "https://name:secret@example.com/book", "https://example.com:8443/book", "not a URL").forEach { assertNull(it, WebBookLinks.resolve(it)) }
    }
}
