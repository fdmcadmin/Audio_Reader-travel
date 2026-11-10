package app.roadleaf.browser

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow

/** Metadata only. Remembering a choice never arms a capture or starts audio. */
class BrowserReaderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("browser-reader", Context.MODE_PRIVATE)
    var useProfiles: Boolean
        get() = prefs.getBoolean("useProfiles", true)
        set(value) { prefs.edit { putBoolean("useProfiles", value) } }
    var follow: Boolean
        get() = prefs.getBoolean("follow", true)
        set(value) { prefs.edit { putBoolean("follow", value) } }
    /** Show the small Read page control on other sites in your browsers (the reader never reads until tapped). */
    var anyPage: Boolean
        get() = prefs.getBoolean("anyPage", true)
        set(value) { prefs.edit { putBoolean("anyPage", value) } }
    /** After a tab chapter ends, read the next chapter once you open it in the same browser. */
    var followPages: Boolean
        get() = prefs.getBoolean("followPages", true)
        set(value) { prefs.edit { putBoolean("followPages", value) } }
    /** Where web books open: [WebBookOpening.READER], a browser's package, or null to ask the first time. */
    var webBooksIn: String?
        get() = prefs.getString("webBooksIn", null)
        set(value) { prefs.edit { if (value == null) remove("webBooksIn") else putString("webBooksIn", value) } }
    /** A chapter Roadleaf just opened in your browser, read once it shows there: from the top when [fresh], else from its saved place. */
    data class AutoRead(val url: String, val fresh: Boolean, val untilMs: Long)
    val autoRead = MutableStateFlow<AutoRead?>(null)
    fun readWhenShown(url: String, fresh: Boolean) { autoRead.value = AutoRead(url, fresh, SystemClock.elapsedRealtime() + 60_000) }
    /** NovelAI shows in Library and Sources only once it has been added as a website, or a NovelAI story was read with the bubble. */
    val novelAi = MutableStateFlow(prefs.getBoolean("novelAi", false))
    fun showNovelAi(shown: Boolean) {
        if (novelAi.value != shown) prefs.edit { putBoolean("novelAi", shown) }
        novelAi.value = shown
    }
    /** Closed with ✕, or by dragging it onto ✕: the bubble stays hidden until Roadleaf is opened again. */
    var bubbleClosed: Boolean
        get() = prefs.getBoolean("bubbleClosed", false)
        set(value) { if (value != bubbleClosed) prefs.edit { putBoolean("bubbleClosed", value) } }
    /** Where the bubble was left: its screen side and height. */
    var bubbleSpot: BubbleGeometry.Spot
        get() = BubbleGeometry.Spot(
            if (prefs.getString("bubbleSide", null) == BubbleGeometry.Side.LEFT.name) BubbleGeometry.Side.LEFT else BubbleGeometry.Side.RIGHT,
            prefs.getFloat("bubbleHeight", BubbleGeometry.DEFAULT_HEIGHT))
        set(value) { prefs.edit { putString("bubbleSide", value.side.name); putFloat("bubbleHeight", value.height) } }
    val lastBook get() = prefs.getString("book", "").orEmpty()
    val lastTitle get() = prefs.getString("title", "").orEmpty()
    fun remember(session: BrowserReadingSession) {
        if (!session.temporary) prefs.edit { putString("book", session.bookId); putString("title", session.title.take(160)); putBoolean("follow", session.follow) }
    }
}
