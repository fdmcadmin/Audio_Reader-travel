package app.roadleaf.sources

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Finds the main readable text of an ordinary page: the densest block of story paragraphs, minus
 * menus, comments, ads and text the visible page hides. Bounded heuristics, not a site crawler.
 */
internal object WebExtraction {
    private val blockTags = setOf("address", "article", "aside", "blockquote", "details", "dialog", "dd", "div", "dl", "dt", "fieldset",
        "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "li", "main", "nav", "ol",
        "p", "pre", "section", "table", "tbody", "thead", "tfoot", "tr", "td", "th", "ul")
    private const val HIDDEN = "[data-roadleaf-hidden], [hidden], [style~=(?i)display\\s*:\\s*none], [style~=(?i)visibility\\s*:\\s*hidden]"
    private const val NOISE = "script, style, noscript, template, svg, canvas, iframe, object, embed, button, input, select, textarea, " +
        "nav, aside, dialog, video, audio, picture, img, [role=navigation], [role=banner], [role=contentinfo], " +
        "[role=complementary], [role=search], [role=dialog], [role=alertdialog], .ads, .ad, .advertisement, .adsbygoogle, .sponsored, " +
        ".comments, .comment-list, .comment-form, #comments, #comments-section, #comment-form, #disqus_thread, .chapter-footer, " +
        ".chapter-nav, .chapter-navigation, .reader-settings, .reading-controls, .site-footer, #footer, .breadcrumb, .breadcrumbs, " +
        ".share, .sharing, .social, .sidebar, #sidebar, .related, .recommendations, .newsletter, .cookie, .cookies, .modal, .popup, .pagination, .pager"
    // Some sites wrap the whole page in one form or header; those are removed only when they are a minor part.
    private const val STRUCTURE = "form, header, footer"
    private val commentArea = Regex("comment|disqus|reply|review", RegexOption.IGNORE_CASE)
    private val positive = Regex("article|body|content|entry|main|page|post|text|story|chapter|chr|read|novel|userstuff|prose", RegexOption.IGNORE_CASE)
    private val negative = Regex("comment|meta|footer|footnote|sidebar|widget|related|share|social|nav|menu|breadcrumb|promo|advert|sponsor|popup|modal|cookie|subscribe|newsletter|banner|login|signup|rating", RegexOption.IGNORE_CASE)
    private val verificationTitle = Regex("just a moment|access denied|attention required|verify.*human|are you (?:a )?(?:robot|human)|captcha|security check", RegexOption.IGNORE_CASE)
    private val signInPhrase = Regex("(?:sign|log)\\s*in to (?:read|continue|unlock|view)|login to (?:read|continue|unlock|view)|please (?:sign|log)\\s*in|members only|account required", RegexOption.IGNORE_CASE)
    private val lockPhrase = Regex("unlock (?:this|the|next) chapter|this chapter is locked|chapter (?:is )?locked|locked chapter|(?:purchase|buy) (?:this|the) chapter|subscribe to (?:read|continue|unlock)|coins? to unlock|unlock (?:it )?with|premium chapter|available to (?:patrons|members|subscribers)|become a (?:patron|member|subscriber) to read", RegexOption.IGNORE_CASE)
    private val nextLabel = Regex("^(?:next(?:\\s+(?:chapter|page|part|episode|ch\\.?))?|下一章|下一页|siguiente|suivant|weiter|próximo)\\s*[›»→>]*$|^[›»→]+$|^>>$", RegexOption.IGNORE_CASE)
    private val chapterLike = Regex("\\b(?:chapter|ch\\.?|episode|part)\\s*\\d+", RegexOption.IGNORE_CASE)

    const val LOCKED = "Locked or paid chapters are not read. If your account has access, unlock it on the site, then choose Read loaded chapter."
    const val SIGN_IN = "This page needs your account. Sign in on the site, then choose Read loaded chapter."
    const val CHECK = "Finish the site's check in the visible page, then choose Read loaded chapter."

    fun verification(doc: Document): String? =
        if (verificationTitle.containsMatchIn(doc.title()) ||
            doc.select("#cf-challenge-running, #challenge-form, #challenge-stage, .cf-turnstile, iframe[src*=challenges.cloudflare.com]").isNotEmpty()) CHECK else null

