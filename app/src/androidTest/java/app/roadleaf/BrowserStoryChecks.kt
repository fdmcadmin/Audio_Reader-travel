package app.roadleaf

import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.room.Room
import app.roadleaf.browser.*
import app.roadleaf.data.*
import app.roadleaf.importing.BookFiles
import app.roadleaf.playback.NarrationService
import app.roadleaf.playback.LoadingAudio
import app.roadleaf.playback.OutputSelection
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class BrowserStoryChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp, private val controller: MediaController, private val report: MutableList<String>) {
    private fun <T> main(action: () -> T): T { val result = AtomicReference<Result<T>>(); instrumentation.runOnMainSync { result.set(runCatching(action)) }; return result.get().getOrThrow() }
    private fun command(action: String, args: Bundle = Bundle.EMPTY) = main { controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }.get(90, TimeUnit.SECONDS).also { check(it.resultCode == SessionResult.RESULT_SUCCESS) }
    private fun args(token: String, text: String) = Bundle().apply { putString("token", token); putString("payload", app.browserText.put(token, text)) }
    private suspend fun await(condition: suspend () -> Boolean) { val end = System.currentTimeMillis() + 90_000; while (!condition() && System.currentTimeMillis() < end) delay(70); check(condition()) { "Browser story fixture timed out: ${app.playbackStatus.value}" } }
    suspend fun run() {
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
        val store = BrowserStoryStore(app, db.library())
        val fixture = BrowserReadingSession(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Original store fixture", "com.android.chrome")
        val first = "The traveller crossed a quiet river beside a village.\nA second paragraph describes the long road home."
        val root = File(app.filesDir, "books")
        try {
            val original = store.capture(fixture, first, true)
            val reader = File(original.chapter.textPath).bufferedReader()
            val appended = store.capture(fixture, first + "\nA third paragraph extends the original story safely.\n", false)
            check(appended.chapter.id == original.chapter.id && reader.use { it.readText() } == first)
            check(File(appended.chapter.textPath).readText().startsWith(first))
            report.add("PASS append uses an immutable source revision while the old open reader remains intact; book/chapter identity stays fixed")
            check(runCatching { store.capture(fixture, "Another story entirely replaces this source with unrelated text.\n", false) }.isFailure)
            check(db.library().chapter(appended.chapter.id)?.textPath == appended.chapter.textPath)
            report.add("PASS automatic edits/story switches are rejected without replacing the saved story")
            val at = first.indexOf("A second")
            db.library().saveBookProgress(Progress(fixture.bookId, appended.chapter.id, at + 6, 40))
            val replacement = store.capture(fixture.copy(allowReplacement = true), "New introduction.\n" + first, true)
            check(replacement.replaced && replacement.matchedParagraph && replacement.chapter.id != original.chapter.id)
            check(db.library().progress(fixture.bookId)?.textOffset == at + "New introduction.\n".length)
            db.library().saveBookProgress(Progress(fixture.bookId, appended.chapter.id, 0, 0))
            check(db.library().progress(fixture.bookId)?.chapterId == replacement.chapter.id)
            report.add("PASS explicit replacement relocates the paragraph and rejects delayed progress from the previous revision")
        } finally { db.close(); BookFiles.delete(root, fixture.bookId) }

        val backup = PhoneListeningState(app); backup.capture()
        val savedOutput = app.audioOutput.selection.value
        val session = BrowserReadingSession(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Original live browser fixture", "com.android.chrome")
        val opening = "An original traveller follows a quiet river. The village waits nearby, and a friendly guide walks beside the traveller toward the bridge.\n"
        val second = opening + "A new paragraph begins beyond the bridge. The guide points toward a distant hill, and the traveller studies the road before continuing toward home.\n"
        val third = second + "Another complete paragraph arrives after waiting. The traveller rests beside a quiet garden, enjoying the cool air before the final walk home.\n"
        try {
            app.listening.save(app.listening.settings.value.copy(speed = 1.6f))
            app.browserSession.value = session
            app.audioOutput.save(OutputSelection("1".repeat(64), "Unavailable output fixture"))
            val blocked = main { controller.sendCustomCommand(SessionCommand(NarrationService.BROWSER_READ, Bundle.EMPTY), args(session.token, opening)) }.get(90, TimeUnit.SECONDS)
            check(blocked.resultCode != SessionResult.RESULT_SUCCESS && blocked.extras.getString("error").orEmpty().contains("Unavailable output fixture"))
            check(main { !controller.isPlaying })
            report.add("PASS unavailable selected audio output stays paused and returns the actual reconnect instruction in the browser result")
            app.audioOutput.save(savedOutput)
            command(NarrationService.BROWSER_READ, args(session.token, opening))
            await { main { controller.isPlaying && controller.currentPosition > 400 } }
            main { controller.pause() }
            val position = main { controller.currentPosition }
            command(NarrationService.APPLY_SETTINGS)
            command(NarrationService.BROWSER_APPEND, args(session.token, second))
            delay(1200)
            check(main { !controller.isPlaying && controller.mediaItemCount == 1 && kotlin.math.abs(controller.currentPosition - position) < 600 })
            report.add("PASS narrator-setting reload retains the live link; new generation while paused is saved without restarting narration or changing the one-chapter public queue")
            main { controller.play() }
            await { app.dao.progress(session.bookId)?.textOffset?.let { it >= opening.length } == true }
            check(main { controller.mediaItemCount == 1 })
            await { app.dao.progress(session.bookId)?.textOffset == second.length && app.playbackStatus.value.startsWith("Waiting for new completed paragraphs") }
            report.add("PASS playback crosses the old snapshot boundary and saves its exact source end instead of replaying the original text")
            command(NarrationService.BROWSER_APPEND, args(session.token, third))
            await { main { controller.isPlaying } }
            await { app.dao.progress(session.bookId)?.textOffset == third.length && app.playbackStatus.value.startsWith("Waiting for new completed paragraphs") }
            report.add("PASS generation arriving after end-of-text resumes only the new tail and retains a single public chapter")
            val fourth = third + "One last paragraph is held while the reader is paused. The quiet village closes its gates for the evening.\n"
            val otherAudio = android.media.MediaPlayer()
            try {
                otherAudio.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                otherAudio.setDataSource(LoadingAudio.file(app.cacheDir).path); otherAudio.prepare(); otherAudio.start()
                command(NarrationService.BROWSER_APPEND, args(session.token, fourth)); delay(1500)
                check(main { !controller.playWhenReady })
                report.add("PASS arriving text after EOF does not seize playback from other active media")
            } finally { otherAudio.release() }
            main { controller.play() }
            await { app.dao.progress(session.bookId)?.textOffset == fourth.length && app.playbackStatus.value.startsWith("Waiting for new completed paragraphs") }
            main { controller.pause() }
            val fifth = fourth + "The morning begins with fresh footsteps on the road. The traveller watches the sun rise beyond the river.\n"
            command(NarrationService.BROWSER_APPEND, args(session.token, fifth)); delay(1000)
            check(main { !controller.isPlaying })
            main { controller.play() }
            await { main { controller.isPlaying } }
            report.add("PASS manual pause at the waiting boundary survives new text; explicit Play continues the pending tail")
            app.browserSession.value = null
            val stale = main { controller.sendCustomCommand(SessionCommand(NarrationService.BROWSER_APPEND, Bundle.EMPTY), args(session.token, fifth + "More text.\n")) }.get(10, TimeUnit.SECONDS)
            check(stale.resultCode != SessionResult.RESULT_SUCCESS)
            report.add("PASS a revoked browser-link token cannot append text or start playback")
        } finally {
            app.browserSession.value = null
            app.browserText.clear()
            app.audioOutput.save(savedOutput)
            runCatching { command(NarrationService.BROWSER_STOP, Bundle().apply { putString("token", session.token) }) }
            main { controller.pause() }
            runCatching { command(NarrationService.DELETE_BOOK, Bundle().apply { putString("bookId", session.bookId) }) }
            app.dao.deleteBook(session.bookId); BookFiles.delete(root, session.bookId); app.learnedCasts.clear(session.bookId)
            backup.restore(rollbackCast = true)
        }
    }
}
