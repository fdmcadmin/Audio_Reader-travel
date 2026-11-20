package app.roadleaf.importing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import app.roadleaf.playback.ParagraphResume

data class ExpeditionChapter(val number: Int, val part: Int?, val title: String, val text: String,
    val speakers: List<String> = emptyList(), val paragraphSpeakers: List<List<String>> = emptyList(),
    val speakerOffsets: List<Int> = emptyList(), val paragraphModes: List<String> = emptyList())

/** Reads only validated narrative chapters from a folder the listener selected. */
object ExpeditionJson {
    data class ParagraphAttribution(val names: List<String> = emptyList(), val mode: String = "") {
        private val characterNames get() = names.filterNot { it.equals("Narrator", true) }
        val thoughtSpeaker get() = characterNames.singleOrNull()?.takeIf { mode == "thought" }.orEmpty()
        // Narrator in the cast list describes the prose around the quotes.
        val quoteSpeakers get() = when (mode) {
            "narration", "thought" -> listOf("Narrator")
            else -> dialogueSpeakers.ifEmpty { names }
        }
        val dialogueSpeakers get() = if (mode == "narration" || mode == "thought") emptyList() else characterNames
    }

    const val SOURCE_ID = "expedition-json"
    const val SERIES = "The Meridian Hunt"
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private val filename = Regex("^Meridian Hunt - Chapter ([1-9][0-9]*)(?: Part ([1-9][0-9]*))? - .+\\.json$", RegexOption.IGNORE_CASE)
    private val legacyFirstName = Regex("^Meridian Hunt - Chapter 1 - (.+)\\.txt$", RegexOption.IGNORE_CASE)

