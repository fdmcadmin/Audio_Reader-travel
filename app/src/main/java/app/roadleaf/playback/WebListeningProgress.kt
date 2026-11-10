package app.roadleaf.playback

import android.content.SharedPreferences
import androidx.core.content.edit
import app.roadleaf.sources.WebBookLinks

/** Keep each website book's checkpoint as well as the old last-page fields for upgrades. */
class WebListeningProgress(private val preferences: SharedPreferences) {
    data class Checkpoint(val offset: Int, val audioMs: Long, val segmentChars: Int, val chapterStartMs: Long = 0, val speechVersion: Int = 0,
        val audioKey: String = "", val castContext: String = "")
    private fun prefix(url: String) = WebBookLinks.resolve(url)?.bookUrl?.let { "book:$it|" }
    fun read(url: String): Checkpoint? {
        val key = prefix(url) ?: return null
        val source = when {
            preferences.getString(key + "url", null) == url -> key
            preferences.getString("url", null) == url -> ""
            else -> return null
        }
        return Checkpoint(preferences.getInt(source + "offset", 0), preferences.getLong(source + "audioMs", 0).coerceAtLeast(0), preferences.getInt(source + "segmentChars", 0), preferences.getLong(source + "chapterStartMs", 0), preferences.getInt(source + "speechVersion", 0),
            preferences.getString(source + "audioKey", "").orEmpty(), preferences.getString(source + "castContext", "").orEmpty())
    }
    fun save(url: String, offset: Int, audioMs: Long, segmentChars: Int, chapterStartMs: Long = 0, speechVersion: Int = NarrationText.VERSION, audioKey: String = "", castContext: String = "") {
        val key = prefix(url) ?: return
        preferences.edit {
            for (scope in listOf("", key)) {
                putString(scope + "url", url); putInt(scope + "offset", offset)
                putLong(scope + "audioMs", audioMs.coerceAtLeast(0)); putInt(scope + "segmentChars", segmentChars)
                putLong(scope + "chapterStartMs", chapterStartMs); putInt(scope + "speechVersion", speechVersion)
                putString(scope + "audioKey", audioKey); putString(scope + "castContext", castContext)
            }
        }
    }
    fun clear(url: String) {
        val key = prefix(url) ?: return
        preferences.edit {
            for (field in listOf("url", "offset", "audioMs", "segmentChars", "chapterStartMs", "speechVersion", "audioKey", "castContext")) {
                remove(key + field)
                if (WebBookLinks.resolve(preferences.getString("url", "").orEmpty())?.bookUrl == WebBookLinks.resolve(url)?.bookUrl) remove(field)
            }
        }
    }
}
