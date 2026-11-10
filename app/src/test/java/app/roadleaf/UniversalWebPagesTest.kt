package app.roadleaf

import app.roadleaf.playback.NarrationText
import app.roadleaf.sources.AddressInput
import app.roadleaf.sources.LiveWebPages
import app.roadleaf.sources.SearchEngine
import app.roadleaf.sources.WebAccess
import app.roadleaf.sources.WebAddress
import org.junit.Assert.*
import org.junit.Test

/** Original fixture prose only. These pages imitate common layouts, not any real site's text. */
class UniversalWebPagesTest {
    private val page = "https://example.org/story/my-tale/chapter-2"
    private val story = listOf(
        "The bridge creaked under the cart as Ada guided the horses across, counting each plank aloud.",
        "On the far bank, the lantern-maker waited with a letter, a map, and a basket of warm bread.",
        "She thanked him, folded the map, and promised to return before the river froze again.")

    private fun article(extraHead: String = "", extraBody: String = "") = """<html><head><title>Chapter 2: The Bridge - My Tale - Example Fiction</title>
        <meta property="og:site_name" content="Example Fiction">$extraHead</head><body>
        <header><a href="/">Example Fiction</a> <a href="/login">Log in</a></header>
        <nav><a href="/browse">Browse</a> <a href="/rankings">Rankings</a></nav>
        <div class="layout"><aside class="sidebar"><p>Popular this week: an unrelated story with a long sidebar summary text.</p></aside>
        <article class="chapter-content"><h1>Chapter 2: The Bridge</h1>${story.joinToString("") { "<p>$it</p>" }}</article>
        <div class="comments"><p>What a lovely chapter, I can't wait to read more of this story!</p><a href="/story/my-tale/chapter-2?comments=2">Next</a></div></div>
        <footer><p>© 2026 Example Fiction. All rights reserved.</p></footer>$extraBody</body></html>"""