    fun chapterNumber(name: String): Int? = filename.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
    fun chapterPart(name: String): Int? = filename.matchEntire(name)?.groupValues?.get(2)?.takeIf(String::isNotEmpty)?.toIntOrNull()
    fun chapterId(bookId: String, key: Pair<Int, Int?>): String = "$bookId:${key.first}" + (key.second?.let { ":part:$it" } ?: "")
    fun keyFromId(bookId: String, id: String): Pair<Int, Int?>? {
        val suffix = id.removePrefix("$bookId:")
        if (suffix == id) return null
        val pieces = suffix.split(":part:")
        if (pieces.size !in 1..2) return null
        val number = pieces[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val part = if (pieces.size == 2) pieces[1].toIntOrNull()?.takeIf { it > 0 } ?: return null else null
        return number to part
    }
    fun partLabel(part: Int): String = listOf("One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")
        .getOrNull(part - 1) ?: part.toString()

    fun relocatePosition(old: String, offset: Int, updated: String): Int {
        val exact = ParagraphResume.relocate(old, offset, updated)
        if (exact.matched) return exact.offset
        val oldParagraph = old.take(offset.coerceIn(0, old.length)).split("\n\n").size - 1
        val starts = Regex("\\n\\n").findAll(updated).map { it.range.last + 1 }.toList()
        return ParagraphResume.start(updated, if (oldParagraph == 0) 0 else starts.getOrElse(oldParagraph - 1) { updated.length })
    }

    /** A published part takes precedence over the old whole-chapter file for the same number. */
    fun orderedKeys(available: Collection<Pair<Int, Int?>>): List<Pair<Int, Int?>> {
        val byNumber = available.groupBy { it.first }
        val numbers = contiguousNumbers(null, byNumber.keys.toList())
        val result = mutableListOf<Pair<Int, Int?>>()
        for (number in numbers) {
            val parts = byNumber.getValue(number).mapNotNull { it.second }.distinct().sorted()
            if (parts.isEmpty()) result += number to null
            else if (parts.first() != 1) {
                if (byNumber.getValue(number).any { it.second == null }) result += number to null else break
            }
            else {
                val contiguous = parts.takeWhileIndexedConsecutive()
                result += contiguous.map { number to it }
                if (contiguous.size != parts.size) break
            }
        }
        return result
    }

    private fun List<Int>.takeWhileIndexedConsecutive(): List<Int> = takeWhileIndexed { index, part -> part == index + 1 }

    private inline fun <T> List<T>.takeWhileIndexed(predicate: (Int, T) -> Boolean): List<T> {
        val result = mutableListOf<T>()
        forEachIndexed { index, value -> if (predicate(index, value)) result += value else return result }
        return result
    }

    /** A missing chapter stops the sequence; a later chapter must never be skipped to. */
    fun contiguousNumbers(after: Int?, available: List<Int>): List<Int> {
        val sorted = available.distinct().sorted()
        var next = (after ?: 0) + 1
        return buildList {
            for (number in sorted) {
                if (number < next) continue
                if (number != next) break
                add(number)
                next++
            }
        }
    }

    fun parse(name: String, source: String): ExpeditionChapter {
        val expected = requireNotNull(chapterNumber(name)) { "Choose a validated Meridian Hunt chapter JSON." }
        val expectedPart = chapterPart(name)
        val json = JSONObject(source.removePrefix("\uFEFF"))
        require(json.optString("format") == "meridian-narrative-chapter" && json.optInt("version") == 1 &&
            json.optString("series") == SERIES && json.optInt("chapter") == expected &&
            json.optString("sha256").matches(Regex("[0-9a-fA-F]{64}")) && json.optString("validated_at").isNotBlank()) {
            "Chapter $expected is not a validated Meridian Hunt narrative JSON."
        }
        if (json.has("part")) require(json.optInt("part") == expectedPart) { "Chapter $expected part metadata does not match its filename." }
        val title = json.optString("title").trim()
        require(title.isNotEmpty() && title.length <= 240) { "Chapter $expected has no usable title." }
        val parts = requireNotNull(json.optJSONArray("paragraphs")) { "Chapter $expected has no narrative paragraphs." }
        require(parts.length() > 0) { "Chapter $expected has no narrative paragraphs." }
        val paragraphs = (0 until parts.length()).map { index ->
            require(parts.opt(index) is String) { "Chapter $expected contains a non-text paragraph." }
            parts.getString(index).trim().also { require(it.isNotEmpty()) { "Chapter $expected contains an empty paragraph." } }
        }
        // Say the chapter's actual title, without announcing its chapter number or JSON metadata.
        val body = paragraphs.joinToString("\n\n")
        val text = if (paragraphs.first().equals(title, ignoreCase = true)) body else "$title\n\n$body"
        require(text.length in 40..BookParser.MAX_BYTES) { "Chapter $expected has no usable narrative text." }
        val cast = json.optJSONArray("speakers")?.let { list ->
            (0 until list.length()).map { index ->
                require(list.opt(index) is String) { "Chapter $expected has an invalid speaker name." }
                list.getString(index).trim().also { require(it.isNotEmpty() && it.length <= 80) }
            }.distinct()
        }.orEmpty()
        val assignments = json.optJSONArray("paragraph_speakers")?.let { list ->
            require(list.length() == paragraphs.size) { "Chapter $expected speaker metadata must match its paragraphs." }
            (0 until list.length()).map { index ->
                when (val entry = list.opt(index)) {
                    is String -> listOf(entry)
                    is JSONArray -> (0 until entry.length()).map { position ->
                        require(entry.opt(position) is String) { "Chapter $expected has an invalid paragraph speaker." }
                        entry.getString(position)
                    }
                    else -> error("Chapter $expected has an invalid paragraph speaker.")
                }.map(String::trim).also { names ->
                    require(names.all { it.isNotEmpty() && it.length <= 80 && (cast.isEmpty() || it in cast) }) {
                        "Chapter $expected has a speaker missing from its cast."
                    }
                }
            }
        }.orEmpty()
        var at = if (text == body) 0 else title.length + 2
        val offsets = paragraphs.map { paragraph -> at.also { at += paragraph.length + 2 } }
        val modes = json.optJSONArray("paragraph_modes")?.takeIf { it.length() == paragraphs.size }?.let { list ->
            (0 until list.length()).map { list.optString(it) }
        }.orEmpty()
        return ExpeditionChapter(expected, expectedPart, title, text, cast, assignments, offsets, modes)
    }

    fun saveSpeakers(path: String, chapter: ExpeditionChapter) {
        val file = File("$path.speakers.json")
        if (chapter.paragraphSpeakers.isEmpty()) { if (file.exists()) file.delete(); return }
        val entries = JSONArray()
        chapter.paragraphSpeakers.forEachIndexed { index, names ->
            entries.put(JSONObject().put("offset", chapter.speakerOffsets[index]).put("names", JSONArray(names))
                .put("mode", chapter.paragraphModes.getOrElse(index) { "" }))
        }
        file.writeText(JSONObject().put("speakers", JSONArray(chapter.speakers)).put("paragraphs", entries).toString())
    }

    fun paragraphAt(path: String, offset: Int): ParagraphAttribution = runCatching {
        val file = File("$path.speakers.json")
        if (!file.isFile) return@runCatching ParagraphAttribution()
        val entries = JSONObject(file.readText()).getJSONArray("paragraphs")
        var chosen: JSONObject? = null
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (entry.getInt("offset") > offset) break
            chosen = entry
        }
        chosen?.let { entry ->
            val names = entry.getJSONArray("names")
            ParagraphAttribution((0 until names.length()).map(names::getString), entry.optString("mode"))
        } ?: ParagraphAttribution()
    }.getOrDefault(ParagraphAttribution())

