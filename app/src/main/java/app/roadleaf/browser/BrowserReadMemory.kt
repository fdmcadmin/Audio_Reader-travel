package app.roadleaf.browser

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest

/**
 * Where a browser reading of a story not saved to Library last stopped, so Resume
 * can continue there after more text has been written. Each place keeps only a fingerprint of the text
 * before that point and the offset, never the story text.
 */
object ReadPlaces {
    const val MAX = 24
    private const val HEAD = 256

    data class Place(val head: String, val fingerprint: String, val offset: Int, val updatedAt: Long)

    fun hash(text: String, length: Int): String = MessageDigest.getInstance("SHA-256")
        .digest(text.substring(0, length.coerceIn(0, text.length)).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun matches(place: Place, text: String) = place.offset <= text.length &&
        hash(text, minOf(HEAD, place.offset)) == place.head && hash(text, place.offset) == place.fingerprint

    /** Records [offset] as this story's latest place, replacing its earlier ones. Very early places are not kept. */
    fun update(places: List<Place>, text: String, offset: Int, now: Long): List<Place> {
        val at = offset.coerceIn(0, text.length)
        if (at < 40) return places
        val fresh = Place(hash(text, minOf(HEAD, at)), hash(text, at), at, now)
        return (listOf(fresh) + places.filterNot { matches(it, text) }).take(MAX)
    }

    /** The most recent place whose earlier text is unchanged in [text], or null. */
    fun find(places: List<Place>, text: String): Int? = places.filter { matches(it, text) }.maxByOrNull { it.updatedAt }?.offset

    fun encode(places: List<Place>) = places.joinToString("\n") { "${it.head}|${it.fingerprint}|${it.offset}|${it.updatedAt}" }
    fun decode(value: String?): List<Place> = value.orEmpty().lines().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size != 4) return@mapNotNull null
        val offset = parts[2].toIntOrNull() ?: return@mapNotNull null
        Place(parts[0], parts[1], offset, parts[3].toLongOrNull() ?: 0)
    }.take(MAX)
}

class BrowserReadMemory(context: Context) {
    private val preferences = context.getSharedPreferences("browser-read-memory", Context.MODE_PRIVATE)
    @Synchronized fun remember(text: String, offset: Int) {
        val places = ReadPlaces.update(ReadPlaces.decode(preferences.getString("places", null)), text, offset, System.currentTimeMillis())
        preferences.edit { putString("places", ReadPlaces.encode(places)) }
    }
    fun find(text: String): Int? = ReadPlaces.find(ReadPlaces.decode(preferences.getString("places", null)), text)
}
