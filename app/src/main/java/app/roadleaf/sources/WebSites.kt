package app.roadleaf.sources

import java.net.URI

/**
 * Optional site extras. Any secure page is readable without an entry here; rules only point the
 * generic reader at a known chapter container, title or next link. They never unlock anything.
 */
internal data class SiteRules(
    val name: String,
    val hosts: Set<String>,
    val content: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
    val chapterTitle: List<String> = emptyList(),
    val bookTitle: List<String> = emptyList(),
    val next: List<String> = emptyList(),
)

internal object WebSites {
    val rules = listOf(
        SiteRules("Royal Road", setOf("royalroad.com"), content = listOf(".chapter-content", ".chapter-inner"),
            remove = listOf(".author-note-portlet", ".author-note"), chapterTitle = listOf(".fic-header h1", "h1"),
            bookTitle = listOf(".fic-header h2", ".fic-header a.font-white"), next = listOf("a:matchesOwn((?i)^\\s*next\\s+chapter\\s*$)")),
        SiteRules("AO3", setOf("archiveofourown.org"), content = listOf("#chapters div.userstuff[role=article]", "#chapters .userstuff", "#workskin .userstuff"),
            remove = listOf(".landmark", "h3.landmark"), chapterTitle = listOf("div.chapter h3.title", "h2.title.heading"),
            bookTitle = listOf("h2.title.heading"), next = listOf("li.chapter.next a", "a:matchesOwn((?i)^\\s*next\\s+chapter)")),
        SiteRules("FanFiction", setOf("fanfiction.net", "fictionpress.com", "m.fanfiction.net", "m.fictionpress.com"), content = listOf("#storytext", "#storytextp"),
            chapterTitle = listOf("select#chap_select option[selected]"), bookTitle = listOf("#profile_top b.xcontrast_txt", "#content b")),
        SiteRules("Scribble Hub", setOf("scribblehub.com"), content = listOf("#chp_raw", ".chp_raw"), chapterTitle = listOf(".chapter-title"),
            bookTitle = listOf(".chp_byauthor a", ".wi_fic_title"), next = listOf("a.btn-next")),
        SiteRules("Webnovel", setOf("webnovel.com", "m.webnovel.com"), content = listOf(".cha-words", ".cha-content", ".chapter_content")),
        SiteRules("Wattpad", setOf("wattpad.com"), content = listOf("div[data-page-number] pre", ".part-content pre", "pre")),
    )

    fun rules(host: String): SiteRules? = rules.firstOrNull { rule -> rule.hosts.any { host == it || host.endsWith(".$it") } }

    /** Advertising and tracking hosts are never loaded, saving data and battery. Ordinary site scripts still load. */
    private val blocked = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "googletagmanager.com",
        "googletagservices.com", "adservice.google.com", "amazon-adsystem.com", "adnxs.com", "adsrvr.org", "rubiconproject.com",
        "pubmatic.com", "openx.net", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "scorecardresearch.com",
        "quantserve.com", "moatads.com", "hotjar.com", "mgid.com", "popads.net", "adsterra.com", "exoclick.com", "juicyads.com",
        "propellerads.com", "revcontent.com", "teads.tv", "33across.com", "media.net", "adform.net", "smartadserver.com",
        "casalemedia.com", "indexww.com", "sovrn.com", "lijit.com", "bidswitch.net", "3lift.com", "contextweb.com", "yieldmo.com",
        "sharethrough.com", "zedo.com", "onclickads.net", "mc.yandex.ru", "an.yandex.ru", "clarity.ms", "newrelic.com", "nr-data.net",
    )
    fun blockedHost(host: String) = blocked.any { host == it || host.endsWith(".$it") }

    private val secondLevel = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")
    /** Approximate registrable domain, enough to keep next-chapter links on the same site. */
    fun site(host: String): String {
        val labels = host.lowercase().removePrefix("www.").split('.').filter(String::isNotBlank)
        if (labels.size <= 2) return labels.joinToString(".")
        val take = if (labels[labels.size - 2] in secondLevel && labels.last().length == 2) 3 else 2
        return labels.takeLast(take).joinToString(".")
    }
    fun sameSite(a: URI, b: URI) = SourcePolicy.host(a).isNotBlank() && site(SourcePolicy.host(a)) == site(SourcePolicy.host(b))
}
