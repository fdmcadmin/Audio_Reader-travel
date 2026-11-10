package app.roadleaf

import app.roadleaf.playback.LoadingAudio
import app.roadleaf.sources.LiveWebPages
import app.roadleaf.sources.WebNavigation
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class LiveWebPagesTest {
    private val current = "https://freewebnovel.com/novel/an-original-test-story/chapter-20"
    private val next = current.replace("20", "21")
    private fun fixture(extra: String = "") = """
        <html><head><title>Original test story - Chapter 20</title>
        <meta property="og:novel:book_name" content="Original test story"></head>
        <body><a rel="next" href="$next">Next chapter</a>
        <div id="chr-content"><h3>Chapter 20: The winding road</h3>
        <p>Mara watched the trees sway beside the winding road. The rain had stopped.</p>
        <p>She read the map and quietly chose the path toward home.</p>
        <nav>Navigation should never be spoken.</nav><script>advertising()</script>
        <div class="ads">An advertisement</div></div>$extra</body></html>
    """.trimIndent()

    @Test fun extractsOnlyChapterTextAndFindsNextPageWithoutScrolling() {
        val page = LiveWebPages.parse(fixture(), current)
        assertEquals("Chapter 20: The winding road", page.title)
        assertEquals("Original test story", page.bookTitle)
        assertEquals(next, page.nextUrl)
        assertTrue(page.text.contains("quietly chose the path"))
        assertFalse(page.text.contains("Navigation")); assertFalse(page.text.contains("advertising"))
        assertFalse(page.text.contains("advertisement"))
    }
    @Test fun extractsVerifiedSiteContainerInsteadOfOuterArticleAndComments() {
        // Container/metadata names were inspected on the reported page; the prose is original.
        val html = """<html><head><title>Original story - Chapter 20</title>
            <meta property="og:novel:novel_name" content="Original story"></head><body>
            <article><nav>Prev Chapter</nav><a href="$next">Next Chapter</a><h1><a>Wrong outer title</a></h1>
            <div class="txt"><div id="article"><h4>Chapter 20: A quiet road</h4>
            <p>A traveller reached the station just before sunset. She found a letter waiting beside the door.</p>
            <p>The journey would continue after a good night's rest.</p></div></div>
            <div id="comments-section">A comment must never be narrated.</div></article></body></html>"""
        val page = LiveWebPages.parse(html, current)
        assertEquals("Original story", page.bookTitle)
        assertEquals("Chapter 20: A quiet road", page.title)
        assertEquals(next, page.nextUrl)
        assertTrue(page.text.endsWith("The journey would continue after a good night's rest."))
        assertFalse(page.text.contains("comment")); assertFalse(page.text.contains("Prev Chapter"))
        assertFalse(page.text.contains("Wrong outer title"))
    }
    @Test fun darkAndLightStylesProduceTheSameCompleteChapterWithoutScrolling() {
        fun themed(background: String, foreground: String) = """<html><head><style>
            body { background: $background; color: $foreground; } #article { margin-top: 2400px; }
            </style></head><body><div class="txt"><div id="article"><h4>Chapter 20: Night travel</h4>
            <p>The road was quiet. A traveller watched the distant lights and read the map.</p>
            <p>At the end of the chapter, she finally arrived home.</p></div></div></body></html>"""
        val light = LiveWebPages.parse(themed("white", "black"), current)
        val dark = LiveWebPages.parse(themed("#202020", "#eeeeee"), current)
        assertEquals(light, dark)
        assertTrue(dark.text.endsWith("At the end of the chapter, she finally arrived home."))
    }
    @Test fun nextMustBeExactNextChapterOfSameBookAndHost() {
        assertEquals(next, LiveWebPages.nextUrl(current, "/novel/an-original-test-story/chapter-21"))
        listOf(current, current.replace("20", "22"), next.replace("original", "different"), next.replace("freewebnovel.com", "example.com"), "javascript:alert(1)").forEach {
            assertNull(it, LiveWebPages.nextUrl(current, it))
        }
        assertNull(LiveWebPages.nextUrl(current.replace("20", Long.MAX_VALUE.toString()), next))
    }
    @Test fun shareAndAddressChecksRejectCredentialsHostSpoofsAndUnsafeSchemes() {
        assertEquals(current, LiveWebPages.sharedUrl("Read this chapter: $current"))
        assertEquals(current, LiveWebPages.canonical(current.replace("https://", "https://www.")))
        listOf("http://freewebnovel.com/novel/a/chapter-20", "https://name@freewebnovel.com/novel/a/chapter-20", "file:///sdcard/book.txt", "https://freewebnovel.com:444/novel/a/chapter-20", "javascript:alert(1)").forEach {
            assertNull(it, LiveWebPages.address(it))
        }
        // Any secure site is readable, but a look-alike host is its own site and never treated as FreeWebNovel.
        val spoof = "https://freewebnovel.com.attacker.test/novel/a/chapter-20"
        assertEquals(spoof, LiveWebPages.canonical(spoof))
        assertNull(LiveWebPages.nextUrl(current, spoof.replace("20", "21")))
    }
    @Test fun challengeMissingChapterLockedAndOversizePagesStopReading() {
        val pages = listOf(
            "<title>Just a moment</title>" + fixture(),
            "<html><article class='paywall'>${"Unlock this chapter. ".repeat(10)}</article></html>",
            "<html><div>${"This is not a chapter. ".repeat(10)}</div></html>",
            "x".repeat(LiveWebPages.MAX_HTML + 1),
            fixture().replace("Mara watched", "x".repeat(LiveWebPages.MAX_TEXT))
        )
        pages.forEach { html -> assertThrows(RuntimeException::class.java) { LiveWebPages.parse(html, current) } }
    }
    @Test fun handoffScansFromTheStartUntilThirtySecondsAndOnlyForRequestedPage() {
        val navigation = WebNavigation(7, next, 11_000)
        assertFalse(navigation.ready(10_999, next)); assertFalse(navigation.ready(11_000, current))
        assertFalse(navigation.ready(11_000, null)); assertTrue(navigation.ready(11_000, next))
        assertTrue(navigation.ready(41_000, next)); assertFalse(navigation.ready(41_001, next))
    }
    @Test fun aSettledPageIsReadAtOnceAndAnyReadablePageAfterTenSeconds() {
        val navigation = WebNavigation(7, next, 11_000)
        // Before ten seconds: only a finished page whose text has stayed the same for a moment.
        assertFalse(navigation.accepts(12_000, finishedLoading = true, unchangedForMs = 0))
        assertFalse(navigation.accepts(12_000, finishedLoading = true, unchangedForMs = LiveWebPages.SETTLE_MS - 1))
        assertFalse(navigation.accepts(12_000, finishedLoading = false, unchangedForMs = 5_000))
        assertTrue(navigation.accepts(12_000, finishedLoading = true, unchangedForMs = LiveWebPages.SETTLE_MS))
        // From ten seconds on, as before: the first readable scan.
        assertTrue(navigation.accepts(21_000, finishedLoading = false, unchangedForMs = 0))
        // Quick scans while settling, then the two-second pace, never past the 30-second limit.
        assertEquals(LiveWebPages.QUICK_SCAN_MS, navigation.scanDelay(12_000))
        assertEquals(LiveWebPages.SCAN_INTERVAL_MS, navigation.scanDelay(21_000))
        assertEquals(500L, navigation.scanDelay(40_500))
        assertEquals(1L, navigation.scanDelay(41_000))
    }
    @Test fun theNextChapterIsReadEarlyNearTheEndOfThisOne() {
        assertFalse(LiveWebPages.prefetchDue(textLength = 20_000, preparedEnd = 2_000, sourceExhausted = false))
        assertTrue(LiveWebPages.prefetchDue(textLength = 20_000, preparedEnd = 20_000 - LiveWebPages.PREFETCH_CHARS, sourceExhausted = false))
        assertTrue(LiveWebPages.prefetchDue(textLength = 20_000, preparedEnd = 3_000, sourceExhausted = true))
        // A short chapter is read ahead from its start.
        assertTrue(LiveWebPages.prefetchDue(textLength = 1_800, preparedEnd = 300, sourceExhausted = false))
    }
    @Test fun webpagePlayCannotResumeLibraryAudioOrAnotherChapter() {
        assertFalse(LiveWebPages.canResume(current, null, false)) // EPUB audio has no web URL.
        assertFalse(LiveWebPages.canResume(current, next, false))
        assertFalse(LiveWebPages.canResume(current, current, true))
        assertFalse(LiveWebPages.canResume("", current, false))
        assertTrue(LiveWebPages.canResume(current, current, false))
        assertTrue(LiveWebPages.canResume(current, current.replace("https://", "https://www."), false))
    }
    @Test fun networkPolicyBlocksThirdPartyMediaAndUnencryptedResources() {
        // The verified FreeWebNovel page keeps its original first-party-only policy.
        assertTrue(LiveWebPages.permittedResource(URI("https://freewebnovel.com/static/chapter.js"), current))
        assertTrue(LiveWebPages.permittedResource(URI("https://challenges.cloudflare.com/turnstile/v0/api.js"), current))
        listOf("http://freewebnovel.com/style.css", "https://ads.example.test/tracker.js", "https://freewebnovel.com/cover.jpg?size=large", "https://freewebnovel.com/video.mp4", "file:///sdcard/file").forEach {
            assertFalse(it, LiveWebPages.permittedResource(URI(it), current))
        }
        // Other sites load their own scripts; ads, trackers, media and fonts never load; images only when allowed.
        val page = "https://example.org/story/my-tale/chapter-2"
        assertTrue(LiveWebPages.permittedResource(URI("https://cdn.example-cdn.test/app.js"), page))
        assertTrue(LiveWebPages.permittedResource(URI("https://example.org/cover.jpg"), page, images = true))
        listOf("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://www.googletagmanager.com/gtm.js", "https://example.org/cover.jpg",
            "https://example.org/font.woff2", "https://example.org/intro.mp4", "http://example.org/app.js").forEach {
            assertFalse(it, LiveWebPages.permittedResource(URI(it), page))
        }
    }
    @Test fun loadingAudioIsLocalBoundedSilenceAndReused() {
        val directory = Files.createTempDirectory("roadleaf-loading").toFile()
        try {
            val file = LoadingAudio.file(directory)
            assertEquals(1_920_044L, file.length())
            val bytes = file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(8000, header.getInt(24)); assertEquals(1_920_000, header.getInt(40))
            assertTrue(bytes.drop(44).all { it == 0.toByte() })
            val modified = file.lastModified(); assertEquals(file, LoadingAudio.file(directory)); assertEquals(modified, file.lastModified())
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
