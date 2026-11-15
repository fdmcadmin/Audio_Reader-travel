package app.roadleaf.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.roadleaf.data.*
import app.roadleaf.sources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import app.roadleaf.playback.ParagraphResume
import android.content.Intent
import android.provider.DocumentsContract
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BookImporter(private val context: Context, private val dao: LibraryDao) {
    data class ExpeditionRefresh(val book: Book?, val added: Int, val revised: Int = 0)
    private val expeditionMutex = Mutex()
    private val expeditionPrefs by lazy { context.getSharedPreferences("expedition-folder", Context.MODE_PRIVATE) }

    fun expeditionFolder(): Uri? = expeditionPrefs.getString("tree", null)?.let(Uri::parse)

    suspend fun linkExpeditionFolder(uri: Uri): ExpeditionRefresh {
        require(DocumentsContract.isTreeUri(uri)) { "Choose the folder containing Meridian Hunt chapter JSON files." }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        expeditionPrefs.edit().putString("tree", uri.toString()).apply()
        return refreshExpedition()
    }

    suspend fun refreshExpedition(): ExpeditionRefresh = expeditionMutex.withLock { withContext(Dispatchers.IO) {
        val tree = expeditionFolder() ?: return@withContext ExpeditionRefresh(null, 0)
        val existing = dao.bookBySource(ExpeditionJson.SOURCE_ID, tree.toString())
        val fresh = ExpeditionJson.readAll(context, tree)
        if (fresh.isEmpty()) return@withContext ExpeditionRefresh(existing, 0)
        val book = existing ?: Book(UUID.randomUUID().toString(), ExpeditionJson.SERIES,
            "Expedition library", ExpeditionJson.SOURCE_ID, tree.toString())
        val previous = if (existing == null) emptyList() else dao.chapters(book.id)
        val byKey = previous.associateBy { ExpeditionJson.keyFromId(book.id, it.id) }
        val source = fresh.associateBy { it.number to it.part }
        val keys = ExpeditionJson.orderedKeys(byKey.keys.filterNotNull() + source.keys)
        if (keys.isEmpty()) return@withContext ExpeditionRefresh(existing, 0)
        val folder = BookFiles.directory(File(context.filesDir, "books"), book.id)
        check(folder.isDirectory || folder.mkdirs()) { "Could not create the offline Expedition library." }
        val created = mutableListOf<File>()
        try {
            var added = 0
            var revised = 0
            val chapters = keys.mapIndexed { ordinal, key ->
                val item = source[key]
                val prior = byKey[key]
                if (item == null) requireNotNull(prior).copy(ordinal = ordinal)
                else {
                    val title = item.part?.let { "Chapter ${item.number}, Part ${ExpeditionJson.partLabel(it)}: ${item.title}" } ?: item.title
                    val oldText = prior?.let { File(it.textPath).readText() }
                    val changed = oldText != item.text
                    if (prior == null) added++ else if (changed) revised++
                    val path = if (!changed && prior != null) prior.textPath else
                        File(folder, "expedition-${item.number}-${item.part ?: 0}-${UUID.randomUUID()}.txt")
                            .apply { writeText(item.text); created += this }.absolutePath
                    Chapter(prior?.id ?: ExpeditionJson.chapterId(book.id, key), book.id, ordinal, title, path)
                }
            }
            val selectedIds = chapters.map { it.id }.toSet()
            val removed = previous.filter { it.id !in selectedIds }
            val saved = existing?.let { dao.progress(it.id) }
            val updatedProgress = saved?.let { progress ->
                val old = previous.firstOrNull { it.id == progress.chapterId }
                val replacement = chapters.firstOrNull { it.id == progress.chapterId }
                    ?: old?.let { prior -> ExpeditionJson.keyFromId(book.id, prior.id)?.first }
                        ?.let { number -> chapters.firstOrNull { chapter -> ExpeditionJson.keyFromId(book.id, chapter.id)?.first == number } }
                if (replacement == null || old == null) null else {
                    val oldText = File(old.textPath).readText()
                    val newText = File(replacement.textPath).readText()
                    if (old.id == replacement.id && oldText == newText) null
                    else progress.copy(chapterId = replacement.id,
                        textOffset = if (progress.textOffset == Int.MAX_VALUE) Int.MAX_VALUE else
                            ExpeditionJson.relocatePosition(oldText, progress.textOffset, newText), audioMs = 0)
                }
            }
            if (added > 0 || revised > 0 || removed.isNotEmpty() || previous.zip(chapters).any { (a, b) -> a != b })
                dao.syncExpedition(if (existing == null) book else null, chapters, removed, updatedProgress)
            ExpeditionRefresh(book, added, revised)
        } catch (error: Exception) { created.forEach(File::delete); throw error }
    } }
    data class Updated(val book: Book, val matchedParagraph: Boolean, val chapterIds: Map<String, String>)
    suspend fun updateText(bookId: String, uri: Uri): Updated = withContext(Dispatchers.IO) {
        val book = requireNotNull(dao.book(bookId)) { "Choose an imported story first." }
        require(book.sourceId == "author-file") { "Update text is for your imported story files." }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
        require(name.endsWith(".txt", true)) { "Export the story As Plaintext in NovelAI, then choose its TXT file." }
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val result = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(result.size() + count <= BookParser.MAX_BYTES) { "Import limit is 32 MB." }; result.write(buffer, 0, count) }
            result.toString("UTF-8")
        } ?: error("Cannot open updated story.")
        val parsed = BookParser.txt(text, name)
        val old = dao.chapters(bookId); val saved = dao.progress(bookId)
        val previous = old.firstOrNull { it.id == saved?.chapterId } ?: old.firstOrNull()
        val ordinal = previous?.ordinal?.coerceIn(parsed.chapters.indices) ?: 0
        val priorText = previous?.let { File(it.textPath).readText() }.orEmpty()
        val offset = saved?.textOffset?.takeUnless { it == Int.MAX_VALUE } ?: if (saved == null) 0 else priorText.length
        val candidates = if (saved == null) emptyList() else parsed.chapters.mapIndexedNotNull { index, chapter ->
            ParagraphResume.relocate(priorText, offset, chapter.text).takeIf { it.matched }?.let { index to it.offset }
        }
        val same = candidates.firstOrNull { it.first == ordinal &&
            (old.size == 1 && parsed.chapters.size == 1 || parsed.chapters[ordinal].title == previous?.title) &&
            parsed.chapters[ordinal].text.startsWith(priorText.take(offset.coerceAtMost(priorText.length))) }
        val selected = same ?: candidates.singleOrNull()
        val target = selected?.first ?: ordinal
        val folder = BookFiles.directory(File(context.filesDir, "books"), bookId)
        val generation = UUID.randomUUID().toString()
        val staged = File(folder, "update-$generation").apply { mkdirs() }
        var committed = false
        try {
            val chapters = parsed.chapters.mapIndexed { index, chapter ->
                val file = File(staged, "$index.txt").apply { writeText(chapter.text) }
                Chapter("$bookId:$generation:$index", bookId, index, chapter.title, file.absolutePath)
            }
            withContext(kotlinx.coroutines.NonCancellable) {
                dao.replaceText(bookId, chapters, Progress(bookId, chapters[target].id, selected?.second ?: 0, 0))
                committed = true
            }
            old.forEach { chapter -> runCatching { File(chapter.textPath).takeIf { it.canonicalPath.startsWith(folder.canonicalPath + File.separator) }?.delete() } }
            val chapterIds = old.mapNotNull { before ->
                val match = if (old.size == 1 && chapters.size == 1) chapters.single() else chapters.singleOrNull { it.title == before.title }
                    ?.takeIf { old.count { other -> other.title == before.title } == 1 }
                match?.let { before.id to it.id }
            }.toMap()
            Updated(book, selected != null || saved == null, chapterIds)
        } catch (error: Exception) { if (!committed) staged.deleteRecursively(); throw error }
    }
    suspend fun importUri(uri: Uri): Book = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Imported book.txt"
        val file = File.createTempFile("import-", ".book", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0
                while (true) { val size = input.read(buffer); if (size < 0) break; total += size; require(total <= BookParser.MAX_BYTES) { "Import limit is 32 MB." }; output.write(buffer, 0, size) }
            } } ?: error("Cannot read selected file.")
            importFile(file, name, "author-file", null)
        } finally { file.delete() }
    }
    suspend fun download(action: SourceAction.Download): Book = withContext(Dispatchers.IO) {
        val bytes = SafeHttp.get(action.url, BookParser.MAX_BYTES) { SourcePolicy.permittedDownload(it, action.sourceId) }
        val file = File.createTempFile("download-", ".epub", context.cacheDir)
        try { file.writeBytes(bytes); importFile(file, "Downloaded book.epub", action.sourceId, action.url) } finally { file.delete() }
    }
    private suspend fun importFile(file: File, name: String, sourceId: String, url: String?): Book {
        val isZip = file.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }
        require(isZip || name.endsWith(".txt", true)) { "Choose an EPUB or UTF-8 TXT file." }
        val parsed = if (isZip) BookParser.epub(file) else BookParser.txt(file.readText(Charsets.UTF_8), name)
        return store(parsed, sourceId, url)
    }
    suspend fun demo(): Book = withContext(Dispatchers.IO) {
        store(ParsedBook("Welcome to Roadleaf", "Roadleaf", listOf(ParsedChapter("Your offline listening library", "Welcome to Roadleaf. This short original sample tests offline narration. Import an E pub or text file, choose a book, and press listen. Your place is saved as you go. Use the large pause button or your headset controls."), ParsedChapter("Ready for the road", "Before a long drive, install an offline English voice in Android text to speech settings. Test playback in airplane mode with your screen locked. Your chapter queue advances automatically. Pronunciation rules let you teach the narrator unusual names."))), "author-file", null)
    }
    private suspend fun store(parsed: ParsedBook, sourceId: String, url: String?): Book {
        val id = UUID.randomUUID().toString()
        val folder = File(context.filesDir, "books/$id").apply { mkdirs() }
        try {
            val chapters = parsed.chapters.mapIndexed { index, chapter ->
                val file = File(folder, "$index.txt").apply { writeText(chapter.text) }
                Chapter("$id:$index", id, index, chapter.title, file.absolutePath)
            }
            val book = Book(id, parsed.title, parsed.author, sourceId, url)
            dao.importBook(book, chapters)
            return book
        } catch (e: Exception) { folder.deleteRecursively(); throw e }
    }
}
