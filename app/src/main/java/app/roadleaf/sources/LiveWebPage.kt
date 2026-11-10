package app.roadleaf.sources

import org.jsoup.Jsoup
import java.net.URI
import app.roadleaf.playback.NarrationText

data class LiveWebPage(val url: String, val title: String, val bookTitle: String, val text: String, val nextUrl: String?)
/**
 * A chapter page for Roadleaf's reader to read. Scanning starts as soon as the page loads: a page that has finished
 * loading and reads the same twice in a row is used at once. From [relaxedAfterMs] (10 s) the first readable scan is
 * used, as before, and [deadlineMs] (30 s) is the limit. An [early] request reads the next chapter while the current
 * one is still playing; it never starts or stops playback by itself.
 */
data class WebNavigation(val id: Long, val url: String, val startedMs: Long, val early: Boolean = false) {
    val relaxedAfterMs get() = startedMs + LiveWebPages.FIRST_SCAN_MS
    val deadlineMs get() = startedMs + LiveWebPages.MAX_LOAD_MS
    fun ready(nowMs: Long, loadedUrl: String?): Boolean = nowMs in startedMs..deadlineMs &&
        loadedUrl != null && LiveWebPages.canonical(loadedUrl) == url
    /**
     * A readable scan is used once the page has finished loading and its text has stayed the same for
     * [LiveWebPages.SETTLE_MS], or once the first 10 s have passed.
     */
    fun accepts(nowMs: Long, finishedLoading: Boolean, unchangedForMs: Long): Boolean =
        nowMs >= relaxedAfterMs || (finishedLoading && unchangedForMs >= LiveWebPages.SETTLE_MS)
    /** Quick scans while the page settles, then the original two-second pace, never past the deadline. */
    fun scanDelay(nowMs: Long): Long = (if (nowMs < relaxedAfterMs) LiveWebPages.QUICK_SCAN_MS else LiveWebPages.SCAN_INTERVAL_MS)
        .coerceAtMost((deadlineMs - nowMs).coerceAtLeast(1))
}

/**
 * Normal visible-browser reading of any secure page the owner opens. It reads only text the page
 * already shows. It does not grant download rights, bypass sign-in, locks, paywalls or site checks.
 */
object LiveWebPages {
    const val MAX_HTML = 1_000_000
    const val MAX_TEXT = 120_000
    /** Until then a scan must read the same twice on a finished page; afterwards any readable scan is used. */
    const val FIRST_SCAN_MS = 10_000L
    const val MAX_LOAD_MS = 30_000L
    const val SCAN_INTERVAL_MS = 2_000L
    const val QUICK_SCAN_MS = 600L
    /** How long a finished page's text must stay unchanged before it is read early, so late paragraphs aren't cut off. */
    const val SETTLE_MS = 1_200L
    /** The next chapter is read early once no more than this much of the current chapter is left to prepare. */
    const val PREFETCH_CHARS = 2_500

    fun prefetchDue(textLength: Int, preparedEnd: Int, sourceExhausted: Boolean): Boolean =
        sourceExhausted || textLength - preparedEnd <= PREFETCH_CHARS
    const val MAX_URL = 2048
    private val tracking = Regex("^(?:utm_[a-z0-9_]*|fbclid|gclid|dclid|msclkid|igshid|mc_cid|mc_eid|_ga|_gl|yclid)$", RegexOption.IGNORE_CASE)
    private val mediaFile = Regex("\\.(?:mp4|webm|mp3|m4a|ogg|wav|flac|woff2?|ttf|otf|eot)$", RegexOption.IGNORE_CASE)
    private val imageFile = Regex("\\.(?:png|jpe?g|gif|webp|svg|ico|avif|bmp)$", RegexOption.IGNORE_CASE)

    /**
     * Marks CSS-hidden elements (anti-copy or decoy text the reader cannot see), drops scripts and other
     * heavy nodes from a copy, and returns HTML within the size limit. The live page is left unchanged.
     */
    val SNAPSHOT_SCRIPT = """(function(){var max=$MAX_HTML;try{var marked=[];var body=document.body;
        |if(body){var all=body.getElementsByTagName('*');var limit=Math.min(all.length,15000);
        |for(var i=0;i<limit;i++){var e=all[i];var s=window.getComputedStyle(e);
        |if(s&&(s.display==='none'||s.visibility==='hidden')){e.setAttribute('data-roadleaf-hidden','1');marked.push(e);}}}
        |var copy=document.documentElement.cloneNode(true);
        |for(var j=0;j<marked.length;j++){marked[j].removeAttribute('data-roadleaf-hidden');}
        |var heavy=copy.querySelectorAll('script,style,noscript,template,svg,iframe,canvas,video,audio,picture');
        |for(var k=0;k<heavy.length;k++){if(heavy[k].parentNode){heavy[k].parentNode.removeChild(heavy[k]);}}
        |var h=copy.outerHTML;return h.length<=max?h:null;}catch(err){var h2=document.documentElement.outerHTML;return h2.length<=max?h2:null;}})()""".trimMargin().replace("\n", "")

