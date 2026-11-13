package app.roadleaf

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import app.roadleaf.data.*
import app.roadleaf.importing.BookFiles
import app.roadleaf.playback.NarrationService
import app.roadleaf.playback.WaveAudio
import app.roadleaf.playback.BookVoiceCast
import android.view.accessibility.AccessibilityNodeInfo
import app.roadleaf.sources.LiveWebPages
import androidx.media3.common.Player
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.json.JSONTokener
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Native Android checks without a third-party runner; all fixtures are original, temporary local text. */
@UnstableApi
class ReaderRegressionInstrumentation : Instrumentation() {
    private var liveProbeUrl: String? = null
    private var focusChecks = false
    private var preparationChecks = false
    private var websiteChecks = false
    private var chapterChecks = false
    private var adaptiveChecks = false
    private var fullChapter = false
    private var recoveryChecks = false
    private var outputChecks = false
    private var universalChecks = false
    private var castChecks = false
    private var musicChecks = false
    private var paragraphChecks = false
    private var browserProbe = false
    private var browserSelectionProbe = false
    private var browserChecks = false
    private var webAppProbe = false
    private var quickReadChecks = false
    private var bubbleChecks = false
    private var bubblePauseMs = 0L
    private var handoffChecks = false
    private var tabChecks = false
    private var kokoroChecks = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); browserSelectionProbe = arguments?.getString("browserSelectionProbe") == "true"; kokoroChecks = arguments?.getString("kokoroChecks") == "true"; bubbleChecks = arguments?.getString("bubbleChecks") == "true"; handoffChecks = arguments?.getString("handoffChecks") == "true"; tabChecks = arguments?.getString("tabChecks") == "true"; bubblePauseMs = arguments?.getString("bubblePauseMs")?.toLongOrNull() ?: 0L; quickReadChecks = arguments?.getString("quickReadChecks") == "true"; webAppProbe = arguments?.getString("browserWebApp") == "true"; browserChecks = arguments?.getString("browserChecks") == "true"; browserProbe = arguments?.getString("browserProbe") == "true"; paragraphChecks = arguments?.getString("paragraphChecks") == "true"; musicChecks = arguments?.getString("musicChecks") == "true"; liveProbeUrl = arguments?.getString("liveUrl"); focusChecks = arguments?.getString("focusChecks") == "true"; preparationChecks = arguments?.getString("preparationChecks") == "true"; websiteChecks = arguments?.getString("websiteChecks") == "true"; chapterChecks = arguments?.getString("chapterChecks") == "true"; adaptiveChecks = arguments?.getString("adaptiveChecks") == "true"; fullChapter = arguments?.getString("fullChapter") == "true"; recoveryChecks = arguments?.getString("recoveryChecks") == "true"; outputChecks = arguments?.getString("outputChecks") == "true"; universalChecks = arguments?.getString("universalChecks") == "true"; castChecks = arguments?.getString("castChecks") == "true"; start() }
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
    private fun button(view: View, label: String): Button? {
        if (view is Button && view.text.toString() == label) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) button(view.getChildAt(index), label)?.let { return it }
        return null
    }
    private fun containsText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.toString()?.contains(text) == true) return true
        for (i in 0 until node.childCount) if (containsText(node.getChild(i), text)) return true
        return false
    }
    /** Scrolls the first scrollable list on screen one page down, for text below the fold. */
    private fun scrollForward(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (i in 0 until node.childCount) if (scrollForward(node.getChild(i))) return true
        return false
    }
    private fun awaitCondition(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(100)
        check(condition()) { "Expected state was not reached within $timeoutMs ms" }
    }
    private fun browser(reader: Activity) = main {
        WebReaderActivity::class.java.getDeclaredField("web").apply { isAccessible = true }.get(reader) as WebView
    }
    private fun javascript(browser: WebView, script: String): String {
        val result = AtomicReference<String>()
        val received = CountDownLatch(1)
        main { browser.evaluateJavascript(script) { value -> result.set(value); received.countDown() } }
        check(received.await(10, TimeUnit.SECONDS)) { "WebView did not return its DOM inspection" }
        return result.get()
    }
    override fun onStart() {
        var controller: MediaController? = null
        var activity: Activity? = null
        val created = mutableListOf<String>()
        val createdWebBooks = mutableListOf<String>()
        val app = targetContext.applicationContext as RoadleafApp
        val report = mutableListOf<String>()
        try { runBlocking {
            if (kokoroChecks) { KokoroStoryChecks(app, report).run(); return@runBlocking }
            if (browserProbe) { BrowserEditorProbe(this@ReaderRegressionInstrumentation, report).run(webAppProbe, browserSelectionProbe); return@runBlocking }
            if (websiteChecks) { WebsiteLibraryChecks(this@ReaderRegressionInstrumentation, report).run(); return@runBlocking }
            if (castChecks) { CastFileChecks(app, report).run(); return@runBlocking }
            if (recoveryChecks) { SpeechRecoveryChecks(this@ReaderRegressionInstrumentation, app, report).run(); return@runBlocking }
            if (chapterChecks) { ChapterPlaybackChecks(this@ReaderRegressionInstrumentation, app, report).run(); return@runBlocking }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (bubbleChecks) { BrowserBubbleChecks(this@ReaderRegressionInstrumentation, app, requireNotNull(activity), report, bubblePauseMs).run(); return@runBlocking }
            val connection = main { MediaController.Builder(targetContext, SessionToken(targetContext, ComponentName(targetContext, NarrationService::class.java))).buildAsync() }
            val active = connection.get(20, TimeUnit.SECONDS).also { controller = it }
            if (tabChecks) { BrowserTabChecks(this@ReaderRegressionInstrumentation, app, active, report).run(); return@runBlocking }
            if (handoffChecks) { WebHandoffChecks(this@ReaderRegressionInstrumentation, app, active, report).run(); return@runBlocking }
            if (quickReadChecks) { BrowserQuickReadChecks(this@ReaderRegressionInstrumentation, app, active, report).run(); return@runBlocking }
            if (browserChecks) { BrowserStoryChecks(this@ReaderRegressionInstrumentation, app, active, report).run(); return@runBlocking }
            if (paragraphChecks) { ParagraphProfileChecks(app, report).run(); ParagraphPlaybackChecks(this@ReaderRegressionInstrumentation, app, active, report).run(); return@runBlocking }
            if (musicChecks) { HeadphoneMusicChecks(this@ReaderRegressionInstrumentation, app, report).run(); return@runBlocking }
            if (outputChecks) { AudioOutputChecks(this@ReaderRegressionInstrumentation, app, active, report).run(); return@runBlocking }
            if (universalChecks) { UniversalReaderChecks(this@ReaderRegressionInstrumentation, app, active, report).run(); return@runBlocking }
            if (adaptiveChecks) { AdaptiveLearningChecks(this@ReaderRegressionInstrumentation, app, active, report).run(); return@runBlocking }
            if (focusChecks) {
                val phoneState = PhoneListeningState(app)
                phoneState.capture()
                try { AudioInterruptionChecks(this@ReaderRegressionInstrumentation, app, active, report).run() }
                finally { phoneState.restore(rollbackCast = true) }
                return@runBlocking
            }
            if (preparationChecks) {
                NarratorPreparationChecks(this@ReaderRegressionInstrumentation, app, active, report).run()
                return@runBlocking
            }
            fun command(action: String, args: Bundle = Bundle.EMPTY): SessionResult = main {
                active.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
            }.get(20, TimeUnit.SECONDS)
            liveProbeUrl?.let { requested ->
                val url = requireNotNull(LiveWebPages.canonical(requested))
                if (fullChapter) command(NarrationService.RESET_LEARNING, Bundle().apply { putString("book", url.substringBeforeLast('/')) })
                val reader = startActivitySync(Intent(targetContext, WebReaderActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("url", url).putExtra("loadNewBook", true))
                activity = reader
                val browser = browser(reader)
                awaitCondition(20_000) { app.webNavigation.value?.url == url || main { active.currentMediaItem?.mediaId?.startsWith("web:") == true } }
                awaitCondition(125_000) { app.webNavigation.value == null }
                val html = JSONTokener(javascript(browser,
                    "(function(){var h=document.documentElement.outerHTML;return h.length<=${LiveWebPages.MAX_HTML}?h:null;})()"))
                    .nextValue() as? String ?: error("No bounded page DOM")
                val page = LiveWebPages.parse(html, url)
                report.add("PASS live page DOM: ${page.title}; ${page.text.length} readable characters; next=${page.nextUrl}; page bytes are not exported")
                awaitCondition { main { active.currentMediaItem?.mediaId?.startsWith("web:") == true && active.playbackState == Player.STATE_READY && active.playWhenReady } }
                check(main { active.currentMediaItem?.mediaMetadata?.extras?.getString("webUrl") } == page.url)
                check(main { active.currentMediaItem?.mediaMetadata?.artist?.toString() } == page.bookTitle)
                check(main { active.currentMediaItem?.mediaMetadata?.title?.toString() } == page.title)
                check((main { active.currentMediaItem?.mediaMetadata?.extras?.getInt("segmentChars") } ?: 0) in 1..360)
                val audioKey = main { active.currentMediaItem?.mediaMetadata?.extras?.getString("audioKey") }
                val audioFile = audioKey?.let { File(app.cacheDir, "speech/$it") }
                check(audioFile != null && WaveAudio.durationMs(audioFile) > 0 && app.dao.queue().isEmpty())
                check(main { active.mediaItemCount <= 2 && active.currentMediaItem?.localConfiguration == null })
                report.add("PASS live chapter: actual normal WebView, local synthesized audio file, correct book/chapter metadata, player READY and playing; no listening-quality claim")
                if (fullChapter) {
                    // Use this exact normal-page snapshot throughout the audit. A later DOM
                    // mutation must not change the coverage target after speech was accepted.
                    command(NarrationService.RESET_LEARNING, Bundle().apply { putString("book", url.substringBeforeLast('/')) })
                    val selection = command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true) }).extras.getLong("selection")
                    command(NarrationService.WEB_PAGE, Bundle().apply {
                        putString("url", page.url); putString("title", page.title); putString("bookTitle", page.bookTitle)
                        putString("text", page.text); putString("next", page.nextUrl); putLong("selection", selection); putBoolean("autoNext", false)
                    })
                    awaitCondition { main { active.isPlaying && active.currentMediaItem?.mediaId == "web:$url" } }
                    FullChapterPlaybackChecks(this@ReaderRegressionInstrumentation, app, active, report).run(page)
                }
                command(NarrationService.WEB_PAUSE)
                return@runBlocking
            }
            val originalSettings = app.listening.settings.value
            val root = File(app.filesDir, "books")
            suspend fun fixture(): Pair<Book, Chapter> {
                val id = "regression-${UUID.randomUUID()}"
                val folder = BookFiles.directory(root, id).apply { mkdirs() }
                val text = File(folder, "0.txt").apply { writeText("This original fixture describes a quiet road and a traveller returning home. ".repeat(3)) }
                val book = Book(id, "Original regression fixture", "Roadleaf", "author-file")
                val chapter = Chapter("$id:0", id, 0, "Original chapter", text.path)
                app.dao.importBook(book, listOf(chapter)); created.add(id)
                return book to chapter
            }
            val (removed, removedChapter) = fixture()
            val (kept, keptChapter) = fixture()
            val staleProgress = Progress(removed.id, removedChapter.id, 3, 50)
            app.dao.saveBookProgress(staleProgress)
            app.dao.saveBookProgress(Progress(kept.id, keptChapter.id, 0, 0))
            app.dao.replaceQueue(listOf(QueueEntry(0, removedChapter.id), QueueEntry(1, keptChapter.id)))
            check(command(NarrationService.DELETE_BOOK, Bundle().apply { putString("bookId", removed.id) }).resultCode == SessionResult.RESULT_SUCCESS)
            check(app.dao.book(removed.id) == null && app.dao.chapter(removedChapter.id) == null && app.dao.progress(removed.id) == null)
            check(!BookFiles.directory(root, removed.id).exists())
            check(app.dao.book(kept.id) != null && app.dao.progress(kept.id) != null && File(keptChapter.textPath).exists())
            check(app.dao.queue().map { it.chapterId } == listOf(keptChapter.id))
            check(app.listening.settings.value == originalSettings)
            report.add("PASS delete: removes selected text, rows, progress and queue; preserves other book and voice settings")

            app.dao.saveBookProgress(staleProgress)
            check(app.dao.progress(removed.id) == null)
            report.add("PASS deleted progress cannot be recreated by a delayed checkpoint")

            val url = "https://freewebnovel.com/novel/roadleaf-original-test/chapter-1"
            fun begin() = command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true) })
            val first = begin()
            check(first.resultCode == SessionResult.RESULT_SUCCESS)
            check(main { active.mediaItemCount } == 0 && app.dao.queue().isEmpty())
            check(app.getSharedPreferences("playback-target", 0).getString("kind", null) == "web")
            check(app.activeNarration.value.contains("no audio loaded"))
            report.add("PASS load-new-book: acknowledges empty active player and persisted queue before accepting text")

            val second = begin()
            fun page(selection: Long) = Bundle().apply {
                putString("url", url); putLong("selection", selection)
                putString("title", "Original chapter one"); putString("bookTitle", "Roadleaf original fixture")
                putString("text", "A traveller studied the map beside the road. She smiled as the clouds cleared. ".repeat(3))
                putBoolean("autoNext", false)
            }
            check(command(NarrationService.WEB_PAGE, page(first.extras.getLong("selection"))).resultCode != SessionResult.RESULT_SUCCESS)
            command(NarrationService.WEB_PAUSE)
            check(command(NarrationService.WEB_PAGE, page(second.extras.getLong("selection"))).resultCode != SessionResult.RESULT_SUCCESS)
            check(main { active.mediaItemCount } == 0)
            report.add("PASS stale or paused extraction cannot resurrect previous audio")

            val current = begin()
            check(command(NarrationService.WEB_PAGE, page(current.extras.getLong("selection"))).resultCode == SessionResult.RESULT_SUCCESS)
            val end = System.currentTimeMillis() + 10_000
            while (main { active.currentMediaItem?.mediaMetadata?.extras?.getString("webUrl") } != url && System.currentTimeMillis() < end) Thread.sleep(100)
            check(main { active.currentMediaItem?.mediaMetadata?.extras?.getString("webUrl") } == url)
            check(main { active.currentMediaItem?.mediaMetadata?.artist?.toString() } == "Roadleaf original fixture")
            command(NarrationService.WEB_PAUSE)
            report.add("PASS replacement media is attributed to the new webpage; local voice completion is not asserted")

            check(command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true); putBoolean("navigate", true) }).resultCode == SessionResult.RESULT_SUCCESS)
            check(app.webNavigation.value?.url == url && app.dao.queue().isEmpty())
            check(main { active.currentMediaItem?.mediaId } == "web-loading")
            command(NarrationService.WEB_PAUSE)
            // The command acknowledgement and controller's player events arrive separately.
            awaitCondition { app.webNavigation.value == null && !main { active.playWhenReady } }
            report.add("PASS Library add-webpage flow requests the new URL after clearing the queue and cancels on pause")

            val reader = startActivitySync(Intent(targetContext, WebReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = reader
            val browser = browser(reader)
            val html = """<html><head><title>Original UI fixture</title><meta property="og:novel:book_name" content="Roadleaf UI fixture"></head>
                <body><div id="chr-content"><h3>Chapter 1: Original fixture</h3><p>A traveller returned home beside a quiet road. This original text tests the chapter button without copying a real novel.</p></div></body></html>"""
            main { browser.resumeTimers(); browser.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url) }
            awaitCondition { main { browser.title == "Original UI fixture" && browser.progress == 100 } }
            main { requireNotNull(button(reader.findViewById(android.R.id.content), "Read loaded chapter")).performClick() }
            awaitCondition { main { active.currentMediaItem?.mediaMetadata?.artist?.toString() == "Roadleaf UI fixture" } }
            check(main { active.currentMediaItem?.mediaMetadata?.extras?.getString("webUrl") } == url)
            report.add("PASS actual Read loaded chapter button extracts the displayed WebView fixture and selects its audio")

            val darkHtml = """<html><head><title>Dark UI fixture</title><meta property="og:novel:novel_name" content="Roadleaf dark fixture">
                <style>body { background: #202020; color: #eeeeee; } #article { margin-top: 2400px; }</style></head>
                <body><div class="txt"><div id="article"><h4>Chapter 1: Original dark fixture</h4>
                <p>A traveller followed a quiet road after sunset. The chapter is below the viewport and its text remains readable without scrolling.</p>
                <p>She arrived home at the end of this original test chapter.</p></div></div></body></html>"""
            main { browser.resumeTimers(); browser.loadDataWithBaseURL(url, darkHtml, "text/html", "UTF-8", url) }
            awaitCondition { main { browser.title == "Dark UI fixture" && browser.progress == 100 } }
            // Dark by the page's own style, or by Roadleaf's dark pages option (applied as a style on Android 8–12).
            val background = javascript(browser, "getComputedStyle(document.body).backgroundColor")
            val channels = Regex("\\d+").findAll(background).take(3).map { it.value.toInt() }.toList()
            check(channels.size == 3 && channels.all { it < 64 }) { "The fixture page is not dark: $background" }
            check(javascript(browser, "window.scrollY").toInt() == 0)
            main { requireNotNull(button(reader.findViewById(android.R.id.content), "Read loaded chapter")).performClick() }
            awaitCondition { main { active.currentMediaItem?.mediaMetadata?.artist?.toString() == "Roadleaf dark fixture" && active.currentMediaItem?.mediaId?.startsWith("web:") == true } }
            check(javascript(browser, "window.scrollY").toInt() == 0)
            report.add("PASS actual dark WebView: #article below viewport is read with the page kept at scroll position zero")

            command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true); putBoolean("navigate", true) })
            main { browser.resumeTimers(); browser.loadDataWithBaseURL(url, darkHtml, "text/html", "UTF-8", url) }
            awaitCondition { main { browser.title == "Dark UI fixture" && browser.progress == 100 } }
            main { requireNotNull(button(reader.findViewById(android.R.id.content), "Read loaded chapter")).performClick() }
            awaitCondition { main { active.currentMediaItem?.mediaMetadata?.artist?.toString() == "Roadleaf dark fixture" && active.currentMediaItem?.mediaId?.startsWith("web:") == true } }
            check(app.webNavigation.value == null)
            report.add("PASS Read loaded chapter replaces a pending automatic scan immediately")

            main { browser.resumeTimers(); browser.loadDataWithBaseURL(url, "<html><head><title>Unready fixture</title></head><body><p>No chapter container exists here.</p></body></html>", "text/html", "UTF-8", url) }
            awaitCondition { main { browser.title == "Unready fixture" && browser.progress == 100 } }
            main { requireNotNull(button(reader.findViewById(android.R.id.content), "Read loaded chapter")).performClick() }
            val status = main { WebReaderActivity::class.java.getDeclaredField("status").apply { isAccessible = true }.get(reader) as TextView }
            try { awaitCondition { main { active.mediaItemCount == 0 && status.text.toString().startsWith("Page not loaded:") } } }
            catch (error: Exception) { error("Failed-page state: ${main { "items=${active.mediaItemCount}, playing=${active.playWhenReady}, status=${status.text}" }}; ${error.message}") }
            check(!main { active.playWhenReady } && app.dao.queue().isEmpty())
            report.add("PASS failed webpage extraction leaves no old media or queue to resume")

            command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true); putBoolean("navigate", true) })
            main { browser.resumeTimers(); browser.loadDataWithBaseURL(url, "<html><head><title>Unready timed fixture</title></head><body>No supported chapter here.</body></html>", "text/html", "UTF-8", url) }
            awaitCondition { main { browser.title == "Unready timed fixture" && browser.progress == 100 } }
            awaitCondition(35_000) { app.webNavigation.value == null && main { active.mediaItemCount == 0 && !active.playWhenReady } }
            awaitCondition { main { status.text.contains("30 seconds") && !status.text.contains("still loading") } }
            check(!app.activeNarration.value.contains("Loading webpage"))
            report.add("PASS unreadable automatic page stops within the 30-second window, clears loading media and displays a terminal error")

            // Since any secure page can be read (0.3.17), a shared https link opens in the reader, ready for Read loaded chapter.
            val sharedBook = "https://royalroad.com/fiction/${System.nanoTime()}/roadleaf-share-fixture"
            createdWebBooks.add(sharedBook.substringBeforeLast('/'))
            main { targetContext.startActivity(Intent(targetContext, WebReaderActivity::class.java).setAction(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Original book link: $sharedBook").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            val address = main { WebReaderActivity::class.java.getDeclaredField("address").apply { isAccessible = true }.get(reader) as android.widget.EditText }
            awaitCondition(10_000) { main { LiveWebPages.canonical(address.text.toString()) } == LiveWebPages.canonical(sharedBook) }
            check(app.webNavigation.value == null) { "Sharing started a page load: ${app.webNavigation.value}" }
            check(!main { active.playWhenReady }) { "Sharing started playback: ${main { active.currentMediaItem?.mediaId }} • ${app.playbackStatus.value}" }
            report.add("PASS a shared secure link opens in the reader without loading the previous book, starting an automatic page load or starting narration")

            // A share without a secure link never falls back to the previous reader page.
            val lastPage = app.getSharedPreferences("web-reader", 0).getString("url", null)
            main { targetContext.startActivity(Intent(targetContext, WebReaderActivity::class.java).setAction(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Read this later: http://example.com/original-story").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            awaitCondition(10_000) { main { reader.isFinishing || reader.isDestroyed } }
            check(app.webNavigation.value == null && !main { active.playWhenReady }) { "An insecure share started reading" }
            check(app.getSharedPreferences("web-reader", 0).getString("url", null) == lastPage) { "An insecure share changed the reader's page" }
            report.add("PASS a share without a secure link returns to the Library without opening the previous page or starting narration")

            app.casts.save(kept.id, BookVoiceCast(mainCharacter = "Quinn Fixture"))
            main { targetContext.startActivity(Intent(targetContext, MainActivity::class.java).putExtra("voiceCastBook", kept.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
            // The main character field is below the import and export buttons; scroll to it.
            awaitCondition { containsText(uiAutomation.rootInActiveWindow, "Quinn Fixture").also { found -> if (!found) scrollForward(uiAutomation.rootInActiveWindow) } }
            report.add("PASS direct Character voices request selects the requested book after a cold library load, not the first book")
        } }
        catch (error: Throwable) {
            report.add("FAIL ${error.stackTraceToString().take(3500)}")
        } finally {
            runBlocking {
                created.forEach { id ->
                    runCatching { app.dao.deleteBook(id); BookFiles.delete(File(app.filesDir, "books"), id); app.getSharedPreferences("voice-cast", 0).edit().remove(id).commit(); app.learnedCasts.clear(id) }
                }
                createdWebBooks.forEach { app.dao.removeWebBook(it) }
                if (!websiteChecks && liveProbeUrl == null) listOf("roadleaf-original-test", "roadleaf-preparation-test", "roadleaf-audio-test", "roadleaf-adaptive-test").forEach {
                    app.dao.removeWebBook("https://freewebnovel.com/novel/$it")
                    app.learnedCasts.clear("https://freewebnovel.com/novel/$it")
                }
            }
            main { controller?.pause(); controller?.release(); activity?.finish() }
        }
        finish(if (report.any { it.startsWith("FAIL") }) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            Bundle().apply { putString("stream", report.joinToString("\n", "\n", "\n")) })
    }
}
