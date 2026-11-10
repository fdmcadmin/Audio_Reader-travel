package app.roadleaf

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.roadleaf.browser.BrowserEditorNodes
import app.roadleaf.browser.BrowserStory
import app.roadleaf.playback.LearnedCastPreferences
import app.roadleaf.playback.StartPoint
import app.roadleaf.playback.WebListeningProgress
import app.roadleaf.playback.NarrationService
import app.roadleaf.sources.LiveWebPages
import app.roadleaf.sources.WebAccess
import app.roadleaf.sources.WebBookLinks
import app.roadleaf.sources.WebSelection
import kotlinx.coroutines.delay
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Any-site reading in the real reader WebView, with no network: an original fixture page is loaded
 * under a page address, then snapshotted, checked for a sign-in suggestion and narrated offline.
 */
@UnstableApi
class UniversalReaderChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp,
    private val controller: MediaController, private val report: MutableList<String>) {
    private val url = "https://tales.example/the-crossing/chapter-4"
    private val lines = listOf(
        "The ferry left at dawn, and Mira watched the harbour lights fade one by one behind her.",
        "By noon the island was a green line on the horizon, and the gulls had stopped following.",
        "She opened her grandmother's letter again, smoothing the creases with careful fingers.")
    private val html = """<html><head><title>Chapter 4: The Ferry - The Crossing - Tales Example</title>
        <style>.decoy-x9 { display: none }</style></head><body>
        <header><a href="/">Tales Example</a> <a href="https://tales.example/login">Log in</a></header>
        <nav><a href="/browse">Browse</a> <a href="/rankings">Rankings</a></nav>
        <main class="chapter-content"><h1>Chapter 4: The Ferry</h1>${lines.joinToString("") { "<p>$it</p>" }}
        <p class="decoy-x9">A decoy line the page hides from its readers.</p>
        <a href="/the-crossing/chapter-5">Next Chapter</a></main>
        <footer>© 2026 Tales Example</footer></body></html>"""

    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
    private fun javascript(web: WebView, script: String): Any? {
        val result = AtomicReference<String>()
        val received = CountDownLatch(1)
        main { web.evaluateJavascript(script) { value -> result.set(value); received.countDown() } }
        check(received.await(15, TimeUnit.SECONDS)) { "WebView did not answer" }
        return JSONTokener(result.get()).nextValue()
    }
    private suspend fun await(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) delay(100)
        check(condition()) { "Expected state was not reached: ${app.playbackStatus.value}" }
    }
    private fun command(action: String, args: Bundle = Bundle.EMPTY): SessionResult =
        main { controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }.get(60, TimeUnit.SECONDS)
            .also { check(it.resultCode == SessionResult.RESULT_SUCCESS) { "$action was refused" } }

    suspend fun run() {
        val saved = PhoneListeningState(app); saved.capture()
        val bookUrl = requireNotNull(WebBookLinks.resolve(url)).bookUrl
        val prefs = app.getSharedPreferences("web-reader", 0)
        val key = requireNotNull(WebAccess.siteKey(url))
        var reader: Activity? = null
        try {
            prefs.edit().putStringSet("signInDismissed", prefs.getStringSet("signInDismissed", emptySet()).orEmpty() - key).remove("url").commit()
            reader = instrumentation.startActivitySync(Intent(app, WebReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val web = main { WebReaderActivity::class.java.getDeclaredField("web").apply { isAccessible = true }.get(reader) as WebView }
            main { web.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url) }
            // The reader marks a page finished only after its own checks accept it.
            val finished = WebReaderActivity::class.java.getDeclaredField("finishedUrl").apply { isAccessible = true }
            val started = System.currentTimeMillis()
            val loaded = runCatching { await { main { finished.get(reader) as String? } == url } }
            check(loaded.isSuccess) { "Fixture page not ready after ${System.currentTimeMillis() - started} ms: finished=${main { finished.get(reader) }} url=${main { web.url }} state=${javascript(web, "document.readyState")}" }

            val snapshot = javascript(web, LiveWebPages.SNAPSHOT_SCRIPT) as? String ?: error("No bounded snapshot")
            val page = LiveWebPages.parse(snapshot, url)
            check(page.title == "Chapter 4: The Ferry" && page.bookTitle == "The Crossing") { "${page.title} / ${page.bookTitle}" }
            check(page.nextUrl == "https://tales.example/the-crossing/chapter-5") { "next=${page.nextUrl}" }
            check(lines.all(page.text::contains)) { "Story lines missing" }
            check(listOf("decoy", "Browse", "Log in", "2026").none(page.text::contains)) { "Page furniture or hidden text was read" }
            check(javascript(web, "document.querySelectorAll('[data-roadleaf-hidden]').length") == 0) { "Snapshot left markers on the live page" }
            report.add("PASS real WebView snapshot of an unknown site reads only the story: menus, sign-in links, footer and CSS-hidden decoy text excluded; next chapter found; live page left unchanged")

            val offered = WebAccess.parse(javascript(web, WebAccess.SCRIPT) as? String)
            check(offered?.offered == true && offered.signedIn.not() && offered.signInUrl == "https://tales.example/login") { "sign-in hint $offered" }
            val row = main { WebReaderActivity::class.java.getDeclaredField("signInRow").apply { isAccessible = true }.get(reader) as View }
            await(10_000) { main { row.visibility == View.VISIBLE } }
            report.add("PASS a site that offers accounts shows the optional sign-in suggestion; reading does not require it")

            val selection = command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", page.url); putBoolean("fresh", true) }).extras.getLong("selection")
            command(NarrationService.WEB_PAGE, Bundle().apply {
                putString("url", page.url); putString("title", page.title); putString("bookTitle", page.bookTitle)
                putString("text", page.text); putString("next", page.nextUrl); putLong("selection", selection); putBoolean("autoNext", false)
            })
            // The short "Preparing web narrator" lead-in shares the address; wait for the chapter itself.
            await(120_000) { main { controller.isPlaying && controller.currentMediaItem?.mediaMetadata?.extras?.getString("webUrl") == url && controller.currentMediaItem?.mediaMetadata?.title?.toString() == "Chapter 4: The Ferry" } }
            check(main { controller.currentMediaItem?.mediaMetadata?.title?.toString() } == "Chapter 4: The Ferry")
            check(main { controller.currentMediaItem?.mediaMetadata?.artist?.toString() } == "The Crossing")
            main { controller.pause() }
            report.add("PASS the page narrates through the normal offline web pipeline with its own chapter and book titles")

            // Start from a word: select it on the live page, find it in the chapter text and start exactly there.
            val target = page.text.indexOf("gulls")
            check(javascript(web, """(function(){var ps=Array.prototype.slice.call(document.querySelectorAll('main p'));
                var p=ps.filter(function(n){return n.textContent.indexOf('gulls')>=0;})[0];var t=p.firstChild;var i=t.textContent.indexOf('gulls');
                var r=document.createRange();r.setStart(t,i);r.setEnd(t,i+5);var s=window.getSelection();s.removeAllRanges();s.addRange(r);return s.toString();})()""") == "gulls") { "Could not select a word on the fixture page" }
            val picked = WebSelection.parse(javascript(web, WebSelection.SCRIPT) as? String) ?: error("No selection context from the page")
            check(WebSelection.locate(page.text, picked.selected, picked.before, picked.after) == target) { "Selected word not located: $picked" }
            val progress = WebListeningProgress(app.getSharedPreferences("web-listening", 0))
            suspend fun heard(offset: Int) {
                await(120_000) { main { controller.isPlaying && controller.currentMediaItem?.mediaMetadata?.title?.toString() == page.title && controller.currentPosition > 300 } }
                main { controller.pause() }
                await(10_000) { progress.read(page.url)?.offset == offset }
            }
            command(NarrationService.START_AT, Bundle().apply { putString("url", page.url); putInt("offset", target) })
            heard(target)
            report.add("PASS Start from a word: a word selected on the live page is found in the chapter text, and reading starts exactly at it")
            val other = page.text.indexOf("smoothing")
            val again = command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", page.url) }).extras.getLong("selection")
            command(NarrationService.WEB_PAGE, Bundle().apply {
                putString("url", page.url); putString("title", page.title); putString("bookTitle", page.bookTitle); putString("text", page.text)
                putString("next", page.nextUrl); putLong("selection", again); putBoolean("autoNext", false); putInt("startAt", other)
            })
            heard(other)
            report.add("PASS Read from here hands the page to the narrator with its start word, which replaces the saved place")

            // Read from here in a story box: a cursor in a rich web editor maps to the same word in the text Roadleaf reads.
            val paragraphs = listOf("The lantern swung over the deck as the crew sang an old song about the northern sea and the long road home.",
                "Mira counted the stars above the mast and named the brightest one for her grandmother, who had sailed these waters long ago.",
                "By dawn the harbour bells were ringing across the water, and the ferry turned slowly toward the island.")
            val editorPage = """<html><body><div id="story" contenteditable="true">${paragraphs.joinToString("") { "<p>$it</p>" }}</div></body></html>"""
            main { web.loadDataWithBaseURL("https://tales.example/the-crossing/editor", editorPage, "text/html", "UTF-8", null) }
            await(20_000) { runCatching { javascript(web, "document.getElementById('story') ? 'yes' : 'no'") == "yes" }.getOrDefault(false) }
            main { web.requestFocus() }
            check(javascript(web, """(function(){var el=document.getElementById('story');el.focus();var t=el.querySelectorAll('p')[1].firstChild;
                var i=t.textContent.indexOf('stars');var r=document.createRange();r.setStart(t,i+2);r.collapse(true);var s=window.getSelection();
                s.removeAllRanges();s.addRange(r);return document.activeElement===el;})()""") == true) { "The story box did not take focus" }
            var mapped: Int? = null; var detail = "no editor"
            val deadline = System.currentTimeMillis() + 10_000
            while (mapped == null && System.currentTimeMillis() < deadline) {
                delay(300)
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: continue
                try {
                    val editor = runCatching { BrowserEditorNodes.focusedEditor(root) }.onFailure { detail = it.message.orEmpty() }.getOrNull() ?: continue
                    try {
                        val read = BrowserEditorNodes.text(editor)
                        val caret = editor.textSelectionStart
                        detail = "caret=$caret in ${read.length} chars"
                        if (caret >= 0) mapped = StartPoint.word(read, BrowserStory.offsetOf(editor.text?.toString().orEmpty(), caret))?.first?.takeIf { read.startsWith("The lantern") && it == read.indexOf("stars") }
                    } finally { editor.recycle() }
                } finally { root.recycle() }
            }
            check(mapped != null) { "Story box cursor was not mapped to its word: $detail" }
            report.add("PASS Read from here in a story box: the cursor inside a word of a rich web editor maps to the start of that word in the text Roadleaf reads")
        } finally {
            reader?.let { activity -> main { activity.finish() } }
            runCatching { command(NarrationService.WEB_PAUSE) }
            app.dao.removeWebBook(bookUrl)
            LearnedCastPreferences(app).clear(bookUrl)
            // Restores the reader, web-progress, playback-target and listening preferences captured above.
            saved.restore(rollbackCast = true)
            report.add("PASS fixture book, progress and learning removed; previous listening state restored")
        }
    }
}
