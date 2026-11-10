package app.roadleaf

import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import app.roadleaf.data.Book
import app.roadleaf.data.Chapter
import app.roadleaf.playback.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.StringReader
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class AdaptiveLearningChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp,
    private val controller: MediaController, private val report: MutableList<String>) {
    private fun <T> main(action: () -> T): T {
        val value = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { value.set(runCatching(action)) }; return value.get().getOrThrow()
    }
    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(75)
        check(condition()) { "Adaptive narration state missing: ${app.playbackStatus.value}" }
    }
    private fun command(action: String, args: Bundle = Bundle.EMPTY) = main {
        controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }.get(30, TimeUnit.SECONDS).also { check(it.resultCode == SessionResult.RESULT_SUCCESS) }
    suspend fun run() {
        val identity = UUID.randomUUID().toString()
        val a = "adaptive-$identity-a"; val b = "adaptive-$identity-b"
        val url = "https://freewebnovel.com/novel/roadleaf-adaptive-test/chapter-1"
        val key = url.substringBeforeLast('/')
        val speech = main { OfflineSpeech(app) }
        val settings = app.listening.settings.value
        try {
            check(app.casts.get(a).enabled && app.casts.get(a).adaptive && app.learnedCasts.get(a).windows == 0)
            app.getSharedPreferences("voice-cast", 0).edit().putString(a, """{"enabled":false,"main":"Previous MC"}""").commit()
            check(!app.casts.get(a).enabled && !app.casts.get(a).adaptive)
            app.getSharedPreferences("voice-cast", 0).edit().putString(b, """{"enabled":true,"main":"Previous MC","mainVoice":"previous"}""").commit()
            check(app.casts.get(b).enabled && app.casts.get(b).adaptive && app.casts.get(b).mainVoice == "previous")
            report.add("PASS new and previously enabled casts use Automatic with existing overrides; saved narrator-only choices remain off")
            val learned = LearnedCast(8, "Arlo", listOf(LearnedCharacter("Arlo", 10, 5, 4, 3, 2, voice = "fixed", engine = "engine", attitudeScore = -2, attitudeWindows = 4)), listOf("hash"),
                perspectives = listOf(ChapterPerspective("test-chapter", listOf(ViewpointEvidence("Bram", 6, 3)))))
            app.learnedCasts.save(a, learned); app.learnedCasts.save(b, learned.copy(main = "Bram"))
            check(LearnedCastPreferences(app).get(a) == learned && app.learnedCasts.get(b).main == "Bram")
            app.learnedCasts.clear(a)
            check(app.learnedCasts.get(a) == LearnedCast() && app.learnedCasts.get(b).main == "Bram")
            report.add("PASS learned identities, attitude evidence and fixed voices survive preference reopen, stay book-specific, and reset independently")

            val profile = BookVoiceCast(mainCharacter = "Arlo", mainVoice = settings.voiceName)
            app.casts.save(key, profile); app.learnedCasts.clear(key)
            val prose = "Arlo said, \"The road is clear.\" Arlo wondered about the spring. Arlo was a man. " +
                "Mira said, \"We can follow the river.\" Mira was a woman. A quiet breeze crossed the meadow. ".repeat(40)
            val selection = command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true) }).extras.getLong("selection")
            command(NarrationService.WEB_PAGE, Bundle().apply {
                putString("url", url); putString("title", "Chapter 1: The Quiet Road"); putString("bookTitle", "Original adaptive fixture")
                putString("text", prose); putLong("selection", selection); putBoolean("autoNext", false)
            })
            await { main { controller.isPlaying && controller.currentMediaItem?.mediaId == "web:$url" } }
            val firstKey = main { controller.currentMediaItem?.mediaMetadata?.extras?.getString("audioKey") }
            check(main { controller.currentMediaItem?.mediaMetadata?.extras?.getInt("learnedWindows") } == 0)
            val book = Book("live-web", "Original adaptive fixture", "Roadleaf", "live-web")
            val chapter = Chapter(url, book.id, 0, "Chapter 1: The Quiet Road", "")
            val first = ChapterNarrationQueue(listOf(chapter), mapOf(book.id to book), fastStart = true, open = { StringReader(prose) }).use { requireNotNull(it.peek()) }
            val transform = SpeechText.pronouncer(app.dao.pronunciationSnapshot().map { it.written to it.spoken })
            val expected = withContext(Dispatchers.Main) { CastSpeechRenderer(speech, app.cacheDir).render(
                NarrationText.part(first.text, chapter.title, true, true), BookVoiceCast(enabled = false), DialogueState(), settings, transform) }
            check(expected.file.name == firstKey)
            await { app.learnedCasts.get(key).windows >= 1 && app.learnedCasts.get(key).people.any { it.name == "Arlo" } }
            report.add("PASS real service starts new book with exactly the narrator-only audio and commits learned names only after queuing speech")
            val status = app.playbackStatus.value
            command(NarrationService.RESET_LEARNING, Bundle().apply { putString("book", b) })
            check(main { controller.isPlaying && controller.currentMediaItem?.mediaId == "web:$url" } && app.playbackStatus.value == status)
            check(app.learnedCasts.get(b) == LearnedCast() && app.learnedCasts.get(key).windows > 0)
            report.add("PASS resetting another book's learned cast leaves the active book, narration and status untouched")
            main { controller.pause() }
            await { main { !controller.playWhenReady } }
            val before = app.learnedCasts.get(key)
            val checkpoint = WebListeningProgress(app.getSharedPreferences("web-listening", 0))
            await { checkpoint.read(url)?.audioKey == firstKey }
            command(NarrationService.RESET_LEARNING, Bundle().apply { putString("book", key) })
            await { main { controller.mediaItemCount == 0 } }
            check(app.learnedCasts.get(key) == LearnedCast() && checkpoint.read(url) != null && app.casts.get(key) == profile)
            check(before.windows > 0 && app.listening.settings.value == settings)
            report.add("PASS service-owned learning reset cancels pending generation, preserves reading progress/manual MC/narrator and leaves no stale audio")
        } finally {
            command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true) })
            main { speech.close() }
            listOf(a, b, key).forEach { app.learnedCasts.clear(it); app.getSharedPreferences("voice-cast", 0).edit().remove(it).commit() }
            app.dao.removeWebBook(key)
        }
    }
}
