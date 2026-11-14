package app.roadleaf

import android.graphics.RectF
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import app.roadleaf.browser.*
import app.roadleaf.data.Book
import app.roadleaf.playback.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real model and persistence checks, confined to the isolated test package and original neutral text. */
class KokoroStoryChecks(private val app: RoadleafApp, private val report: MutableList<String>) {
    suspend fun run() {
        check(app.packageName == "app.roadleaf.storypreview.dev") { "Run only against the isolated development preview." }
        val archive = File(app.getExternalFilesDir(null), "kokoro-int8-multi-lang-v1_0.tar.bz2")
        if (!NativeKokoro.installed(app)) {
            check(archive.isFile) { "Push the official model to the test app's external files directory first." }
            val started = SystemClock.elapsedRealtime()
            KokoroModelImport.install(app, Uri.fromFile(archive))
            report.add("PASS production model importer; elapsedMs=${SystemClock.elapsedRealtime() - started}")
        }
        check(NativeKokoro.options(app).size == 28)
        val speech = OfflineSpeech(app)
        try {
            val text = "The traveller follows the quiet river toward the village."
            val hashes = mutableSetOf<String>()
            for (voice in listOf("kokoro:2", "kokoro:18", "kokoro:26")) {
                val started = SystemClock.elapsedRealtime()
                val audio = speech.render(text, ListeningSettings(NativeKokoro.ENGINE_ID, voice))
                check(audio.length() > 44)
                check(audio.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) } == "RIFF")
                val hash = MessageDigest.getInstance("SHA-256").digest(audio.readBytes()).joinToString("") { "%02x".format(it) }
                check(hashes.add(hash)) { "Different speakers produced identical audio." }
                report.add("PASS ${NativeKokoro.speakerName(voice)} via production OfflineSpeech; bytes=${audio.length()}; elapsedMs=${SystemClock.elapsedRealtime() - started}")
            }
        } finally { speech.close() }
        val id = "kokoro-fixture-${UUID.randomUUID()}"
        val other = "$id-other"
        val url = "https://novelai.net/stories?id=original-fixture"
        val cast = BookVoiceCast(narratorVoice = "kokoro:26", characters = listOf(
            CastCharacter("Aster", voice = "kokoro:2", pronoun = CharacterPronoun.SHE),
            CastCharacter("Rowan", voice = "kokoro:18", pronoun = CharacterPronoun.HE)))
        try {
            app.dao.insertBook(Book(id, "Original fixture", "Test", BrowserStory.SOURCE, url))
            app.casts.save(id, cast)
            check(app.casts.get(id) == cast)
            check(app.casts.get(other).characters.isEmpty())
            check(app.casts.get(other).narratorVoice.isEmpty())
            val text = "Aster watches the river while Rowan checks the path.\nA calm morning begins."
            app.browserStories.capture(BrowserReadingSession(UUID.randomUUID().toString(), id, "Original fixture", "com.android.chrome"), text, true)
            check(app.dao.book(id)?.sourceUrl == url)
            check(app.browserStories.matchingBook(text)?.id == id)
            check(app.browserStories.matchingBook("An entirely different manuscript begins in a distant place.") == null)
            report.add("PASS exact bookmark survives capture; saved text and cast remain isolated by story")
            check(SceneReadAhead.prepare(cast, "[POV: Aster]\nAster wonders about the path.", DialogueState()).cast.storyVoice == "kokoro:2")
            check(SceneReadAhead.prepare(cast, "[POV: Rowan]\nRowan considers the river.", DialogueState()).cast.storyVoice == "kokoro:18")
            report.add("PASS dual POV chooses each character's distinct Kokoro speaker")
            val viewport = RectF(0f, 100f, 400f, 800f)
            check(!BrowserEditorNodes.visibleCharacter(RectF(0f, -80f, 12f, -60f), viewport))
            check(BrowserEditorNodes.visibleCharacter(RectF(0f, 100f, 12f, 120f), viewport))
            check(!BrowserEditorNodes.visibleCharacter(RectF(Float.NaN, 100f, 12f, 120f), viewport))
            report.add("PASS visible-top geometry rejects offscreen and invalid character positions")
        } finally {
            val chapters = app.dao.chapters(id)
            app.dao.deleteBook(id)
            app.getSharedPreferences("voice-cast", 0).edit().remove(id).commit()
            chapters.forEach { File(it.textPath).delete() }
        }
        report.add("INFO process PSS after native synthesis=${Debug.getPss()} KiB; endurance and listening quality are separate checks")
    }
}