    /** The first published chapter predates the validated JSON format. Accept only its exact series filename and heading. */
    fun parseLegacyFirst(name: String, source: String): ExpeditionChapter {
        val title = requireNotNull(legacyFirstName.matchEntire(name)) { "Not the Meridian Hunt Chapter 1 file." }.groupValues[1].trim()
        require(title.isNotEmpty() && title.length <= 240)
        val lines = source.removePrefix("\uFEFF").replace("\r\n", "\n").split('\n')
        require(lines.size >= 4 && lines[0].trim().equals("THE MERIDIAN HUNT", ignoreCase = true)) {
            "The legacy Chapter 1 file has an unexpected heading."
        }
        val heading = Regex("^Chapter One\\s*[–—-]\\s*(.+)$", RegexOption.IGNORE_CASE).matchEntire(lines[1].trim())
        require(heading?.groupValues?.get(1)?.trim()?.equals(title, ignoreCase = true) == true) {
            "The legacy Chapter 1 title does not match its file name."
        }
        val body = lines.drop(2).joinToString("\n").trim()
        require(body.length in 40..MAX_JSON_BYTES) { "The legacy Chapter 1 file has no usable narrative text." }
        return ExpeditionChapter(1, null, title, "$title\n\n$body")
    }

    fun readAll(context: Context, tree: Uri): List<ExpeditionChapter> {
        require(DocumentsContract.isTreeUri(tree)) { "Choose the Expedition folder, not a single JSON file." }
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        val files = mutableMapOf<Pair<Int, Int?>, Pair<String, Uri>>()
        var legacyFirst: Pair<String, Uri>? = null
        resolver.query(children, columns, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(nameColumn) ?: continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn))
                if (legacyFirstName.matches(name)) {
                    require(legacyFirst == null) { "More than one legacy Chapter 1 TXT is present." }
                    legacyFirst = name to uri
                    continue
                }
                val number = chapterNumber(name) ?: continue
                val key = number to chapterPart(name)
                require(!files.containsKey(key)) { "More than one validated JSON is named Chapter $number${key.second?.let { " Part $it" }.orEmpty()}." }
                files[key] = name to uri
            }
        } ?: error("Cannot list the selected Expedition folder.")
        val useLegacyFirst = files.keys.none { it.first == 1 } && legacyFirst != null
        val keys = files.keys + if (useLegacyFirst) listOf(1 to null) else emptyList()
        return keys.sortedWith(compareBy<Pair<Int, Int?>> { it.first }.thenBy { it.second ?: 0 }).map { (number, part) ->
            val (name, uri) = requireNotNull(files[number to part] ?: if (number == 1 && useLegacyFirst) legacyFirst else null)
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_JSON_BYTES) { "Chapter $number JSON exceeds 2 MB." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("Cannot open Expedition Chapter $number.")
            val source = bytes.toString(Charsets.UTF_8)
            if (number == 1 && useLegacyFirst) parseLegacyFirst(name, source) else parse(name, source)
        }
    }
}
