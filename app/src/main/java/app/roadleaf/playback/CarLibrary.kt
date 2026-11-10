package app.roadleaf.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import app.roadleaf.data.*

/** Local-only browse tree shared by Android Auto and the standard media browser API. */
@UnstableApi
class CarLibrary(private val dao: LibraryDao) {
    companion object { const val ROOT = "roadleaf-root"; const val LIBRARY = "roadleaf-library" }
    data class Selection(val bookId: String, val chapterId: String? = null)
    fun root() = folder(ROOT, "Roadleaf")
    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(title)
        .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
    private fun bookItem(book: Book, resume: Boolean = false) = MediaItem.Builder().setMediaId("play:${book.id}").setMediaMetadata(MediaMetadata.Builder()
        .setTitle(if (resume) "Resume • ${book.title}" else book.title).setArtist(book.author).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK).build()).build()
    private fun chapterItem(chapter: Chapter) = MediaItem.Builder().setMediaId("chapter:${chapter.id}").setMediaMetadata(MediaMetadata.Builder()
        .setTitle(chapter.title).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).build()).build()
    suspend fun item(id: String): MediaItem? = when {
        id == ROOT -> root()
        id == LIBRARY -> folder(LIBRARY, "Offline library")
        id.startsWith("book:") -> dao.book(id.removePrefix("book:"))?.let { folder(id, it.title) }
        id.startsWith("play:") -> dao.book(id.removePrefix("play:"))?.let { bookItem(it, true) }
        id.startsWith("chapter:") -> dao.chapter(id.removePrefix("chapter:"))?.let(::chapterItem)
        else -> null
    }
    suspend fun children(parent: String, page: Int, pageSize: Int): List<MediaItem> {
        require(page >= 0 && pageSize in 1..100) { "Invalid media page" }
        val offset = (page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return when {
            parent == ROOT -> buildList {
                dao.latestProgress()?.let { dao.book(it.bookId) }?.let { add(bookItem(it, true)) }
                add(folder(LIBRARY, "Offline library"))
            }.drop(offset).take(pageSize)
            parent == LIBRARY -> dao.booksPage(pageSize, offset).map { folder("book:${it.id}", it.title) }
            parent.startsWith("book:") -> {
                val book = dao.book(parent.removePrefix("book:")) ?: return emptyList()
                (listOf(bookItem(book, true)) + dao.chapters(book.id).map(::chapterItem)).drop(offset).take(pageSize)
            }
            else -> emptyList()
        }
    }
    suspend fun search(query: String, page: Int, pageSize: Int): List<MediaItem> {
        require(page >= 0 && pageSize in 1..100)
        return dao.searchBooks("%${query.take(200)}%", pageSize, (page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).map { bookItem(it) }
    }
    suspend fun selection(id: String, query: String? = null): Selection {
        if (id.startsWith("play:")) return Selection(requireNotNull(dao.book(id.removePrefix("play:"))) { "Book not found" }.id)
        if (id.startsWith("chapter:")) return requireNotNull(dao.chapter(id.removePrefix("chapter:"))) { "Chapter not found" }.let { Selection(it.bookId, it.id) }
        if (!query.isNullOrBlank()) return dao.searchBooks("%${query.take(200)}%", 1, 0).firstOrNull()?.let { Selection(it.id) } ?: error("No matching offline book")
        error("Choose a book from the offline library")
    }
}
