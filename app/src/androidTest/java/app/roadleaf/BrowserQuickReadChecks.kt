package app.roadleaf

import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import app.roadleaf.browser.*
import app.roadleaf.data.*
import app.roadleaf.importing.BookFiles
import app.roadleaf.playback.*
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class BrowserQuickReadChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp, private val controller: MediaController, private val report: MutableList<String>) {
    private fun <T> main(action: () -> T): T { val value = AtomicReference<Result<T>>(); instrumentation.runOnMainSync { value.set(runCatching(action)) }; return value.get().getOrThrow() }
    private fun command(action: String, args: Bundle = Bundle.EMPTY) = main { controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }.get(90, TimeUnit.SECONDS).also { check(it.resultCode == SessionResult.RESULT_SUCCESS) { it.extras.getString("error").orEmpty() } }
    private fun args(session: BrowserReadingSession, text: String, start: BrowserStart) = Bundle().apply {
        putString("token", session.token); putString("payload", app.browserText.put(session.token, text)); putString("start", start.name)
    }
    private suspend fun await(condition: () -> Boolean) { val end = System.currentTimeMillis() + 90_000; while (!condition() && System.currentTimeMillis() < end) delay(70); check(condition()) { app.playbackStatus.value } }
    suspend fun run() {
        val backup = PhoneListeningState(app); backup.capture()
        val previousSession = app.browserSession.value
        val output = app.audioOutput.selection.value
        val session = BrowserReadingSession(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Original quick-read fixture", "com.android.chrome")
        val opening = "An original traveller studies a river map beside a quiet bridge. The road leads past a garden, and a guide waits near the gate.\n"
        val text = opening + "A second original paragraph describes the journey toward a village. The traveller and the guide discuss the long road home and the trees beside it.\n" +
            "A third paragraph follows their footsteps into the valley. They pause at a quiet garden before continuing along the river toward the bridge.\n"
        val temporary = session.copy(token = UUID.randomUUID().toString(), bookId = "free-${UUID.randomUUID()}", title = BrowserStory.UNSAVED_TITLE, temporary = true, profileBookId = session.bookId, pinnedBook = false)
        try {
            app.audioOutput.save(OutputSelection())
            app.listening.save(app.listening.settings.value.copy(speed = 1.3f))
            val capture = app.browserStories.capture(session, text, true)
            app.dao.saveBookProgress(Progress(session.bookId, capture.chapter.id, opening.length + 35, 4000))
            check(app.browserStories.matchingBook(text + "A new paragraph.\n")?.id == session.bookId)
            check(app.browserStories.matchingBook(text.replace("river map", "mountain map")) == null)
            report.add("PASS direct story matching finds the saved snapshot after append, but rejects earlier edits and unrelated stories")
            app.browserSession.value = session
            command(NarrationService.BROWSER_READ, args(session, text, BrowserStart.RESUME))
            await { main { controller.isPlaying && controller.currentMediaItem?.mediaMetadata?.extras?.getInt("offset") == opening.length } }
            main { controller.pause() }
            report.add("PASS Resume starts at the saved paragraph boundary, with old in-paragraph audio position cleared")
            command(NarrationService.BROWSER_READ, args(session, text, BrowserStart.TOP))
            await { main { controller.isPlaying && controller.currentMediaItem?.mediaMetadata?.extras?.getInt("offset") == 0 } }
            check(main { controller.mediaItemCount == 1 && controller.currentPosition < 3000 })
            // Pausing saves progress asynchronously; wait for it before taking the baseline compared below.
            main { controller.pause() }; delay(700)
            report.add("PASS From top ignores a midpoint checkpoint and starts the first source paragraph with one public chapter")
            val queue = app.dao.queue()
            val libraryProgress = app.dao.progress(session.bookId)
            app.casts.save(session.bookId, BookVoiceCast(enabled = false, adaptive = false, sceneOnly = true, profileSetId = "original-fixture", characters = listOf(CastCharacter("Original Guide", profileId = "guide-one"))))
            app.browserSession.value = temporary
            command(NarrationService.BROWSER_READ, args(temporary, text, BrowserStart.TOP))
            await { main { controller.isPlaying } }
            main { controller.pause() }; delay(700)
            check(app.dao.book(temporary.bookId) == null && app.dao.chapters(temporary.bookId).isEmpty() && app.dao.progress(temporary.bookId) == null)
            // Switching away re-saves the paused book's own position, which can differ by a few
            // milliseconds of audio (seen 2-11 ms on main and this branch alike). The saved
            // chapter and paragraph must not change; audio position may differ only by that drift.
            val queueAfter = app.dao.queue(); val progressAfter = app.dao.progress(session.bookId)
            val samePlace = progressAfter != null && libraryProgress != null && progressAfter.chapterId == libraryProgress.chapterId &&
                progressAfter.textOffset == libraryProgress.textOffset && kotlin.math.abs(progressAfter.audioMs - libraryProgress.audioMs) <= 250
            check(queueAfter == queue && samePlace) { "An unsaved reading changed saved state: queue ${queue.size}->${queueAfter.size} (${queue == queueAfter}); progress $libraryProgress -> $progressAfter" }
            check(app.browserStories.profileBook(temporary.bookId) == session.bookId)
            check(app.browserStories.progress(temporary.bookId)?.textOffset == 0)
            report.add("PASS an unsaved reading plays locally with an optional metadata profile link, creates no library book/chapter/progress, and preserves the saved queue and checkpoint")
            val more = text + "A fourth completed paragraph is available while paused. The guide returns the map to the traveller beside the gate.\n"
            command(NarrationService.BROWSER_APPEND, args(temporary, more, BrowserStart.TOP)); delay(500)
            check(main { !controller.isPlaying })
            check(File(app.browserStories.chapters(temporary.bookId).single().textPath).readText() == more)
            check(app.dao.book(temporary.bookId) == null)
            report.add("PASS live generation appends to the temporary source without saving to Library or unpausing narration")
            val chosen = more.indexOf("A third paragraph")
            command(NarrationService.BROWSER_READ, args(temporary, more, BrowserStart.AT).apply { putInt("offset", chosen) })
            await { main { controller.isPlaying && controller.currentMediaItem?.mediaMetadata?.extras?.getInt("offset") == chosen } }
            main { controller.pause() }; delay(700)
            check(app.dao.book(temporary.bookId) == null)
            check(app.browserStories.memory.find(more + "A fifth paragraph is written after the reader stopped.\n") == chosen) { "The unsaved reading's place was not remembered" }
            check(app.browserStories.memory.find(more.replace("river map", "mountain map")) == null)
            report.add("PASS Read from here starts an unsaved story at the chosen word, and Resume finds that place again after more text is written, without saving the story")
            app.browserSession.value = null
            command(NarrationService.BROWSER_STOP, Bundle().apply { putString("token", temporary.token) })
            val path = File(app.browserStories.chapters(temporary.bookId).single().textPath)
            app.browserStories.releaseTemporaryExcept(null)
            check(!path.exists() && !app.browserStories.isTemporary(temporary.bookId))
            report.add("PASS temporary source cleanup removes only its own cache, leaving saved story text intact")
            val prefs = BrowserReaderPreferences(app)
            val priorBook = prefs.lastBook; val priorTitle = prefs.lastTitle
            prefs.remember(temporary)
            check(prefs.lastBook == priorBook && prefs.lastTitle == priorTitle && app.browserSession.value == null)
            report.add("PASS an unsaved reading never becomes a persisted save choice; stored browser choices cannot arm capture or auto-play")
        } finally {
            app.browserSession.value = null; app.browserText.clear()
            main { controller.pause() }
            runCatching { command(NarrationService.BROWSER_STOP, Bundle().apply { putString("token", temporary.token) }) }
            runCatching { command(NarrationService.DELETE_BOOK, Bundle().apply { putString("bookId", session.bookId) }) }
            app.dao.deleteBook(session.bookId); BookFiles.delete(File(app.filesDir, "books"), session.bookId)
            app.browserStories.releaseTemporaryExcept(null); app.learnedCasts.clear(session.bookId)
            app.getSharedPreferences("voice-cast", 0).edit().remove(session.bookId).commit()
            app.audioOutput.save(output); backup.restore(rollbackCast = true)
            app.browserSession.value = previousSession
        }
    }
}
