package app.roadleaf.sources

import java.net.URLEncoder

/** The owner's choice of search engine. Roadleaf has no default preference among them. */
enum class SearchEngine(val label: String, private val template: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s"),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=%s"),
    QWANT("Qwant", "https://www.qwant.com/?q=%s"),
    YAHOO("Yahoo", "https://search.yahoo.com/search?p=%s"),
    YANDEX("Yandex", "https://yandex.com/search/?text=%s"),
    NAVER("Naver", "https://search.naver.com/search.naver?query=%s"),
    BAIDU("Baidu", "https://www.baidu.com/s?wd=%s");

    fun url(query: String): String = template.replace("%s", URLEncoder.encode(query.trim().take(500), "UTF-8"))
    companion object { fun named(name: String?): SearchEngine? = entries.firstOrNull { it.name == name } }
}

sealed interface AddressInput {
    data class Page(val url: String) : AddressInput
    data class Search(val query: String) : AddressInput
    data object Invalid : AddressInput
}

/** Address bar text: a secure page, a bare domain (upgraded to https) or words to search for. */
object WebAddress {
    private val domainLike = Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
    private val otherScheme = Regex("^(?:javascript|vbscript|data|file|intent|content|about|chrome|market|mailto|tel|sms|blob):", RegexOption.IGNORE_CASE)

    fun read(input: String): AddressInput {
        val text = input.trim()
        if (text.isEmpty() || text.length > LiveWebPages.MAX_URL) return AddressInput.Invalid
        fun page(url: String) = LiveWebPages.address(url)?.let { AddressInput.Page(it.toString()) } ?: AddressInput.Invalid
        return when {
            text.startsWith("https://", true) -> page(text)
            text.startsWith("http://", true) -> page("https://" + text.substring(7))
            text.contains("://") -> AddressInput.Invalid
            !text.any(Char::isWhitespace) && domainLike.matches(text) -> page("https://$text")
            otherScheme.containsMatchIn(text) && !text.any(Char::isWhitespace) -> AddressInput.Invalid
            else -> AddressInput.Search(text)
        }
    }
}

data class SignInHint(val offered: Boolean, val signedIn: Boolean, val signInUrl: String?)

/**
 * Sign-in is preferred, never required: a site that offers accounts gets a gentle suggestion.
 * Roadleaf never sees or stores passwords; the site keeps its own sign-in in this reader.
 */
object WebAccess {
    /** Returns `offered|signedIn|url` from visible link and button labels only. */
    const val SCRIPT = """(function(){var inRe=/^\s*(sign\s*in|log\s*in|login|sign\s*in\s*\/\s*(register|sign\s*up)|log\s*in\s*\/\s*(register|sign\s*up))\s*$/i;var outRe=/^\s*(sign\s*out|log\s*out|logout|my\s*account|my\s*profile|account\s*settings)\s*$/i;var items=document.querySelectorAll('a,button,[role=button]');var offered=false,signed=false,url='';for(var i=0;i<items.length&&i<3000;i++){var t=((items[i].innerText||'')+'').trim();if(!t){t=((items[i].getAttribute('aria-label')||'')+'').trim();}if(!t||t.length>40){continue;}if(outRe.test(t)){signed=true;}else if(inRe.test(t)){offered=true;if(!url&&items[i].href){url=items[i].href;}}}return (offered?'1':'0')+'|'+(signed?'1':'0')+'|'+url;})()"""

    fun parse(result: String?): SignInHint? {
        val parts = result?.split('|', limit = 3) ?: return null
        if (parts.size < 3 || parts[0] !in setOf("0", "1") || parts[1] !in setOf("0", "1")) return null
        val url = parts[2].trim().takeIf { it.isNotEmpty() }?.let { LiveWebPages.address(it)?.toString() }
        return SignInHint(parts[0] == "1", parts[1] == "1", url)
    }

    fun suggest(hint: SignInHint?, dismissed: Boolean): Boolean = hint != null && hint.offered && !hint.signedIn && !dismissed

    /** Sites are remembered by registrable domain so a site's own subdomains share one choice. */
    fun siteKey(url: String): String? = LiveWebPages.address(url)?.let { WebSites.site(SourcePolicy.host(it)) }
}