    @Test fun readsMainStoryTextOfAnyPageAndSkipsMenusSidebarsAndComments() {
        val result = LiveWebPages.parse(article("""<link rel="next" href="/story/my-tale/chapter-3">"""), page)
        assertEquals("Chapter 2: The Bridge", result.title)
        assertEquals("My Tale", result.bookTitle)
        assertEquals("https://example.org/story/my-tale/chapter-3", result.nextUrl)
        story.forEach { assertTrue(it, result.text.contains(it)) }
        listOf("Browse", "Popular this week", "lovely chapter", "All rights reserved", "Log in").forEach { assertFalse(it, result.text.contains(it)) }
    }
    @Test fun readsLineBreakLayoutsAndNextChapterLinkText() {
        val html = """<html><head><title>My Tale ch. 7</title></head><body><div class="menu"><a href="/a">Home</a> <a href="/b">Top</a></div>
            <div id="content">${story.joinToString("<br><br>")}</div>
            <a class="btn" href="/story/my-tale/chapter-8">Next Chapter →</a></body></html>"""
        val result = LiveWebPages.parse(html, "https://example.org/story/my-tale/chapter-7")
        story.forEach { assertTrue(it, result.text.contains(it)) }
        assertFalse(result.text.contains("Home"))
        assertEquals("https://example.org/story/my-tale/chapter-8", result.nextUrl)
    }
    @Test fun hiddenDecoyTextIsNeverSpokenButContentStaysEvenIfPrintStylesHideIt() {
        val html = """<html><head><title>Chapter 4 - My Tale</title><style>.xq7 { display: none } @media print { .chapter-content { display: none } }</style></head><body>
            <div class="chapter-content"><p>${story[0]}</p><p class="xq7">A decoy notice copied from elsewhere.</p>
            <p data-roadleaf-hidden="1">Text the page hides from readers.</p><p style="display:none">Another hidden line.</p><p>${story[1]}</p></div></body></html>"""
        val text = LiveWebPages.parse(html, "https://example.org/story/my-tale/chapter-4").text
        assertTrue(text.contains(story[0])); assertTrue(text.contains(story[1]))
        listOf("decoy", "hides from readers", "Another hidden").forEach { assertFalse(it, text.contains(it)) }
    }
    @Test fun signInLockedAndCheckPagesAreReportedNotWorkedAround() {
        val wall = """<html><head><title>My Tale - Chapter 9</title></head><body><main><p>Please sign in to continue reading this chapter of the story.</p>
            <form><input type="email"><input type="password"><button>Log in</button></form></main></body></html>"""
        val locked = """<html><head><title>My Tale - Chapter 10</title></head><body><main class="chapter"><p>The first lines of the chapter appear here as a short preview for everyone.</p>
            <p>Unlock this chapter with coins to keep reading the story today.</p></main></body></html>"""
        val check = "<html><head><title>Just a moment...</title></head><body><div id=\"challenge-stage\">Checking your browser</div></body></html>"
        assertEquals("This page needs your account. Sign in on the site, then choose Read loaded chapter.",
            assertThrows(IllegalStateException::class.java) { LiveWebPages.parse(wall, page) }.message)
        assertTrue(assertThrows(IllegalStateException::class.java) { LiveWebPages.parse(locked, page) }.message!!.startsWith("Locked or paid chapters are not read"))
        assertTrue(assertThrows(IllegalStateException::class.java) { LiveWebPages.parse(check, page) }.message!!.startsWith("Finish the site's check"))
        // A full chapter is read even when the page header offers sign-in.
        assertTrue(LiveWebPages.parse(article(), page).text.contains(story[2]))
    }
    @Test fun nextChapterStaysOnTheSameSiteAndNeverGoesBackToTheIndex() {
        assertEquals("https://example.org/story/my-tale/chapter-3", LiveWebPages.nextUrl(page, "chapter-3"))
        assertEquals("https://read.example.org/story/my-tale/chapter-3", LiveWebPages.nextUrl(page, "https://read.example.org/story/my-tale/chapter-3"))
        assertEquals("https://example.org/story/my-tale?chapter=3", LiveWebPages.nextUrl("https://example.org/story/my-tale?chapter=2", "?chapter=3"))
        assertEquals("https://books.example.co.uk/tale/2", LiveWebPages.nextUrl("https://example.co.uk/tale/1", "https://books.example.co.uk/tale/2"))
        listOf("https://other.example/story/my-tale/chapter-3", "/story/my-tale", "/", page, "#top", "javascript:alert(1)", "http://example.org/story/my-tale/chapter-3",
            "https://example.co.uk.evil.test/tale/2").forEach { assertNull(it, LiveWebPages.nextUrl(page, it)) }
        assertNull(LiveWebPages.nextUrl("https://example.co.uk/tale/1", "https://other.co.uk/tale/2"))
    }
    @Test fun knownSitesUseTheirChapterContainerAndOnClickNextButtons() {
        val royal = """<html><head><title>Day One | A Quiet Road | Royal Road</title></head><body><div class="fic-header"><h1>Day One</h1><h2>A Quiet Road</h2></div>
            <div class="portlet"><div class="author-note-portlet"><p>Author note: thanks for reading, please leave a review and follow the story!</p></div>
            <div class="chapter-inner chapter-content">${story.joinToString("") { "<p>$it</p>" }}</div></div>
            <a class="btn" href="/fiction/1/a-quiet-road/chapter/12/day-two">Next Chapter</a></body></html>"""
        val rr = LiveWebPages.parse(royal, "https://www.royalroad.com/fiction/1/a-quiet-road/chapter/11/day-one")
        assertEquals("Day One", rr.title); assertEquals("A Quiet Road", rr.bookTitle)
        assertFalse(rr.text.contains("Author note"))
        assertEquals("https://royalroad.com/fiction/1/a-quiet-road/chapter/12/day-two", rr.nextUrl)
        val ffn = """<html><head><title>A Quiet Road Chapter 3, a fanfic</title></head><body><div id="profile_top"><b class="xcontrast_txt">A Quiet Road</b></div>
            <select id="chap_select"><option value="2">2. Meadow</option><option value="3" selected>3. River</option></select>
            <div id="storytext">${story.joinToString("") { "<p>$it</p>" }}</div>
            <button onclick="self.location='/s/123/4/a-quiet-road'">Next &gt;</button></body></html>"""
        val story3 = LiveWebPages.parse(ffn, "https://www.fanfiction.net/s/123/3/a-quiet-road")
        assertEquals("3. River", story3.title); assertEquals("A Quiet Road", story3.bookTitle)
        assertEquals("https://fanfiction.net/s/123/4/a-quiet-road", story3.nextUrl)
    }
    @Test fun canonicalAddressesDropTrackingFragmentsAndWww() {
        assertEquals("https://example.org/story/my-tale/chapter-2?lang=en",
            LiveWebPages.canonical("https://www.example.org/story/my-tale/chapter-2/?utm_source=feed&fbclid=1&lang=en#top"))
        assertEquals("https://example.org/", LiveWebPages.canonical("https://example.org"))
        listOf("http://example.org/a", "https://user@example.org/a", "https://example.org:8443/a", "file:///a", "https://localhost/a", "not a url").forEach {
            assertNull(it, LiveWebPages.canonical(it))
        }
    }
    @Test fun addressBarAcceptsAnySiteOrSearchWordsForYourChosenEngine() {
        assertEquals(AddressInput.Page("https://royalroad.com/fiction/1"), WebAddress.read("royalroad.com/fiction/1"))
        assertEquals(AddressInput.Page("https://example.org/a"), WebAddress.read("http://example.org/a"))
        assertEquals(AddressInput.Search("quiet road chapter 5"), WebAddress.read("quiet road chapter 5"))
        listOf("javascript:alert(1)", "intent://scan#Intent;end", "file:///sdcard/a.txt", "ftp://example.org/a", "").forEach {
            assertEquals(it, AddressInput.Invalid, WebAddress.read(it))
        }
        assertEquals("https://duckduckgo.com/?q=my+tale+chapter+2", SearchEngine.DUCKDUCKGO.url("my tale chapter 2"))
        assertEquals("https://www.google.com/search?q=a%26b", SearchEngine.GOOGLE.url("a&b"))
        SearchEngine.entries.forEach { assertNotNull(it.label, LiveWebPages.address(it.url("tale"))) }
        assertEquals(SearchEngine.BING, SearchEngine.named("BING")); assertNull(SearchEngine.named(null))
    }
    @Test fun signInIsSuggestedOncePerSiteAndNeverForSignedInVisitors() {
        val offered = requireNotNull(WebAccess.parse("1|0|https://example.org/login?next=%2Fstory"))
        assertTrue(WebAccess.suggest(offered, dismissed = false))
        assertFalse(WebAccess.suggest(offered, dismissed = true))
        assertFalse(WebAccess.suggest(WebAccess.parse("1|1|https://example.org/login"), dismissed = false))
        assertFalse(WebAccess.suggest(WebAccess.parse("0|0|"), dismissed = false))
        assertNull(WebAccess.parse("1|0|javascript:alert(1)")?.signInUrl)
        assertNull(WebAccess.parse("yes")); assertNull(WebAccess.parse(null))
        assertEquals("example.co.uk", WebAccess.siteKey("https://read.example.co.uk/story"))
    }
    @Test fun spokenWebTextDropsAnySitesPromotionsAndItsOwnAddress() {
        val spoken = NarrationText.part("He went home.\nRead the latest chapters at novelsite.com\nVisit www.novelsite.com/b/my-tale for more.\nShe smiled at the old library.",
            "", false, true, site = "https://novelsite.com/b/my-tale/chapter-1")
        assertEquals("He went home.\nShe smiled at the old library.", spoken)
        assertEquals("The road was quiet.", NarrationText.part("The road was quiet. royalroad.com", "", false, true, site = "https://www.royalroad.com/fiction/1/x/chapter/2/y"))
    }
}
