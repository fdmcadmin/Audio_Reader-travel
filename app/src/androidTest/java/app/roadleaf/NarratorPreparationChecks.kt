package app.roadleaf

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.roadleaf.data.Book
import app.roadleaf.data.Chapter
import app.roadleaf.playback.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringReader
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class NarratorPreparationChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp,
    private val controller: MediaController, private val report: MutableList<String>) {
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(75)
        check(condition()) { "Narrator state not reached: ${app.playbackStatus.value}" }
    }
    private fun command(action: String, args: Bundle = Bundle.EMPTY) = main {
        controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }.get(20, TimeUnit.SECONDS).also { check(it.resultCode == SessionResult.RESULT_SUCCESS) }
    suspend fun run() {
        val url = "https://freewebnovel.com/novel/roadleaf-preparation-test/chapter-1"
        val prose = "Original preparation test ${UUID.randomUUID()}. " +
            "A traveller reached a quiet station beside the river. She read her map and continued toward the lights of home. ".repeat(90)
        val book = Book("live-web", "Original preparation fixture", "Roadleaf", "live-web")
        val chapter = Chapter(url, book.id, 0, "Original preparation chapter", "")
        val first = ChapterNarrationQueue(listOf(chapter), mapOf(book.id to book), fastStart = true, open = { StringReader(prose) }).use { requireNotNull(it.peek()) }
        val old = requireNotNull(SpeechChunkReader(StringReader(prose)).use { it.next() })
        val speech = main { OfflineSpeech(app) }
        val files = mutableSetOf<File>()
        try {
            val settings = app.listening.settings.value
            val engine = withContext(Dispatchers.Main) { speech.catalog(settings.enginePackage).enginePackage }
            var started = SystemClock.elapsedRealtime()
            val openingFile = withContext(Dispatchers.Main) { speech.render(first.text, settings) }.also(files::add)
            val openingMs = SystemClock.elapsedRealtime() - started
            started = SystemClock.elapsedRealtime()
            val oldFile = withContext(Dispatchers.Main) { speech.render(old.text, settings) }.also(files::add)
            val oldMs = SystemClock.elapsedRealtime() - started
            started = SystemClock.elapsedRealtime()
            check(withContext(Dispatchers.Main) { speech.render(first.text, settings) } == openingFile)
            val cachedMs = SystemClock.elapsedRealtime() - started
            check(first.text.length <= 360 && old.text.length > 1200 && openingFile.length() > 44 && oldFile.length() > 44)
            report.add("PASS local preparation: opening ${first.text.length} chars=${openingMs}ms; former ${old.text.length} chars=${oldMs}ms; cached opening=${cachedMs}ms; engine=$engine; not a phone benchmark")
            main { speech.close() }

            fun begin() = command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url) })
            command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true) })
            fun load(selection: Long) = command(NarrationService.WEB_PAGE, Bundle().apply {
                putString("url", url); putString("title", chapter.title); putString("bookTitle", book.title)
                putString("text", prose); putBoolean("autoNext", false); putLong("selection", selection)
            })
            load(begin().extras.getLong("selection"))
            await { main { controller.isPlaying && controller.currentMediaItem?.mediaId?.startsWith("web:") == true } }
            check(main { controller.currentMediaItem?.mediaMetadata?.extras?.getInt("segmentChars") } == first.text.length)
            val firstKey = main { controller.currentMediaItem?.mediaMetadata?.extras?.getString("audioKey") }
            check(!firstKey.isNullOrBlank() && File(app.cacheDir, "speech/$firstKey").length() > 44)
            Thread.sleep(650)
            main { controller.pause() }
            val checkpoint = app.getSharedPreferences("web-listening", 0)
            await { checkpoint.getInt("segmentChars", 0) == first.text.length && checkpoint.getLong("audioMs", 0) > 200 }
            val savedMs = checkpoint.getLong("audioMs", 0)
            load(begin().extras.getLong("selection"))
            await { main { controller.isPlaying && controller.currentMediaItem?.mediaId?.startsWith("web:") == true } }
            check(main { controller.currentMediaItem?.mediaMetadata?.extras?.getString("audioKey") } == firstKey)
            check(main { controller.currentPosition } in savedMs..(savedMs + 2000))
            report.add("PASS webpage resume restores the exact short speech span, reuses its audio file and retains saved audio milliseconds")
        } finally {
            command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true) })
            main { speech.close() }
            files.forEach { it.delete() }
        }
    }
}
