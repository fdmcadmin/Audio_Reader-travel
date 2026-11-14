package app.roadleaf.browser

import java.net.URI

/** Only an explicitly chosen NovelAI editor, never an entire webpage or another app. */
object BrowserStory {
    const val MAX_TEXT = 500_000
    const val SOURCE = "novelai-browser"
    /** The title of a reading that isn't saved to Library. */
    const val UNSAVED_TITLE = "NovelAI story"
    /** Address fields of common browsers (Chromium family, Firefox family, Samsung, Opera, DuckDuckGo). */
    val addressIds = setOf("url_bar", "mozac_browser_toolbar_url_view", "mozac_browser_toolbar_url_edit_view",
        "location_bar_edit_text", "url_field", "omnibarTextInput", "omnibar_text_input", "addressbarEdit", "address_bar_edit_text")
    fun novelAiAddress(value: String): Boolean = runCatching {
        val text = value.trim()
        if (text.any(Char::isWhitespace)) return false
        val uri = URI(if ("://" in text) text else "https://$text")
        uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) && uri.host?.lowercase() in setOf("novelai.net", "www.novelai.net") &&
            (uri.path.isNullOrBlank() || uri.path == "/" || uri.path == "/stories" || uri.path.startsWith("/stories/"))
    }.getOrDefault(false)
    /** Compare the manuscript IDs in native browser addresses, including a collapsed address without https. */
    fun storyKey(value: String): String? = runCatching {
        if (!novelAiAddress(value)) return null
        val uri = URI(if ("://" in value) value.trim() else "https://${value.trim()}")
        val path = uri.path.orEmpty()
        if (path.startsWith("/stories/")) path.removePrefix("/stories/").trimEnd('/').takeIf(String::isNotBlank)
        else if (path == "/stories") uri.rawQuery?.split('&')?.singleOrNull { it.startsWith("id=") }
            ?.substringAfter('=')?.takeIf { it.matches(Regex("[A-Za-z0-9-]{6,128}")) }
        else null
    }.getOrNull()
    fun sameStory(first: String, second: String): Boolean = storyKey(first)?.let { it == storyKey(second) } == true
    /** A range may be dragged backwards; a lone insertion caret must never act as a highlight. */
    fun selectionOffset(raw: String, start: Int, end: Int): Int? =
        minOf(start, end).takeIf { start != end && start in 0..raw.length && end in 0..raw.length }
    /** A bookmark must point to an individual story; the generic library page is not enough. */
    fun storyAddress(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        if (!novelAiAddress(value)) return false
        val path = uri.path.orEmpty()
        val storyPath = path.startsWith("/stories/") && path.length > "/stories/".length
        val storyId = uri.rawQuery?.split('&')?.singleOrNull { it.startsWith("id=") }
            ?.substringAfter('=')?.takeIf { it.matches(Regex("[A-Za-z0-9-]{6,128}")) }
        storyPath || (path == "/stories" && storyId != null)
    }.getOrDefault(false)
    fun editorText(text: String, editable: Boolean, password: Boolean, insideWeb: Boolean): String {
        require(editable && !password && insideWeb) { "Tap NovelAI's story-writing box first. Menus, search and sign-in fields are not read." }
        require(text.length in 40..MAX_TEXT) { "The browser must expose 40–500,000 characters from the story box. No whole-page fallback is used." }
        require(text.any(Char::isLetter)) { "The selected story box has no readable text." }
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }
    /** Offset in [editorText] of a cursor at [caret] in the raw editor text, whose line breaks may be \r\n. */
    fun offsetOf(raw: String, caret: Int): Int = raw.substring(0, caret.coerceIn(0, raw.length)).replace("\r\n", "\n").length
    /** Automatic captures hold the unfinished last paragraph. A manual Read includes it. */
    fun completedText(text: String, previous: String): String? {
        require(text.startsWith(previous)) { "Earlier text changed or another story opened. Follow stopped; confirm the story in Roadleaf." }
        val end = text.lastIndexOf('\n') + 1
        return text.take(end).takeIf { it.length > previous.length }
    }
    fun stableCompletedText(first: String, second: String, previous: String): String? {
        val complete = completedText(first, previous) ?: return null
        require(second.startsWith(previous)) { "Earlier text changed or another story opened. Follow stopped; confirm the story in Roadleaf." }
        return complete.takeIf { second.startsWith(it) }
    }
}

data class BrowserReadingSession(val token: String, val bookId: String, val title: String, val browserPackage: String,
    val follow: Boolean = true, val allowReplacement: Boolean = false, val webAppPackage: String? = null, val webAppLaunchMs: Long = 0,
    val temporary: Boolean = false, val profileBookId: String? = null, val useProfiles: Boolean = true, val pinnedBook: Boolean = true)

/**
 * Play after a chapter read from your own browser has ended. Roadleaf reads only what the browser shows and never turns
 * pages: once you have opened another page, that page is read; otherwise you are asked to open the next chapter.
 */
object BrowserContinue {
    enum class Next { READ_SHOWN, SAME_PAGE, NO_PAGE }
    fun after(ended: String?, shown: String?): Next = when {
        shown == null -> Next.NO_PAGE
        shown == ended -> Next.SAME_PAGE
        else -> Next.READ_SHOWN
    }
}

/** Where a web book opens: Roadleaf's reader, your chosen browser, or a question the first time (or once that browser is gone). */
object WebBookOpening {
    const val READER = "roadleaf-reader"
    sealed interface Target {
        data object Ask : Target
        data object Reader : Target
        data class Browser(val packageName: String) : Target
    }
    fun target(choice: String?, installed: Collection<String>): Target = when {
        choice == READER -> Target.Reader
        choice != null && choice in installed -> Target.Browser(choice)
        else -> Target.Ask
    }
}

/** TOP and RESUME as before; AT starts at an exact offset (a chosen word, or where an unsaved reading stopped). */
enum class BrowserStart { TOP, RESUME, AT }

/** A cast is never inferred from names, the last book, or a shared website title. */
object BrowserStoryMatch {
    fun unique(text: String, snapshots: List<Pair<String, String>>): String? = snapshots
        .filter { (_, saved) -> saved.length >= 40 && text.startsWith(saved) }.map { it.first }.distinct().singleOrNull()
}
data class BrowserReadingStatus(val connected: Boolean = false, val message: String = "Browser reader is off. Enable it in Accessibility, then tap Roadleaf's bubble in your browser. Saving to Library is optional.")
