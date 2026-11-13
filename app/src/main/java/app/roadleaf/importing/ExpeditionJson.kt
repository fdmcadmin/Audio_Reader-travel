package app.roadleaf.importing

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.ByteArrayOutputStream

data class ExpeditionChapter(val number: Int, val title: String, val text: String)

/** Reads only validated narrative chapters from a folder the listener selected. */
object ExpeditionJson {
    const val SOURCE_ID = "expedition-json"
    const val SERIES = "The Meridian Hunt"
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private val filename = Regex("^Meridian Hunt - Chapter ([1-9][0-9]*) - .+\\.json$", RegexOption.IGNORE_CASE)

    fun chapterNumber(name: String): Int? = filename.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

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
        val json = JSONObject(source.removePrefix("\uFEFF"))
        require(json.optString("format") == "meridian-narrative-chapter" && json.optInt("version") == 1 &&
            json.optString("series") == SERIES && json.optInt("chapter") == expected &&
            json.optString("sha256").matches(Regex("[0-9a-fA-F]{64}")) && json.optString("validated_at").isNotBlank()) {
            "Chapter $expected is not a validated Meridian Hunt narrative JSON."
        }
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
        return ExpeditionChapter(expected, title, text)
    }

    fun readNew(context: Context, tree: Uri, after: Int?): List<ExpeditionChapter> {
        require(DocumentsContract.isTreeUri(tree)) { "Choose the Expedition folder, not a single JSON file." }
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        val files = mutableMapOf<Int, Pair<String, Uri>>()
        resolver.query(children, columns, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(nameColumn) ?: continue
                val number = chapterNumber(name) ?: continue
                require(!files.containsKey(number)) { "More than one validated JSON is named Chapter $number." }
                files[number] = name to DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn))
            }
        } ?: error("Cannot list the selected Expedition folder.")
        return contiguousNumbers(after, files.keys.toList()).map { number ->
            val (name, uri) = requireNotNull(files[number])
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
            } ?: error("Cannot open Chapter $number JSON.")
            parse(name, bytes.toString(Charsets.UTF_8))
        }
    }
}
