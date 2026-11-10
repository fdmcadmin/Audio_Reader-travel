package app.roadleaf.data

import app.roadleaf.sources.WebBookLinks
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One shortcut per book; page navigation updates its chapter rather than adding duplicates. */
class WebsiteLibrary(private val dao: LibraryDao) {
    private val mutex = Mutex()
    suspend fun remember(url: String, title: String = "", chapterTitle: String = ""): WebBook? = mutex.withLock {
        val link = WebBookLinks.resolve(url) ?: return@withLock null
        val previous = dao.webBook(link.bookUrl)
        val chapter = chapterTitle.trim().take(240).ifBlank {
            if (previous?.chapterUrl == link.chapterUrl) previous.chapterTitle else link.chapterTitle
        }
        WebBook(link.bookUrl, title.trim().take(200).ifBlank { previous?.title ?: link.title }, link.chapterUrl, chapter).also { dao.saveWebBook(it) }
    }
    suspend fun forget(bookUrl: String) = mutex.withLock { dao.removeWebBook(bookUrl) }
}