    /** Gates are reported, never worked around: sign in or unlock on the site, then read again. */
    fun problem(doc: Document, text: String?): String? {
        val short = text == null || text.length < 1500
        if (!short) return null
        val sample = text ?: doc.body()?.text().orEmpty().take(20_000)
        if (signInPhrase.containsMatchIn(sample)) return SIGN_IN
        if (doc.select(".paywall, .chapter-locked, .locked-chapter, [class*=paywall]").isNotEmpty() || lockPhrase.containsMatchIn(sample)) return LOCKED
        if ((text?.length ?: 0) < 600 && doc.select("input[type=password]").isNotEmpty()) return SIGN_IN
        return null
    }

    /** CSS rules that hide decoy text with a class (outside media queries). Only short blocks are dropped. */
    private fun hiddenClasses(doc: Document): Set<String> {
        val classes = mutableSetOf<String>()
        doc.select("style").take(40).forEach { style ->
            val css = style.data().take(200_000).replace(Regex("@media[^{]*\\{(?:[^{}]*\\{[^{}]*\\})*[^{}]*\\}"), " ")
            Regex("([^{}]+)\\{([^{}]*)\\}").findAll(css).forEach { rule ->
                if (rule.groupValues[2].contains(Regex("display\\s*:\\s*none|visibility\\s*:\\s*hidden", RegexOption.IGNORE_CASE)))
                    rule.groupValues[1].split(',').map(String::trim).forEach { selector ->
                        Regex("^[a-zA-Z0-9]*\\.([A-Za-z_][\\w-]*)$").matchEntire(selector)?.let { classes += it.groupValues[1] }
                    }
            }
        }
        return classes.take(200).toSet()
    }

    private fun removeHidden(root: Element, classes: Set<String>) {
        root.select(HIDDEN).remove()
        classes.forEach { name -> root.getElementsByClass(name).filter { it.text().length < 400 }.forEach(Element::remove) }
    }

    fun content(doc: Document, rules: SiteRules?): Element? {
        val body = doc.body() ?: return null
        val work = body.clone()
        removeHidden(work, hiddenClasses(doc))
        rules?.remove?.forEach { work.select(it).remove() }
        rules?.content?.firstNotNullOfOrNull { selector ->
            work.select(selector).maxByOrNull { it.text().length }?.takeIf { it.text().length >= 40 }
        }?.let { return clean(it) }
        strip(work)
        return best(work)?.let(::clean)
    }

    private fun strip(root: Element) {
        root.select(NOISE).remove()
        val total = root.text().length
        root.select(STRUCTURE).filter { it.text().length * 2 < total }.forEach(Element::remove)
    }

    private fun clean(element: Element): Element = element.clone().also(::strip)

    private fun inline(element: Element): Pair<Int, Int> {
        var text = 0; var links = 0
        for (node in element.childNodes()) when (node) {
            is TextNode -> text += node.text().trim().length
            is Element -> if (node.tagName() !in blockTags) {
                val length = node.text().trim().length
                text += length
                links += if (node.tagName() == "a") length else node.select("a").sumOf { it.text().trim().length }
            }
        }
        return text to links
    }

    private fun weight(element: Element): Double {
        val names = element.className() + " " + element.id()
        var factor = 1.0
        if (positive.containsMatchIn(names)) factor *= 1.25
        if (negative.containsMatchIn(names)) factor *= 0.5
        return factor
    }

    private fun linkDensity(element: Element): Double {
        val total = element.text().length
        if (total == 0) return 1.0
        return element.select("a").sumOf { it.text().length }.toDouble() / total
    }

    private fun best(root: Element): Element? {
        val scores = LinkedHashMap<Element, Double>()
        for (element in root.getAllElements().take(30_000)) {
            val (length, links) = inline(element)
            if (length < 25 || links * 2 > length) continue
            val commas = element.ownText().count { it == ',' || it == '，' }
            val score = 1.0 + minOf(length / 100.0, 3.0) + minOf(commas, 10) * 0.25
            scores[element] = (scores[element] ?: 0.0) + score
            element.parent()?.let { scores[it] = (scores[it] ?: 0.0) + score }
            element.parent()?.parent()?.let { scores[it] = (scores[it] ?: 0.0) + score / 2 }
        }
        // Ties favor the deeper, more specific container (later in document order for nested blocks).
        return scores.entries.map { (element, score) -> element to score * weight(element) * (1 - linkDensity(element)) }
            .filter { it.second >= 2.0 }
            .maxWithOrNull(compareBy<Pair<Element, Double>> { it.second }.thenBy { it.first.parents().size })?.first
    }

