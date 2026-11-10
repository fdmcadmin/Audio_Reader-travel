package app.roadleaf

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.webkit.WebView
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.roadleaf.playback.NarrationService
import app.roadleaf.playback.OfflineSpeech
import app.roadleaf.sources.WebBookLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Faster story starts and chapter changes: the narrator warm-up, the next webpage chapter read early and started the
 * moment the current one ends, and a loaded page read as soon as its text settles. Original fixtures; no live site.
 */
@UnstableApi
class WebHandoffChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp,
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
        while (!condition() && SystemClock.elapsedRealtime() < end) delay(50)
        check(condition()) { "Not reached: $what (status: ${app.playbackStatus.value}; item: ${mediaId()})" }
    }
    private fun webUrl() = main { controller.currentMediaItem?.mediaMetadata?.extras?.getString("webUrl") }
    private fun mediaId() = main { controller.currentMediaItem?.mediaId }
    private fun playing() = main { controller.isPlaying }
    private fun paragraphs(chapter: String, count: Int) = (1..count).joinToString("") {
        "Paragraph $it of the $chapter original chapter. A traveller walks beside the quiet river and counts the bridges toward home.\n"
    }
    private fun page(url: String, title: String, text: String, next: String?, selection: Long = 0, request: Long = 0) = Bundle().apply {
        putString("url", url); putString("title", title); putString("bookTitle", "Roadleaf handoff fixture"); putString("text", text)
        next?.let { putString("next", it) }; putLong("selection", selection); putBoolean("autoNext", true)
        if (request != 0L) { putLong("request", request); putString("requested", url) }
    }

    suspend fun run() {
        val backup = PhoneListeningState(app); backup.capture()
        val slug = "roadleaf-handoff-${UUID.randomUUID().toString().take(8)}"
        val one = "https://freewebnovel.com/novel/$slug/chapter-1"
        val two = "https://freewebnovel.com/novel/$slug/chapter-2"
        val three = "https://freewebnovel.com/novel/$slug/chapter-3"
        val five = "https://freewebnovel.com/novel/$slug/chapter-5"
        val key = requireNotNull(WebBookLinks.resolve(one)).bookUrl
        try {
            app.listening.save(app.listening.settings.value.copy(speed = 2f))
            warmUp()
            earlyRead(one, two, three, key)
            settledPage(five)
        } finally {
            runCatching { command(NarrationService.WEB_PAUSE) }
            app.dao.removeWebBook(key); app.learnedCasts.clear(key)
            backup.restore(rollbackCast = true)
        }
    }

    private suspend fun warmUp() {
        val settings = app.listening.settings.value
        val speech = main { OfflineSpeech(app) }
        try {
            var started = SystemClock.elapsedRealtime()
            val ready = withContext(Dispatchers.Main) { speech.warmUp(settings) }
            val warmUpMs = SystemClock.elapsedRealtime() - started
            started = SystemClock.elapsedRealtime()
            val again = withContext(Dispatchers.Main) { speech.warmUp(settings) }
            val againMs = SystemClock.elapsedRealtime() - started
            val opening = "A traveller ${UUID.randomUUID()} reached the quiet station beside the river and read her map by the lamp."
            started = SystemClock.elapsedRealtime()
            val file = withContext(Dispatchers.Main) { speech.render(opening, settings) }
            val renderMs = SystemClock.elapsedRealtime() - started
            check(ready && again && againMs < 1_000 && file.length() > 44) { "warm-up ready=$ready again=$again in ${againMs}ms" }
            report.add("PASS narrator warm-up: engine and voice ready in ${warmUpMs}ms before any story; once warm it is not repeated (${againMs}ms); the first passage then took ${renderMs}ms; not a phone benchmark")
        } finally { main { speech.close() } }
    }

    private suspend fun earlyRead(one: String, two: String, three: String, key: String) {
        val selection = command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", one); putBoolean("fresh", true) }).extras.getLong("selection")
        check(command(NarrationService.WEB_PAGE, page(one, "Chapter 1: Original handoff", paragraphs("first", 6), two, selection = selection)).resultCode == SessionResult.RESULT_SUCCESS)
        await("chapter one playing") { webUrl() == one && playing() }

        // A short chapter: the next one is asked for while this one plays.
        await("early read of chapter two") { app.webNavigation.value?.let { it.early && it.url == two } == true }
        val early = requireNotNull(app.webNavigation.value)
        check(webUrl() == one && playing()) { "The early read disturbed chapter one" }

        // Pausing mid-chapter keeps the early read and needs no Read chapter afterwards.
        main { controller.pause() }
        delay(400)
        check(app.webNavigation.value?.id == early.id) { "Pausing dropped the early read" }
        check(!app.playbackStatus.value.contains("Read this chapter")) { "Pausing asked for Read chapter: ${app.playbackStatus.value}" }
        main { controller.play() }
        await("chapter one resumed") { webUrl() == one && playing() }

        // The reader hands back chapter two's page: it is prepared without touching playback, and the Quick pick stays put.
        check(command(NarrationService.WEB_PAGE, page(two, "Chapter 2: Original handoff", paragraphs("second", 4), three, request = early.id)).resultCode == SessionResult.RESULT_SUCCESS)
        check(app.webNavigation.value == null && webUrl() == one) { "Chapter two's page interrupted chapter one" }
        check(app.dao.webBook(key)?.chapterUrl == one) { "The Quick pick moved before chapter two started" }

        // At chapter one's end, chapter two starts at once, with no page load in between.
        var sawLoading = false
        var lastOne = 0L
        var firstTwo = 0L
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val id = mediaId(); val url = webUrl(); val isPlaying = playing()
            val now = SystemClock.elapsedRealtime()
            if (id == "web-loading") sawLoading = true
            if (url == one && isPlaying) lastOne = now
            if (url == two && isPlaying) { firstTwo = now; break }
            delay(40)
        }
        check(firstTwo > 0) { "Chapter two did not start: ${app.playbackStatus.value}" }
        check(!sawLoading) { "A page load ran between the chapters" }
        val gap = firstTwo - lastOne
        check(gap < 2_000) { "Chapter two started ${gap}ms after chapter one" }
        await("Quick pick on chapter two") { app.dao.webBook(key)?.chapterUrl == two }
        report.add("PASS the next webpage chapter is read while the current one plays and starts ${gap}ms after it ends, with no page load in between; pausing keeps the early read; the Quick pick moves only when the next chapter starts")

        // A failed early read is dropped quietly; the usual page load runs when the chapter ends.
        await("early read of chapter three") { app.webNavigation.value?.let { it.early && it.url == three } == true }
        val third = requireNotNull(app.webNavigation.value)
        command(NarrationService.WEB_FAILED, Bundle().apply { putLong("request", third.id); putString("message", "Original fixture failure") })
        await("early read dropped") { app.webNavigation.value == null }
        check(webUrl() == two && playing()) { "A failed early read stopped chapter two" }
        await("usual load at chapter two's end", 120_000) { app.webNavigation.value?.let { !it.early && it.url == three } == true && mediaId() == "web-loading" }
        report.add("PASS a failed early read is dropped without stopping the chapter, and the next chapter loads the usual way when the current one ends")
        command(NarrationService.WEB_PAUSE)
        await("handoff stopped") { app.webNavigation.value == null && !main { controller.playWhenReady } }
    }

    private suspend fun settledPage(url: String) {
        val reader = instrumentation.startActivitySync(Intent(instrumentation.targetContext, WebReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val browser = main { WebReaderActivity::class.java.getDeclaredField("web").apply { isAccessible = true }.get(reader) as WebView }
            val late = "The final line arrives a moment after the page loads."
            val html = """<html><head><title>Original settle fixture</title><meta property="og:novel:book_name" content="Roadleaf handoff fixture"></head>
                <body><div id="chr-content"><h3>Chapter 5: Original settle fixture</h3><p>A traveller returned home beside a quiet road. This original text tests reading a page as soon as it settles.</p></div>
                <script>setTimeout(function(){var p=document.createElement('p');p.textContent='$late';document.getElementById('chr-content').appendChild(p);},700);</script></body></html>"""
            val started = SystemClock.elapsedRealtime()
            check(command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true); putBoolean("navigate", true) }).resultCode == SessionResult.RESULT_SUCCESS)
            main { browser.resumeTimers(); browser.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url) }
            await("settled page narrated", 25_000) { webUrl() == url && mediaId()?.startsWith("web:") == true && playing() }
            val elapsed = SystemClock.elapsedRealtime() - started
            check(app.readingPage.value?.text?.contains(late) == true) { "The paragraph added after loading was cut off" }
            check(elapsed < 8_000) { "Reading started ${elapsed}ms after the page was requested" }
            report.add("PASS a loaded chapter page is read as soon as its text settles (${elapsed}ms, not a fixed 10 seconds), including a paragraph the page added after loading")
            command(NarrationService.WEB_PAUSE)
        } finally { main { reader.finish() } }
    }
}
