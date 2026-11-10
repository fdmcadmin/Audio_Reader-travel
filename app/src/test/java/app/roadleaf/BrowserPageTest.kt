package app.roadleaf

import app.roadleaf.browser.BrowserPageHtml
import app.roadleaf.browser.BrowserPages
import app.roadleaf.browser.PageNode
import app.roadleaf.sources.LiveWebPages
import org.junit.Assert.*
import org.junit.Test

/** A browser tab's accessibility nodes (original fixture text) become the same readable page. */
class BrowserPageTest {
    private val lines = listOf(
        "The ferry left at dawn, and Mira watched the harbour lights fade one by one behind her.",
        "By noon the island was a green line on the horizon, and the gulls had stopped following.",
        "She opened her grandmother's letter again, smoothing the creases with careful fingers.")

    private fun tab(): List<PageNode> = buildList {
        add(PageNode(0, "div", ""))
        add(PageNode(1, "header", "")); add(PageNode(2, "a", "", "https://tales.example/")); add(PageNode(3, "span", "Tales Example"))
        add(PageNode(2, "a", "", "https://tales.example/login")); add(PageNode(3, "span", "Log in"))
        add(PageNode(1, "nav", "")); add(PageNode(2, "a", "", "https://tales.example/browse")); add(PageNode(3, "span", "Browse"))
        add(PageNode(1, "main", ""))
        add(PageNode(2, "h2", "")); add(PageNode(3, "span", "Chapter 4: The Ferry"))
        lines.forEach { add(PageNode(2, "p", "")); add(PageNode(3, "span", it)) }
        add(PageNode(2, "a", "", "https://tales.example/the-crossing/chapter-5")); add(PageNode(3, "span", "Next Chapter"))
        add(PageNode(1, "footer", "")); add(PageNode(2, "span", "© 2026 Tales Example"))
        add(PageNode(1, "input", "search words never read"))
    }

    @Test fun browserTabNodesReadLikeTheSamePageInRoadleafsReader() {
        val html = BrowserPageHtml.build("Chapter 4: The Ferry - The Crossing - Tales Example", tab())
        val page = LiveWebPages.parse(html, "https://tales.example/the-crossing/chapter-4")
        assertEquals("Chapter 4: The Ferry", page.title)
        assertEquals("The Crossing", page.bookTitle)
        assertEquals("https://tales.example/the-crossing/chapter-5", page.nextUrl)
        lines.forEach { assertTrue(it, page.text.contains(it)) }
        listOf("Log in", "Browse", "2026", "search words").forEach { assertFalse(it, page.text.contains(it)) }
    }
    @Test fun nodeTextIsEscapedAndRolesMapToPlainHtml() {
        val html = BrowserPageHtml.build("A <b> title", listOf(PageNode(0, "p", "Fish & chips <script>x</script>"), PageNode(0, "a", "", "https://x.example/?a=1&b=\"2\"")))
        assertTrue(html.contains("Fish &amp; chips &lt;script&gt;x&lt;/script&gt;"))
        assertTrue(html.contains("href=\"https://x.example/?a=1&amp;b=&quot;2&quot;\""))
        assertEquals("p", BrowserPageHtml.tag("paragraph", false, null))
        assertEquals("h2", BrowserPageHtml.tag("genericContainer", true, null))
        assertEquals("nav", BrowserPageHtml.tag("navigation", false, null))
        assertEquals("input", BrowserPageHtml.tag(null, false, "android.widget.EditText"))
    }
    @Test fun browserAddressFieldsBecomePagesOnlyWithAFullPath() {
        assertEquals("https://tales.example/the-crossing/chapter-4", BrowserPages.pageUrl("tales.example/the-crossing/chapter-4"))
        assertEquals("https://tales.example/the-crossing/chapter-4", BrowserPages.pageUrl("https://www.tales.example/the-crossing/chapter-4#top"))
        listOf(null, "", "tales.example", "search words here", "http://tales.example/a", "javascript:alert(1)").forEach { assertNull(it, BrowserPages.pageUrl(it)) }
    }
}