    fun text(element: Element): String {
        val readable = element.clone()
        readable.select("p, div, br, h1, h2, h3, h4, h5, h6, li, pre, blockquote, section, article, tr").forEach { it.appendText("\n") }
        return readable.wholeText().replace(Regex("[\\t\\u00A0 ]+"), " ").replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun heading(value: String?) = value?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() && it.length <= 200 }

    fun chapterTitle(doc: Document, content: Element, rules: SiteRules?): String? =
        rules?.chapterTitle?.firstNotNullOfOrNull { heading(doc.selectFirst(it)?.text()) }
            ?: heading(content.selectFirst("h1, h2, h3, h4")?.text())
            ?: doc.select("h1, h2, h3").firstOrNull { chapterLike.containsMatchIn(it.text()) }?.let { heading(it.text()) }
            ?: heading(doc.selectFirst("h1")?.text())
            ?: heading(doc.selectFirst("meta[property=og:title]")?.attr("content"))
            ?: heading(doc.title().split(Regex("\\s+[-|–—»]\\s+")).firstOrNull())

    fun bookTitle(doc: Document, rules: SiteRules?, chapter: String, host: String): String? {
        rules?.bookTitle?.firstNotNullOfOrNull { heading(doc.selectFirst(it)?.text()) }?.let { return it }
        heading(doc.selectFirst("meta[property=og:novel:book_name], meta[property=og:novel:novel_name], meta[name=book_title]")?.attr("content"))?.let { return it }
        val site = doc.selectFirst("meta[property=og:site_name]")?.attr("content").orEmpty().trim()
        val siteLabel = host.substringBefore('.')
        fun book(value: String) = value.isNotBlank() && !chapter.contains(value, true) && !value.contains(chapter, true) &&
            !chapterLike.containsMatchIn(value) && !value.equals(site, true) && !value.contains(host, true) &&
            !value.equals(siteLabel, true) && !value.equals("home", true)
        doc.select(".breadcrumb a, .breadcrumbs a, [itemtype*=BreadcrumbList] [itemprop=name]").map { it.text().trim() }
            .lastOrNull(::book)?.let { heading(it) }?.let { return it }
        return doc.title().split(Regex("\\s+[-|–—»]\\s+")).map(String::trim).firstOrNull(::book)?.let(::heading)
    }

    /** Candidate next links, most explicit first. Each is validated by [LiveWebPages.nextUrl]. */
    fun nextCandidates(doc: Document, rules: SiteRules?): List<String> {
        val out = mutableListOf<String>()
        rules?.next?.forEach { selector -> doc.select(selector).forEach { out += it.attr("href") } }
        doc.select("link[href][rel~=(?i)(^|\\s)next(\\s|$)]").forEach { out += it.attr("href") }
        doc.select("a[href][rel~=(?i)(^|\\s)next(\\s|$)]").forEach { out += it.attr("href") }
        fun label(element: Element) = element.text().trim().ifBlank { element.attr("aria-label").ifBlank { element.attr("title") } }.trim()
        // Comment threads have their own "Next" pages; those are never the next chapter.
        val anchors = doc.select("a[href]").take(5000).filterNot { link ->
            link.parents().takeWhile { it.tagName() != "body" }.any { commentArea.containsMatchIn(it.className() + " " + it.id()) }
        }
        anchors.filter { label(it).matches(Regex("(?i)^next\\s+chapter.*")) }.forEach { out += it.attr("href") }
        anchors.filter { nextLabel.matches(label(it)) }.forEach { out += it.attr("href") }
        anchors.filter {
            val names = (it.id() + " " + it.className()).lowercase()
            names.contains("next") && !names.contains("prev") && label(it).length <= 40
        }.forEach { out += it.attr("href") }
        doc.select("[onclick]").take(500).filter { nextLabel.matches(label(it)) }.forEach { element ->
            Regex("location(?:\\.href)?\\s*=\\s*['\"]([^'\"]+)['\"]").find(element.attr("onclick"))?.let { out += it.groupValues[1] }
        }
        return out.map(String::trim).filter(String::isNotBlank).distinct().take(40)
    }
}
