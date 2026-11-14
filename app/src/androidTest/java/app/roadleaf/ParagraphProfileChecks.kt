package app.roadleaf

import androidx.room.Room
import app.roadleaf.data.*
import app.roadleaf.importing.CharacterLorebook
import app.roadleaf.playback.*
import java.util.UUID

/** Isolated Room database and metadata-only fixtures; never imports the owner's lore text. */
class ParagraphProfileChecks(private val app: RoadleafApp, private val report: MutableList<String>) {
    suspend fun run() {
        val database = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
        val book = "paragraph-check-${UUID.randomUUID()}"
        val other = "$book-other"
        try {
            val dao = database.library()
            dao.importBook(Book(book, "Original fixture", "Roadleaf", "author-file"), listOf(Chapter("$book:old", book, 0, "One", "unused")))
            dao.importBook(Book(other, "Unrelated fixture", "Roadleaf", "author-file"), listOf(Chapter("$other:one", other, 0, "Other", "unused")))
            dao.saveBookProgress(Progress(book, "$book:old", 80, 400))
            dao.replaceQueue(listOf(QueueEntry(0, "$book:old"), QueueEntry(1, "$other:one")))
            val progress = Progress(book, "$book:new", 40, 0)
            dao.replaceText(book, listOf(Chapter("$book:new", book, 0, "One", "new")), progress)
            check(dao.progress(book) == progress && dao.chapter("$book:old") == null && dao.book(book)?.title == "Original fixture")
            check(dao.queue() == listOf(QueueEntry(1, "$other:one")) && dao.book(other) != null)
            report.add("PASS text replacement transaction retains the book identity, installs paragraph progress and preserves the unrelated queue/book")
            dao.saveBookProgress(Progress(book, "$book:old", 90, 500))
            check(dao.progress(book) == progress)
            report.add("PASS a delayed checkpoint from an old text revision cannot overwrite updated-story paragraph progress")
            val people = CharacterLorebook.parse("""{"format":"roadleaf-scene-profiles","version":1,"profileSetId":"fixture-profiles","entries":[{"id":"char_sample","displayName":"01 Sample — Preset","keys":["Sample","Captain Sample"]},{"id":"group","displayName":"Group","keys":["Sample"]}]}""")
            check(people.size == 1 && people.single().name == "Sample" && people.single().aliases == "Captain Sample" && people.single().role == CastRole.AUTO)
            report.add("PASS actual Android JSON import reads only designated metadata from the personal-collection sidecar")
            val voiced = CharacterLorebook.decode("""{"format":"roadleaf-scene-profiles","version":1,"profileSetId":"fixture-profiles","enginePackage":"offline.engine","mainCharacter":"Captain Sample","mainVoice":"male-one","narratorVoice":"narrator","mainPronoun":"HE","entries":[{"id":"char_sample","displayName":"Sample","keys":["Sample"],"voice":"female-one","pronoun":"SHE","delivery":"LOWER"}]}""")
            check(voiced.engine == "offline.engine" && voiced.voices == setOf("female-one", "male-one", "narrator"))
            check(voiced.characters.single().role == CastRole.CUSTOM && voiced.characters.single().pronoun == CharacterPronoun.SHE && voiced.characters.single().delivery == CharacterDelivery.LOWER)
            check(CharacterLorebook.merge(BookVoiceCast(), voiced).mainVoice == "male-one")
            report.add("PASS metadata-only voice bindings decode with explicit pronouns/delivery and main/narrator choices; no module text is needed")
            val kokoro = CharacterLorebook.decode("""{"format":"roadleaf-scene-profiles","version":1,"profileSetId":"fixture-kokoro","voiceService":"sherpa-onnx-kokoro","entries":[
                {"id":"char_aster","displayName":"Aster","category":"Characters","keys":["Aster"],"ttsProfile":{"service":"sherpa-onnx","model":"kokoro-multi-lang-v1_0","voice":"af_bella","sid":2}},
                {"id":"char_morrow","displayName":"Morrow","category":"Characters","keys":["Morrow"],"ttsProfile":{"service":"sherpa-onnx","model":"kokoro-multi-lang-v1_0","voice":"am_puck","sid":18}}]}""")
            check(kokoro.engine == NativeKokoro.ENGINE_ID && kokoro.characters.map { it.voice } == listOf("kokoro:2", "kokoro:18"))
            check(CharacterLorebook.merge(BookVoiceCast(), kokoro).profileSetId == "fixture-kokoro")
            report.add("PASS service-tagged reader profiles bind two distinct Kokoro speakers to one story without module text")
            check(runCatching { CharacterLorebook.parse("""{"lorebookVersion":6,"entries":[{"id":"char_sample","text":"Private module content"}]}""") }.isFailure)
            check(runCatching { CharacterLorebook.parse("""{"format":"roadleaf-scene-profiles","version":1,"profileSetId":"fixture","entries":[{"id":"char_sample","text":"Private module content"}]}""") }.isFailure)
            report.add("PASS full lorebooks/modules and module text disguised as voice metadata are rejected before importing any profiles")
            val cast = BookVoiceCast(sceneOnly = true, characters = people, profileSetId = "fixture-profiles")
            app.casts.save(book, cast)
            check(VoiceCastPreferences(app).get(book) == cast)
            val state = DialogueState(sceneNames = setOf("sample"), sceneExplicit = true)
            check(VoiceCastPreferences.state(VoiceCastPreferences.encode(state)) == state)
            report.add("PASS scene-only profiles and explicit scene presence survive Android preference serialization/reopening")
        } finally {
            database.close()
            app.getSharedPreferences("voice-cast", 0).edit().remove(book).commit()
        }
    }
}