    /** Media and fonts are never fetched; images only when the owner allows them. Ads/trackers are blocked. */
    fun permittedResource(uri: URI, mainUrl: String? = null, images: Boolean = false): Boolean {
        if (!SourcePolicy.secure(uri)) return false
        val main = mainUrl?.let { runCatching { URI(it) }.getOrNull() }
        if (main != null && FreeWebNovel.site(main)) return FreeWebNovel.permittedResource(uri)
        val host = SourcePolicy.host(uri)
        if (host.isBlank() || WebSites.blockedHost(host)) return false
        val path = uri.path.orEmpty()
        if (mediaFile.containsMatchIn(path)) return false
        return images || !imageFile.containsMatchIn(path)
    }

    /** Any secure page address: https only, no embedded credentials, default port. */
    fun address(url: String): URI? = runCatching {
        val text = url.trim()
        if (text.isEmpty() || text.length > MAX_URL || text.any(Char::isWhitespace)) return null
        URI(text).takeIf { SourcePolicy.secure(it) && !it.host.isNullOrBlank() && it.host.contains('.') }
    }.getOrNull()

    /** One stable identity per page: no fragment, tracking parameters, `www.` or trailing slash. */
    fun canonical(url: String): String? {
        val uri = address(url) ?: return null
        FreeWebNovel.canonical(uri)?.let { return it }
        return runCatching {
            val normalized = uri.normalize()
            var path = normalized.rawPath.orEmpty().ifBlank { "/" }
            if (path.startsWith("/..")) return null
            if (path.length > 1) path = path.trimEnd('/').ifBlank { "/" }
            val query = normalized.rawQuery?.split('&')?.filter { it.isNotBlank() && !tracking.matches(it.substringBefore('=')) }
                ?.joinToString("&")?.takeIf { it.isNotBlank() }
            ("https://${SourcePolicy.host(uri)}$path" + (query?.let { "?$it" } ?: "")).takeIf { it.length <= MAX_URL }
        }.getOrNull()
    }

    fun sameSite(a: String, b: String): Boolean {
        val first = address(a) ?: return false
        val second = address(b) ?: return false
        return WebSites.sameSite(first, second)
    }

    /** A library item or a different chapter must never be resumed by the webpage's Play button. */
    fun canResume(displayedUrl: String, audioUrl: String?, loadingOrEnded: Boolean): Boolean {
        val displayed = canonical(displayedUrl) ?: return false
        return !loadingOrEnded && audioUrl != null && displayed == canonical(audioUrl)
    }

    /** The next chapter must stay on the same site and cannot point back up to the book index. */
    fun nextUrl(current: String, candidate: String): String? {
        val first = address(current) ?: return null
        val value = candidate.trim()
        if (value.isEmpty() || value.length > MAX_URL) return null
        // Java resolves "?page=2" against the parent folder; browsers keep the current path (RFC 3986).
        val resolved = runCatching {
            if (value.startsWith("?")) URI(first.toString().substringBefore('#').substringBefore('?') + value) else first.resolve(value)
        }.getOrNull() ?: return null
        if (FreeWebNovel.handles(first)) return FreeWebNovel.nextUrl(first, resolved)
        val here = canonical(current) ?: return null
        val next = canonical(resolved.toString()) ?: return null
        if (next == here) return null
        val hereUri = URI(here); val nextUri = URI(next)
        if (!WebSites.sameSite(hereUri, nextUri)) return null
        val herePath = hereUri.rawPath.orEmpty(); val nextPath = nextUri.rawPath.orEmpty()
        if (nextPath == "/" || (nextUri.rawQuery == null && herePath.startsWith(nextPath.trimEnd('/') + "/"))) return null
        return next
    }

    fun sharedUrl(text: String): String? = Regex("https://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text.take(4096))
        .mapNotNull { canonical(it.value.trimEnd('.', ',', ')', ']', '"')) }.firstOrNull()

    fun parse(html: String, url: String): LiveWebPage {
        val canonical = canonical(url) ?: error("Open a secure https page first, then choose Read loaded chapter.")
        require(html.length <= MAX_HTML) { "This page is too large for live reading." }
        val uri = URI(canonical)
        if (FreeWebNovel.handles(uri)) return FreeWebNovel.parse(html, canonical)
        val doc = Jsoup.parse(html, canonical)
        WebExtraction.verification(doc)?.let { error(it) }
        val rules = WebSites.rules(SourcePolicy.host(uri))
        val content = WebExtraction.content(doc, rules)
        val text = content?.let(WebExtraction::text)
        WebExtraction.problem(doc, text)?.let { error(it) }
        require(content != null && text != null && text.length >= 40) {
            "No readable story text was found on this page. Open the chapter itself, then choose Read loaded chapter."
        }
        require(text.length <= MAX_TEXT) { "This page has more text than one live reading can hold." }
        val link = WebBookLinks.resolve(canonical)
        val chapterTitle = WebExtraction.chapterTitle(doc, content, rules)
            ?: link?.chapterTitle?.takeIf { it.isNotBlank() } ?: "Web page"
        val bookTitle = WebExtraction.bookTitle(doc, rules, chapterTitle, SourcePolicy.host(uri))
            ?: link?.title ?: SourcePolicy.host(uri)
        val next = WebExtraction.nextCandidates(doc, rules).firstNotNullOfOrNull { nextUrl(canonical, it) }
        return LiveWebPage(canonical, NarrationText.displayTitle(chapterTitle).take(240), bookTitle.take(240), text, next)
    }
}

/** The original verified FreeWebNovel reader, kept byte-for-byte so saved chapter positions stay valid. */
internal object FreeWebNovel {
    private val chapterPath = Regex("^/novel/([a-zA-Z0-9-]+)/chapter-([0-9]+)$")
    // Verified against the visible chapter-20 page. Prefer the specific chapter over any outer article.
    private val contentSelectors = listOf("#article", "#chr-content", ".chr-c", "#chapter-content", ".chapter-content", "article")

