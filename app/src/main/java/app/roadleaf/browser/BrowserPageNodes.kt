package app.roadleaf.browser

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import app.roadleaf.sources.LiveWebPages

/** One node of the visible web page, in document order. Browser menus and toolbars are never included. */
data class PageNode(val depth: Int, val tag: String, val text: String, val href: String? = null)
data class PageCapture(val title: String, val nodes: List<PageNode>)

/**
 * Turns what a browser shows to accessibility services into a small HTML document, so the same
 * generic reader that serves Roadleaf's own browser finds the story text from any browser.
 */
object BrowserPageHtml {
    private val roles = mapOf("paragraph" to "p", "heading" to "h2", "link" to "a", "list" to "ul", "listItem" to "li",
        "navigation" to "nav", "banner" to "header", "contentInfo" to "footer", "complementary" to "aside", "main" to "main",
        "article" to "article", "form" to "form", "search" to "form", "button" to "button", "popUpButton" to "button",
        "toggleButton" to "button", "textField" to "input", "searchBox" to "input", "textFieldWithComboBox" to "input",
        "comboBoxGrouping" to "form", "image" to "img", "dialog" to "dialog", "alertDialog" to "dialog", "region" to "section",
        "section" to "section", "blockquote" to "blockquote", "lineBreak" to "br", "staticText" to "span", "inlineTextBox" to "span",
        "genericContainer" to "div", "menu" to "nav", "menuBar" to "nav", "tabList" to "nav", "toolbar" to "nav")
    private val void = setOf("br", "img", "input")

    fun tag(role: String?, heading: Boolean, className: String?): String = when {
        heading -> "h2"
        role != null -> roles[role] ?: "div"
        className == "android.widget.Button" -> "button"
        className == "android.widget.EditText" -> "input"
        className == "android.widget.Image" || className == "android.widget.ImageView" -> "img"
        else -> "div"
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun attribute(text: String) = escape(text).replace("\"", "&quot;")

    fun build(title: String, nodes: List<PageNode>): String {
        val html = StringBuilder("<html><head><title>").append(escape(title)).append("</title></head><body>")
        val open = ArrayDeque<Pair<Int, String>>()
        for (node in nodes) {
            while (open.isNotEmpty() && open.last().first >= node.depth) html.append("</").append(open.removeLast().second).append('>')
            html.append('<').append(node.tag)
            if (node.tag == "a" && !node.href.isNullOrBlank()) html.append(" href=\"").append(attribute(node.href)).append('"')
            html.append('>')
            if (node.tag in void) continue
            if (node.text.isNotEmpty()) html.append(escape(node.text))
            open.addLast(node.depth to node.tag)
            if (html.length > LiveWebPages.MAX_HTML) break
        }
        while (open.isNotEmpty()) html.append("</").append(open.removeLast().second).append('>')
        return html.append("</body></html>").toString()
    }
}

/** Reads the page shown in a browser window. Password fields and the browser's own interface are skipped. */
@Suppress("DEPRECATION")
object BrowserPageNodes {
    private const val MAX_NODES = 15_000
    private fun label(node: AccessibilityNodeInfo) = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()

    fun capture(root: AccessibilityNodeInfo): PageCapture {
        val nodes = ArrayList<PageNode>()
        var title = ""
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int, inWeb: Boolean) {
            if (++visited > MAX_NODES || depth > 150) return
            val className = node.className?.toString()
            val web = inWeb || className == "android.webkit.WebView"
            if (web && node.isPassword) return
            val children = (0 until node.childCount).mapNotNull { node.getChild(it) }
            try {
                if (web) {
                    if (!inWeb && title.isEmpty()) title = label(node)
                    val role = node.extras?.getString("AccessibilityNodeInfo.chromeRole")
                    val heading = role == "heading" || (Build.VERSION.SDK_INT >= 28 && node.isHeading)
                    val tag = if (!inWeb) "div" else BrowserPageHtml.tag(role, heading, className)
                    // Chromium puts text on leaf nodes; other browsers may put it on the element itself.
                    val childrenCarryText = children.any { it.childCount > 0 || label(it).isNotEmpty() }
                    val text = if (!inWeb || tag == "input" || childrenCarryText) "" else label(node)
                    val href = node.extras?.getCharSequence("AccessibilityNodeInfo.targetUrl")?.toString()?.takeIf { tag == "a" }
                    nodes += PageNode(depth, tag, text, href)
                }
                children.forEach { visit(it, depth + 1, web) }
            } finally { children.forEach(AccessibilityNodeInfo::recycle) }
        }
        visit(root, 0, false)
        return PageCapture(title, nodes)
    }
}

/** The browser's address field, turned into a page address. A bare site name is not enough to save a chapter. */
object BrowserPages {
    fun pageUrl(addressText: String?): String? {
        val text = addressText?.trim().orEmpty()
        if (text.isEmpty() || text.any(Char::isWhitespace)) return null
        val full = if ("://" in text) text else "https://$text"
        if (!full.substringAfter("://").contains('/')) return null
        return LiveWebPages.canonical(full)
    }
}
