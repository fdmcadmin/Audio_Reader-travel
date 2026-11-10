package app.roadleaf.sources

import org.json.JSONObject

/**
 * "Read from here" on a webpage: the word the reader selected on the live page is located in the
 * chapter text Roadleaf extracted, using the words around it to tell repeated words apart.
 */
object WebSelection {
    data class Selection(val selected: String, val before: String, val after: String)

    /** Returns the selected text plus the text before and after it inside the same block, or null. */
    const val SCRIPT = """(function(){try{var s=window.getSelection();if(!s||s.rangeCount<1)return null;var t=s.toString();if(!t||!t.trim())return null;
var r=s.getRangeAt(0);var B={P:1,DIV:1,LI:1,BLOCKQUOTE:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,TD:1,TH:1,DD:1,DT:1,SECTION:1,ARTICLE:1,MAIN:1,PRE:1};
function block(n){while(n&&n.nodeType!==1)n=n.parentNode;while(n&&n!==document.body&&!B[n.tagName])n=n.parentNode;return n||document.body;}
var pre=document.createRange();pre.selectNodeContents(block(r.startContainer));pre.setEnd(r.startContainer,r.startOffset);
var post=document.createRange();post.selectNodeContents(block(r.endContainer));post.setStart(r.endContainer,r.endOffset);
return JSON.stringify({selected:t.slice(0,200),before:pre.toString().slice(-200),after:post.toString().slice(0,200)});}catch(e){return null;}})()"""

    fun parse(json: String?): Selection? = runCatching {
        val value = JSONObject(json ?: return null)
        Selection(value.optString("selected").take(200), value.optString("before").takeLast(200), value.optString("after").take(200))
    }.getOrNull()?.takeIf { it.selected.isNotBlank() }

    /** Offset of the selected word in [text], or null when the selection is not part of the chapter text. */
    fun locate(text: String, selected: String, before: String = "", after: String = ""): Int? {
        val anchor = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’\\-]*").find(selected) ?: return null
        val word = anchor.value.trimEnd('\'', '’', '-')
        val pattern = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        val candidates = pattern.findAll(text).map { it.range.first }.toList()
        if (candidates.size <= 1) return candidates.firstOrNull()
        val beforeKey = squash(before + selected.substring(0, anchor.range.first)).takeLast(120)
        val afterKey = squash(selected.substring(anchor.range.first + word.length) + after).take(120)
        return candidates.maxByOrNull { at ->
            val prior = squash(text.substring(maxOf(0, at - 240), at)).takeLast(120)
            val next = squash(text.substring(at + word.length, minOf(text.length, at + word.length + 240))).take(120)
            commonSuffix(prior, beforeKey) + commonPrefix(next, afterKey)
        }
    }
    private fun squash(value: String) = value.replace(Regex("\\s+"), " ").lowercase()
    private fun commonSuffix(a: String, b: String): Int {
        var n = 0
        while (n < a.length && n < b.length && a[a.length - 1 - n] == b[b.length - 1 - n]) n++
        return n
    }
    private fun commonPrefix(a: String, b: String): Int {
        var n = 0
        while (n < a.length && n < b.length && a[n] == b[n]) n++
        return n
    }
}
