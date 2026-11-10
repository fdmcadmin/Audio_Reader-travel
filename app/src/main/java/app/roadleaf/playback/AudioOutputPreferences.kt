package app.roadleaf.playback

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class AudioOutputPreferences(context: Context, name: String = "audio-output") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(OutputSelection(prefs.getString("key", "").orEmpty(), prefs.getString("name", "").orEmpty()))
    val selection = mutable.asStateFlow()
    private val observed = MutableStateFlow(prefs.getString("last-result", "").orEmpty())
    val lastResult = observed.asStateFlow()
    private val music = MutableStateFlow(prefs.getBoolean("music-mode", false))
    val musicMode = music.asStateFlow()
    private val events = MutableStateFlow(runCatching { JSONArray(prefs.getString("events", "[]")).let { list -> (0 until list.length()).map { list.getString(it) }.takeLast(6) } }.getOrDefault(emptyList()))
    val recentEvents = events.asStateFlow()
    fun saveMusicMode(value: Boolean) { prefs.edit { putBoolean("music-mode", value) }; music.value = value }
    fun recordEvent(message: String) {
        val stamp = java.time.LocalTime.now().withNano(0).toString()
        val next = (events.value + "$stamp ${message.take(200)}").takeLast(6)
        prefs.edit { putString("events", JSONArray(next).toString()) }; events.value = next
    }
    fun observe(result: String) {
        val safe = result.take(360)
        if (observed.value != safe) { prefs.edit { putString("last-result", safe) }; observed.value = safe }
    }
    fun save(value: OutputSelection) {
        require(value.key.isBlank() || value.key.matches(Regex("[a-f0-9]{64}")))
        val safe = value.copy(name = value.name.take(120))
        prefs.edit { putString("key", safe.key); putString("name", safe.name) }
        mutable.value = safe
    }
}
