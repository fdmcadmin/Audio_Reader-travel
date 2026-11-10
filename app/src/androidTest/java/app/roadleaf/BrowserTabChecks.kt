package app.roadleaf

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.roadleaf.browser.BrowserApps
import app.roadleaf.browser.WebBookOpening
import app.roadleaf.playback.NarrationService
import app.roadleaf.sources.WebBookLinks
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Reading from your own browser like a normal reader, and NovelAI kept out of the way until it is added.
 * Original fixtures; no live site. Play routing is checked only while the browser reader is switched on.
 */
@UnstableApi
class BrowserTabChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp,
    private val controller: MediaController, private val report: MutableList<String>) {
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
    private fun command(action: String, args: Bundle = Bundle.EMPTY): SessionResult = main {
        controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }.get(20, TimeUnit.SECONDS)
    private suspend fun await(what: String, timeoutMs: Long = 20_000, condition: suspend () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < end) delay(100)
        check(condition()) { "Not reached: $what (status: ${app.playbackStatus.value})" }
    }
    private fun containsText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.toString()?.contains(text) == true) return true
        for (i in 0 until node.childCount) if (containsText(node.getChild(i), text)) return true
        return false
    }
    private fun scrollForward(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (i in 0 until node.childCount) if (scrollForward(node.getChild(i))) return true
        return false
    }
    /** Looks for any of these texts on the current screen, scrolling down a few pages. */
    private fun onScreen(vararg texts: String, pages: Int = 6): Boolean {
        repeat(pages + 1) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (texts.any { containsText(root, it) }) return true
            if (!scrollForward(instrumentation.uiAutomation.rootInActiveWindow)) return false
            Thread.sleep(400)
        }
        return false
    }
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
    /** Taps the first control showing exactly this text, waiting for it to appear. */
    private fun tap(text: String, timeoutMs: Long = 10_000): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, text)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            Thread.sleep(250)
        }
        return false
    }
    private fun library(): Activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).also { Thread.sleep(1_500) }

    suspend fun run() {
        val backup = PhoneListeningState(app); backup.capture()
        val novelAiWas = app.browserReader.novelAi.value
        val openWas = app.browserReader.webBooksIn
        val url = "https://freewebnovel.com/novel/roadleaf-tab-${UUID.randomUUID().toString().take(8)}/chapter-1"
        val key = requireNotNull(WebBookLinks.resolve(url)).bookUrl
        val choice = "https://freewebnovel.com/novel/roadleaf-choice-${UUID.randomUUID().toString().take(8)}/chapter-1"
        val choiceKey = requireNotNull(WebBookLinks.resolve(choice)).bookUrl
        try {
            novelAiPlacement()
            playAfterBrowserChapter(url)
            webBookChoice(choice)
        } finally {
            runCatching { command(NarrationService.WEB_PAUSE) }
            app.browserReader.showNovelAi(novelAiWas)
            app.browserReader.webBooksIn = openWas
            app.dao.removeWebBook(key); app.dao.removeWebBook(choiceKey)
            backup.restore(rollbackCast = true)
        }
    }

    private suspend fun webBookChoice(url: String) {
        app.websites.remember(url, "Roadleaf choice fixture", "Chapter 1: Original choice fixture")
        // The first time, Roadleaf asks where web books open, and remembers the answer.
        app.browserReader.webBooksIn = null
        val first = library()
        check(tap("Continue reading")) { "No Continue reading button for the Quick pick" }
        await("where-to-open question") { containsText(instrumentation.uiAutomation.rootInActiveWindow, "Open web books in") }
        val monitor = instrumentation.addMonitor(WebReaderActivity::class.java.name, null, false)
        try {
            check(tap("Roadleaf's reader")) { "No Roadleaf's reader choice" }
            val reader = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 15_000)) { "Roadleaf's reader did not open" }
            check(app.browserReader.webBooksIn == WebBookOpening.READER) { "The choice was not remembered" }
            command(NarrationService.WEB_PAUSE)
            main { reader.finish() }
        } finally { instrumentation.removeMonitor(monitor) }
        main { first.finish() }
        report.add("PASS the first web book asks where to open it (Roadleaf's reader or an installed browser) and remembers the choice")

        // With a browser chosen, Continue reading opens the chapter in that browser instead of Roadleaf's reader.
        val browser = BrowserApps.installed(app).firstOrNull()?.packageName
        if (browser == null) { report.add("PASS no browser is installed here, so opening in a browser wasn't checked"); return }
        app.browserReader.webBooksIn = browser
        val second = library()
        check(tap("Continue reading")) { "No Continue reading button the second time" }
        await("browser in front") { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == browser }
        main { second.finish() }
        library().also { main { it.finish() } }
        report.add("PASS with a browser chosen, Continue reading opens the chapter in that browser ($browser), not Roadleaf's reader")
    }

    private fun novelAiPlacement() {
        app.browserReader.showNovelAi(false)
        val hidden = library()
        check(!onScreen("Your NovelAI stories, read in your browser", "NovelAI · Read in my browser")) { "NovelAI shows in the Library before it was added" }
        main { hidden.finish() }
        report.add("PASS NovelAI is not shown in the Library until it is added")

        // A NovelAI link opened in Roadleaf's reader adds NovelAI and shows how to read it in your own browser.
        val monitor = instrumentation.addMonitor(NovelAiBrowserActivity::class.java.name, null, false)
        try {
            main { instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, WebReaderActivity::class.java)
                .putExtra("url", "https://novelai.net/stories").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            val setup = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 15_000)) { "The NovelAI setup did not open" }
            check(app.browserReader.novelAi.value) { "Opening a NovelAI link did not add NovelAI" }
            Thread.sleep(1_500)
            check(onScreen("NovelAI · read your stories")) { "The setup screen does not show the NovelAI steps" }
            main { setup.finish() }
        } finally { instrumentation.removeMonitor(monitor) }
        val shown = library()
        check(onScreen("Your NovelAI stories, read in your browser")) { "The Library does not show NovelAI after it was added" }
        main { shown.finish() }
        report.add("PASS a NovelAI link adds NovelAI, opens the steps to read it in your own browser, and only then shows it in the Library")
    }

    private suspend fun playAfterBrowserChapter(url: String) {
        val selection = command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true) }).extras.getLong("selection")
        check(command(NarrationService.WEB_PAGE, Bundle().apply {
            putString("url", url); putString("title", "Chapter 1: Original tab fixture"); putString("bookTitle", "Roadleaf tab fixture")
            putString("text", "A traveller reached the quiet bridge as the evening lights came on along the river.\n".repeat(2))
            putString("next", url.replace("chapter-1", "chapter-2")); putLong("selection", selection)
            putBoolean("autoNext", false); putString("via", "tab")
        }).resultCode == SessionResult.RESULT_SUCCESS)
        await("browser chapter playing") { main { controller.isPlaying } }
        await("browser chapter finished", 90_000) { app.webChapterEnded.value == url }
        check(app.playbackStatus.value.contains("open the next chapter in your browser")) { "Unclear end of chapter: ${app.playbackStatus.value}" }
        val connected = app.browserStatus.value.connected
        val before = app.browserPlayRequest.value
        main { controller.play() }
        delay(1_500)
        check(!main { controller.isPlaying }) { "Play replayed the finished chapter" }
        if (connected) {
            check(app.browserPlayRequest.value == before + 1) { "Play did not ask the browser reader for the page on screen" }
            // No browser is on screen here, so the reader asks you to open the next chapter first.
            await("browser reader answer") { app.browserStatus.value.message.contains("Open the next chapter in your browser") }
            report.add("PASS Play after a chapter read from your browser asks the browser reader for the page on screen; with no new page it says to open the next chapter, and nothing replays")
        } else {
            check(app.browserPlayRequest.value == before) { "Play asked a browser reader that is off" }
            check(app.playbackStatus.value.contains("open the next chapter in your browser")) { "No guidance after Play: ${app.playbackStatus.value}" }
            report.add("PASS Play after a chapter read from your browser doesn't replay it and says to open the next chapter (browser reader not running in this test process)")
        }
        command(NarrationService.WEB_PAUSE)
    }
}
