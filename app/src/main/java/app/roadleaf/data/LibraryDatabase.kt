package app.roadleaf.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity data class Book(@PrimaryKey val id: String, val title: String, val author: String, val sourceId: String, val sourceUrl: String? = null, val addedAt: Long = System.currentTimeMillis())
@Entity(foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)], indices = [Index("bookId")])
data class Chapter(@PrimaryKey val id: String, val bookId: String, val ordinal: Int, val title: String, val textPath: String)
@Entity data class Progress(@PrimaryKey val bookId: String, val chapterId: String, val textOffset: Int, val audioMs: Long, val updatedAt: Long = System.currentTimeMillis())
@Entity data class QueueEntry(@PrimaryKey val position: Int, val chapterId: String)
@Entity data class Pronunciation(@PrimaryKey val written: String, val spoken: String)
@Entity data class Bookmark(@PrimaryKey val url: String, val sourceId: String, val title: String)
@Entity data class WebBook(@PrimaryKey val bookUrl: String, val title: String, val chapterUrl: String, val chapterTitle: String, val updatedAt: Long = System.currentTimeMillis())

@Dao interface LibraryDao {
    @Query("SELECT * FROM Book ORDER BY addedAt DESC") fun books(): Flow<List<Book>>
    @Query("SELECT * FROM Book WHERE id=:id") suspend fun book(id: String): Book?
    @Query("SELECT * FROM Book WHERE sourceId=:sourceId AND sourceUrl=:sourceUrl LIMIT 1") suspend fun bookBySource(sourceId: String, sourceUrl: String): Book?
    @Query("SELECT * FROM Book ORDER BY addedAt DESC LIMIT :limit OFFSET :offset") suspend fun booksPage(limit: Int, offset: Int): List<Book>
    @Query("SELECT * FROM Book WHERE title LIKE :query OR author LIKE :query ORDER BY addedAt DESC LIMIT :limit OFFSET :offset") suspend fun searchBooks(query: String, limit: Int, offset: Int): List<Book>
    @Query("SELECT * FROM Chapter WHERE bookId=:bookId ORDER BY ordinal") suspend fun chapters(bookId: String): List<Chapter>
    @Query("SELECT * FROM Chapter WHERE id=:id") suspend fun chapter(id: String): Chapter?
    @Insert suspend fun insertBook(book: Book)
    @Insert suspend fun insertChapters(chapters: List<Chapter>)
    @Transaction suspend fun appendChapters(book: Book?, chapters: List<Chapter>) {
        if (book != null) insertBook(book)
        insertChapters(chapters)
    }
    @Update suspend fun updateChapter(chapter: Chapter)
    @Query("DELETE FROM Chapter WHERE bookId=:bookId") suspend fun removeChapters(bookId: String)
    @Transaction suspend fun replaceText(bookId: String, chapters: List<Chapter>, progress: Progress) {
        require(book(bookId) != null && chapters.all { it.bookId == bookId } && progress.bookId == bookId)
        removeQueuedBook(bookId); removeProgress(bookId); removeChapters(bookId)
        insertChapters(chapters); saveBookProgress(progress)
    }
    @Transaction suspend fun importBook(book: Book, chapters: List<Chapter>) { insertBook(book); insertChapters(chapters) }
    @Query("SELECT * FROM Progress WHERE bookId=:id") suspend fun progress(id: String): Progress?
    @Query("SELECT * FROM Progress ORDER BY updatedAt DESC LIMIT 1") suspend fun latestProgress(): Progress?
    @Upsert suspend fun saveProgress(progress: Progress)
    @Transaction suspend fun saveBookProgress(progress: Progress) {
        if (book(progress.bookId) != null && chapter(progress.chapterId)?.bookId == progress.bookId) saveProgress(progress)
    }
    @Query("DELETE FROM QueueEntry WHERE chapterId IN (SELECT id FROM Chapter WHERE bookId=:bookId)") suspend fun removeQueuedBook(bookId: String)
    @Query("DELETE FROM Progress WHERE bookId=:bookId") suspend fun removeProgress(bookId: String)
    @Query("DELETE FROM Book WHERE id=:bookId") suspend fun removeBookRow(bookId: String)
    @Transaction suspend fun deleteBook(bookId: String) {
        removeQueuedBook(bookId); removeProgress(bookId); removeBookRow(bookId)
    }
    @Query("SELECT * FROM QueueEntry ORDER BY position") suspend fun queue(): List<QueueEntry>
    @Query("SELECT Chapter.* FROM Chapter INNER JOIN QueueEntry ON Chapter.id = QueueEntry.chapterId ORDER BY QueueEntry.position") suspend fun queuedChapters(): List<Chapter>
    @Query("DELETE FROM QueueEntry") suspend fun clearQueue()
    @Insert suspend fun insertQueue(entries: List<QueueEntry>)
    @Transaction suspend fun replaceQueue(entries: List<QueueEntry>) { clearQueue(); insertQueue(entries) }
    @Query("SELECT * FROM Pronunciation ORDER BY written") fun pronunciations(): Flow<List<Pronunciation>>
    @Query("SELECT * FROM Pronunciation ORDER BY length(written) DESC") suspend fun pronunciationSnapshot(): List<Pronunciation>
    @Upsert suspend fun savePronunciation(rule: Pronunciation)
    @Query("DELETE FROM Pronunciation WHERE written=:written") suspend fun deletePronunciation(written: String)
    @Query("SELECT * FROM Bookmark ORDER BY title") fun bookmarks(): Flow<List<Bookmark>>
    @Upsert suspend fun bookmark(bookmark: Bookmark)
    @Query("SELECT * FROM WebBook ORDER BY updatedAt DESC") fun webBooks(): Flow<List<WebBook>>
    @Query("SELECT * FROM WebBook WHERE bookUrl=:bookUrl") suspend fun webBook(bookUrl: String): WebBook?
    @Upsert suspend fun saveWebBook(book: WebBook)
    @Query("DELETE FROM WebBook WHERE bookUrl=:bookUrl") suspend fun removeWebBook(bookUrl: String)
}

@Database(entities = [Book::class, Chapter::class, Progress::class, QueueEntry::class, Pronunciation::class, Bookmark::class, WebBook::class], version = 2, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `WebBook` (`bookUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `chapterUrl` TEXT NOT NULL, `chapterTitle` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bookUrl`))")
            }
        }
    }
}
