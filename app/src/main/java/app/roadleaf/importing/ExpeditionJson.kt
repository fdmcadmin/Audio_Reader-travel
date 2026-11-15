package app.roadleaf.importing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import app.roadleaf.playback.ParagraphResume

data class ExpeditionChapter(val number: Int, val part: Int?, val title: String, val text: String)

/** Reads only validated narrative chapters from a folder the listener selected. */
object ExpeditionJson {
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
        return ExpeditionChapter(expected, expectedPart, title, text)
    }

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
