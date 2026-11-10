package app.roadleaf

import android.app.Application
import androidx.room.Room
import app.roadleaf.data.LibraryDatabase
import app.roadleaf.data.WebsiteLibrary
import app.roadleaf.importing.BookImporter
import app.roadleaf.sources.SourceRegistry
import app.roadleaf.playback.ListeningPreferences
import app.roadleaf.playback.VoiceCastPreferences
import app.roadleaf.playback.LearnedCastPreferences
import app.roadleaf.playback.AudioOutputPreferences
import app.roadleaf.playback.OutputState
import app.roadleaf.playback.HeadphoneMusicState
import kotlinx.coroutines.flow.MutableStateFlow
import app.roadleaf.sources.WebNavigation
import app.roadleaf.browser.*

class RoadleafApp : Application() {
    val database by lazy { Room.databaseBuilder(this, LibraryDatabase::class.java, "library.db").addMigrations(LibraryDatabase.MIGRATION_1_2).build() }
    val dao get() = database.library()
    val websites by lazy { WebsiteLibrary(dao) }
    val importer by lazy { BookImporter(this, dao) }
    val sources = SourceRegistry()
    val listening by lazy { ListeningPreferences(this) }
    val casts by lazy { VoiceCastPreferences(this) }
    val learnedCasts by lazy { LearnedCastPreferences(this) }
    val audioOutput by lazy { AudioOutputPreferences(this) }
    val outputState = MutableStateFlow(OutputState())
    val headphoneMusicState = MutableStateFlow(HeadphoneMusicState())
    val playbackStatus = MutableStateFlow("Choose a book to listen offline")
    val webNavigation = MutableStateFlow<WebNavigation?>(null)
    /** The web chapter whose narration just ended without an automatic next page (for browser-tab following). */
    val webChapterEnded = MutableStateFlow<String?>(null)
    /** Counts Play presses after a chapter read from your own browser ended; the browser reader then reads the page on screen. */
    val browserPlayRequest = MutableStateFlow(0L)
    /** The webpage chapter being narrated, for choosing a start word. */
    val readingPage = MutableStateFlow<app.roadleaf.sources.LiveWebPage?>(null)
    val activeNarration = MutableStateFlow("No book loaded")
    val browserSession = MutableStateFlow<BrowserReadingSession?>(null)
    val browserStatus = MutableStateFlow(BrowserReadingStatus())
    val browserStories by lazy { BrowserStoryStore(this, dao) }
    val browserText = BrowserTextHandoff()
    val browserReader by lazy { BrowserReaderPreferences(this) }
}
