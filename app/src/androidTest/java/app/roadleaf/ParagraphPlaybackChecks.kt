package app.roadleaf

import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import app.roadleaf.data.*
import app.roadleaf.importing.BookFiles
import app.roadleaf.playback.NarrationService
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class ParagraphPlaybackChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp,
    private val controller: MediaController, private val report: MutableList<String>) {
    private fun <T> main(action: () -> T): T {
        val value = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { value.set(runCatching(action)) }; return value.get().getOrThrow()
    }
    private fun command(action: String, args: Bundle = Bundle.EMPTY) = main {
        controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }.get(20, TimeUnit.SECONDS).also { check(it.resultCode == SessionResult.RESULT_SUCCESS) }
    private suspend fun await(condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 90_000
        while (!condition() && System.currentTimeMillis() < deadline) delay(60)
        check(condition()) { "Paragraph playback fixture did not reach expected state: ${app.playbackStatus.value}" }
    }
    suspend fun run() {
        val backup = PhoneListeningState(app)
        backup.capture()
        val id = UUID.randomUUID().toString()
        val root = File(app.filesDir, "books")
        try {
            app.listening.save(app.listening.settings.value.copy(speed = 1f))
            val text = "An original traveller followed the road beside a river.\n\nThe second paragraph describes a quiet hillside, a distant village, and a traveller studying a map before walking toward home."
            val at = text.indexOf("The second")
            val directory = BookFiles.directory(root, id).apply { mkdirs() }
            val file = File(directory, "0.txt").apply { writeText(text) }
            app.dao.importBook(Book(id, "Original paragraph fixture", "Roadleaf", "author-file"), listOf(Chapter("$id:0", id, 0, "Original chapter", file.path)))
            app.dao.saveBookProgress(Progress(id, "$id:0", at + 24, 700))
            command(NarrationService.RESUME_PARAGRAPH, Bundle().apply { putString("bookId", id) })
            await { main { controller.isPlaying && controller.currentPosition > 800 } }
            main { controller.pause() }
            await { app.dao.progress(id)?.let { it.textOffset == at && it.audioMs > 0 } == true }
            check(main { controller.mediaItemCount == 1 && controller.currentMediaItem?.mediaId == "chapter:$id:0" })
            report.add("PASS real offline narration resumes from the original paragraph boundary despite a saved mid-paragraph source offset; chapter stays one public media item")
            command(NarrationService.RESUME_PARAGRAPH, Bundle().apply { putString("bookId", id) })
            await { main { controller.isPlaying } }
            check(main { controller.currentPosition < 700 })
            main { controller.pause() }
            report.add("PASS an explicit second Resume paragraph restarts the cached paragraph rather than resuming its saved audio milliseconds")
            val word = text.indexOf("distant")
            command(NarrationService.START_AT, Bundle().apply { putString("bookId", id); putString("chapterId", "$id:0"); putInt("offset", word) })
            await { main { controller.isPlaying && controller.currentMediaItem?.mediaId == "chapter:$id:0" && controller.currentPosition > 300 } }
            main { controller.pause() }
            await { app.dao.progress(id)?.textOffset == word }
            report.add("PASS Start from a word begins a Library chapter exactly at the chosen mid-paragraph word, not at its paragraph")
        } finally {
            main { controller.pause() }
            runCatching { command(NarrationService.DELETE_BOOK, Bundle().apply { putString("bookId", id) }) }
            app.dao.deleteBook(id); BookFiles.delete(root, id); app.learnedCasts.clear(id)
            backup.restore(rollbackCast = true)
        }
    }
}
