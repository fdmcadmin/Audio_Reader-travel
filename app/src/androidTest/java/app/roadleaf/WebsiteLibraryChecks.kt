package app.roadleaf

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.roadleaf.data.LibraryDatabase
import app.roadleaf.data.WebsiteLibrary
import app.roadleaf.playback.WebListeningProgress
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.UUID

/** Isolated on-device upgrade and persistence checks; never opens the user's library file. */
class WebsiteLibraryChecks(private val instrumentation: Instrumentation, private val report: MutableList<String>) {
    suspend fun run() {
        val context = instrumentation.targetContext
        val name = "website-upgrade-${UUID.randomUUID()}"
        var database: LibraryDatabase? = null
        try {
            val schema = JSONObject(instrumentation.context.assets.open("app.roadleaf.data.LibraryDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    fun sql(value: String) = value.replace("\${TABLE_NAME}", entity.getString("tableName"))
                    old.execSQL(sql(entity.getString("createSql")))
                    val indices = entity.optJSONArray("indices")
                    for (i in 0 until (indices?.length() ?: 0)) old.execSQL(sql(indices!!.getJSONObject(i).getString("createSql")))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO Book VALUES ('kept', 'Existing book', 'Author', 'author-file', NULL, 1)")
                old.execSQL("INSERT INTO Chapter VALUES ('chapter', 'kept', 0, 'Chapter one', 'fixture.txt')")
                old.execSQL("INSERT INTO Progress VALUES ('kept', 'chapter', 200, 400, 1)")
                old.execSQL("INSERT INTO QueueEntry VALUES (0, 'chapter')")
                old.execSQL("INSERT INTO Pronunciation VALUES ('Roadleaf', 'Road leaf')")
                old.execSQL("INSERT INTO Bookmark VALUES ('https://author.example/book', 'author-file', 'Author book')")
                old.version = 1
            }
            fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_1_2).build()
            database = open()
            var dao = database.library()
            check(dao.book("kept")?.title == "Existing book" && dao.progress("kept")?.textOffset == 200)
            check(dao.chapters("kept").size == 1 && dao.queue().single().chapterId == "chapter")
            check(dao.pronunciationSnapshot().single().spoken == "Road leaf" && dao.bookmarks().first().size == 1)
            report.add("PASS Room 1→2 migration validates the full old schema and preserves books, chapters, progress, queue, pronunciations and bookmarks")

            val a = "https://freewebnovel.com/novel/original-website-fixture/chapter-20"
            val next = a.replace("chapter-20", "chapter-21")
            val b = "https://royalroad.com/fiction/123/original-fixture/chapter/456/one"
            val store = WebsiteLibrary(dao)
            val first = requireNotNull(store.remember(a, "Original website fixture", "Chapter 20: First"))
            store.remember(next)
            val other = requireNotNull(store.remember(b))
            check(dao.webBooks().first().size == 2)
            check(dao.webBook(first.bookUrl)?.let { it.title == first.title && it.chapterUrl == next && it.chapterTitle == "Chapter 21" } == true)
            database.close(); database = open(); dao = database.library()
            check(dao.webBook(first.bookUrl)?.chapterUrl == next && dao.webBooks().first().size == 2)
            report.add("PASS website quick picks deduplicate chapters, preserve the book title and latest chapter across database close/reopen")
            WebsiteLibrary(dao).forget(first.bookUrl)
            check(dao.webBook(first.bookUrl) == null && dao.webBook(other.bookUrl) != null && dao.book("kept") != null)
            report.add("PASS removing a quick pick preserves other shortcuts and offline books")

            val prefs = context.getSharedPreferences(name, 0)
            val progress = WebListeningProgress(prefs)
            prefs.edit().putString("url", a).putInt("offset", 120).putLong("audioMs", 450).putInt("segmentChars", 360).commit()
            check(progress.read(a)?.audioMs == 450L)
            progress.save(a, 300, 700, 720)
            val otherLive = a.replace("original-website-fixture", "another-original-fixture")
            progress.save(otherLive, 100, 800, 360)
            check(progress.read(a)?.offset == 300 && progress.read(a)?.segmentChars == 720)
            progress.clear(otherLive)
            check(progress.read(otherLive) == null && progress.read(a)?.audioMs == 700L)
            report.add("PASS per-book web checkpoints retain legacy resume, audio time and segment boundaries when switching or restarting a different book")
        } finally {
            database?.close(); context.deleteDatabase(name); context.deleteSharedPreferences(name)
        }
    }
}