    fun site(uri: URI) = SourcePolicy.host(uri) == "freewebnovel.com"
    fun handles(uri: URI) = SourcePolicy.secure(uri) && site(uri) && chapterPath.matches(uri.path.orEmpty())
    fun canonical(uri: URI): String? = if (handles(uri)) "https://freewebnovel.com${uri.path}" else null

    fun permittedResource(uri: URI): Boolean {
        if (!SourcePolicy.secure(uri)) return false
        val host = SourcePolicy.host(uri)
        if (host !in setOf("freewebnovel.com", "challenges.cloudflare.com")) return false
        // Avoid images, media and fonts; preserve ordinary first-party page and verification scripts.
        return !uri.path.orEmpty().contains(Regex("\\.(?:png|jpe?g|gif|webp|svg|ico|avif|mp4|webm|mp3|m4a|ogg|woff2?|ttf)(?:$)", RegexOption.IGNORE_CASE))
    }

    fun nextUrl(first: URI, next: URI): String? {
        if (!handles(first) || !handles(next)) return null
        val a = chapterPath.matchEntire(first.path)!!.groupValues
        val b = chapterPath.matchEntire(next.path)!!.groupValues
        val number = a[2].toLongOrNull() ?: return null
        if (number == Long.MAX_VALUE || a[1] != b[1] || number + 1 != b[2].toLongOrNull()) return null
        return canonical(next)
    }

    fun parse(html: String, canonical: String): LiveWebPage {
        val doc = Jsoup.parse(html, canonical)
        require(!doc.title().contains(Regex("just a moment|access denied|verify.*human|sign in|log in", RegexOption.IGNORE_CASE))) {
            "Finish the site's verification in the visible page, then choose Read this chapter."
        }
        val content = contentSelectors.firstNotNullOfOrNull { doc.selectFirst(it) }
            ?: error("Chapter text was not found in this page's layout. Try Read loaded chapter; if this repeats, the reader needs a site update.")
        require(doc.select(".paywall, .chapter-locked, #cf-challenge-running, #challenge-form").isEmpty()) {
            "This page needs attention. Restricted chapters and verification screens are not read."
        }
        val chapterTitle = content.selectFirst("h1,h2,h3,h4")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.select("h1,h2,h3,h4").firstOrNull { it.text().contains(Regex("chapter\\s+\\d+", RegexOption.IGNORE_CASE)) }?.text()
            ?: "Chapter ${chapterPath.matchEntire(URI(canonical).path)!!.groupValues[2]}"
        val bookTitle = doc.selectFirst("meta[property=og:novel:novel_name], meta[property=og:novel:book_name]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1 a")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore(" - Chapter", doc.title()).substringBefore(" | ").ifBlank { "Web chapter" }
        val first = URI(canonical)
        val next = doc.select("a[href]").asSequence().filter {
            it.attr("rel").split(' ').contains("next") || it.id().startsWith("next_chap") ||
                it.text().trim().matches(Regex("next(?:\\s+chapter)?\\s*[›»→]?", RegexOption.IGNORE_CASE))
        }.mapNotNull { link -> runCatching { first.resolve(link.attr("href")) }.getOrNull()?.let { nextUrl(first, it) } }.firstOrNull()
        val readable = content.clone()
        readable.select("script,style,nav,header,footer,aside,button,form,iframe,video,audio,.ads,.ad,.advertisement,.comments,.comment-list,.comment-form,.chapter-footer,.chapter-nav,.chapter-navigation,.reader-settings,.reading-controls,.site-footer,#footer,#comments,#comments-section,#comment-form,[hidden]").remove()
        readable.select("p,div,br,h1,h2,h3,h4,li").forEach { it.appendText("\n") }
        val text = readable.wholeText().replace(Regex("[\\t ]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        require(text.length in 40..LiveWebPages.MAX_TEXT) { "No complete readable chapter found. Check the visible page; locked or verification pages are not read." }
        return LiveWebPage(canonical, NarrationText.displayTitle(chapterTitle).take(240), bookTitle.take(240), text, next)
    }
}
