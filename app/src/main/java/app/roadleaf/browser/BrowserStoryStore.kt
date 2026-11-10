package app.roadleaf.browser

import android.content.Context
import app.roadleaf.data.*
import app.roadleaf.importing.BookFiles
import app.roadleaf.playback.ParagraphResume
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable source files keep the old reader stable while a new generation is captured. */
class BrowserStoryStore(private val context: Context, private val dao: LibraryDao) {
    private data class Temporary(val book: Book, val chapter: Chapter, val profiles: String?, val progress: Progress? = null, val text: String = "")
    private val temporary = ConcurrentHashMap<String, Temporary>()
    private val temporaryFolder = File(context.cacheDir, "browser-free")
    /** Where unsaved readings stopped, so Resume can continue after more text is written. */
    val memory by lazy { BrowserReadMemory(context) }
    init {
        // Only this feature's own previous-process cache is removed; no library files.
        if (cleaned.compareAndSet(false, true)) temporaryFolder.listFiles()?.filter { it.isFile && it.name.startsWith("read-") && it.extension == "txt" }?.forEach { it.delete() }
    }
    fun isTemporary(id: String) = temporary.containsKey(id)
    fun profileBook(id: String) = temporary[id]?.profiles ?: id
    suspend fun book(id: String) = temporary[id]?.book ?: dao.book(id)
    suspend fun chapters(id: String) = temporary[id]?.let { listOf(it.chapter) } ?: dao.chapters(id)
    suspend fun chapter(id: String) = temporary.values.firstOrNull { it.chapter.id == id }?.chapter ?: dao.chapter(id)
    suspend fun progress(id: String) = temporary[id]?.let { it.progress } ?: if (isTemporary(id)) null else dao.progress(id)
    suspend fun saveProgress(value: Progress) {
        if (isTemporary(value.bookId)) {
            val updated = temporary.computeIfPresent(value.bookId) { _, old -> if (old.chapter.id == value.chapterId) old.copy(progress = value) else old }
            if (updated != null && updated.chapter.id == value.chapterId && updated.text.isNotEmpty())
                withContext(Dispatchers.Default) { memory.remember(updated.text, value.textOffset.takeUnless { it == Int.MAX_VALUE } ?: updated.text.length) }
        }
        else dao.saveBookProgress(value)
    }
    suspend fun matchingBook(text: String): Book? = withContext(Dispatchers.IO) {
        val books = dao.booksPage(256, 0).filter { it.sourceId == BrowserStory.SOURCE }
        val snapshots = books.mapNotNull { book ->
            val path = dao.chapters(book.id).singleOrNull()?.textPath ?: return@mapNotNull null
            val saved = runCatching { File(path).takeIf { it.length() <= BrowserStory.MAX_TEXT * 4L }?.readText() }.getOrNull() ?: return@mapNotNull null
            book.id to saved
        }
        val id = BrowserStoryMatch.unique(text, snapshots)
        books.singleOrNull { it.id == id }
    }
    fun releaseTemporaryExcept(keep: String?) {
        temporary.keys.filter { it != keep }.forEach { id -> temporary.remove(id)?.let { File(it.chapter.textPath).delete() } }
    }
    companion object { private val cleaned = AtomicBoolean(false) }
    data class Capture(val chapter: Chapter, val previousLength: Int, val length: Int, val replaced: Boolean, val matchedParagraph: Boolean, val previousChapter: String? = null)
    suspend fun capture(session: BrowserReadingSession, text: String, manual: Boolean): Capture = withContext(Dispatchers.IO) {
        require(text.length in 40..BrowserStory.MAX_TEXT)
        if (session.temporary) {
            val old = temporary[session.bookId]
            val previous = old?.let { File(it.chapter.textPath).readText() }.orEmpty()
            require(text.startsWith(previous)) { "The story changed. Tap From top to start again." }
            if (old != null && previous == text) return@withContext Capture(old.chapter, previous.length, text.length, false, true)
            temporaryFolder.mkdirs()
            val file = File(temporaryFolder, "read-${UUID.randomUUID()}.txt").apply { writeText(text) }
            val chapter = Chapter(old?.chapter?.id ?: "${session.bookId}:free", session.bookId, 0, "Story", file.path)
            temporary[session.bookId] = Temporary(Book(session.bookId, session.title, "Temporary reading", "browser-free"), chapter, session.profileBookId, old?.progress, text)
            old?.let { File(it.chapter.textPath).delete() }
            return@withContext Capture(chapter, previous.length, text.length, false, true)
        }
        val book = dao.book(session.bookId)
        require(book == null || book.sourceId == BrowserStory.SOURCE) { "Choose a browser story, not an unrelated library book." }
        val old = dao.chapters(session.bookId).singleOrNull()
        val previous = old?.let { File(it.textPath).readText() }.orEmpty()
        val replaced = !text.startsWith(previous)
        require(!replaced || manual && session.allowReplacement) { "Earlier text changed or a different story opened. Select the correct story in Roadleaf and explicitly allow replacement before Read story." }
        val progress = dao.progress(session.bookId)
        val anchor = if (replaced) ParagraphResume.relocate(previous, progress?.textOffset?.takeUnless { it == Int.MAX_VALUE } ?: previous.length, text) else null
        if (text == previous && old != null) return@withContext Capture(old, previous.length, text.length, false, true)
        val folder = BookFiles.directory(File(context.filesDir, "books"), session.bookId).apply { mkdirs() }
        val file = File(folder, "browser-${UUID.randomUUID()}.txt").apply { writeText(text) }
        val chapter = Chapter(if (old != null && !replaced) old.id else "${session.bookId}:${UUID.randomUUID()}", session.bookId, 0, "Story", file.path)
        var committed = false
        try {
            withContext(NonCancellable) {
                when {
                    book == null -> dao.importBook(Book(session.bookId, session.title.take(160), "Personal NovelAI story", BrowserStory.SOURCE, "https://novelai.net/stories"), listOf(chapter))
                    old == null || replaced -> dao.replaceText(session.bookId, listOf(chapter), Progress(session.bookId, chapter.id, anchor?.offset ?: 0, 0))
                    else -> { dao.updateChapter(chapter); if (progress?.textOffset == Int.MAX_VALUE) dao.saveBookProgress(Progress(session.bookId, chapter.id, previous.length, 0)) }
                }
                committed = true
            }
            old?.let { runCatching { File(it.textPath).takeIf { oldFile -> oldFile.canonicalPath.startsWith(folder.canonicalPath + File.separator) }?.delete() } }
            Capture(chapter, previous.length, text.length, replaced, anchor?.matched != false, old?.id)
        } catch (error: Exception) { if (!committed) file.delete(); throw error }
    }
}
