package app.roadleaf.playback

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

enum class ResourceMode(val label: String, val lookahead: Int, val checkpointMs: Long, val cacheBytes: Long, val trimIntervalMs: Long) {
    ECONOMY("Battery saver", 2, 15_000, 64L * 1024 * 1024, 300_000),
    BALANCED("Extra buffering", 4, 10_000, 128L * 1024 * 1024, 120_000)
}
data class ListeningSettings(
    val enginePackage: String = "",
    val voiceName: String = "",
    val localeTag: String = "en-US",
    val pitch: Float = 1f,
    val speed: Float = 1f,
    val resourceMode: ResourceMode = ResourceMode.ECONOMY
) {
    fun normalized() = copy(pitch = if (pitch.isFinite()) pitch.coerceIn(0.7f, 1.3f) else 1f, speed = if (speed.isFinite()) speed.coerceIn(0.65f, 2f) else 1f)
}
class ListeningPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("listening", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(ListeningSettings(
        preferences.getString("engine", "").orEmpty(), preferences.getString("voice", "").orEmpty(), preferences.getString("locale", "en-US").orEmpty(),
        preferences.getFloat("pitch", 1f), preferences.getFloat("speed", 1f),
        ResourceMode.entries.firstOrNull { it.name == preferences.getString("resources", "") } ?: ResourceMode.ECONOMY
    ).normalized())
    val settings = mutable.asStateFlow()
    fun save(value: ListeningSettings) {
        val safe = value.normalized()
        preferences.edit {
            putString("engine", safe.enginePackage); putString("voice", safe.voiceName); putString("locale", safe.localeTag)
            putFloat("pitch", safe.pitch); putFloat("speed", safe.speed); putString("resources", safe.resourceMode.name)
        }
        mutable.value = safe
    }
}
data class SpeechEngineOption(val packageName: String, val label: String)
data class OfflineVoiceOption(val name: String, val localeTag: String, val quality: Int, val latency: Int)
data class SpeechCatalog(val engines: List<SpeechEngineOption>, val voices: List<OfflineVoiceOption>, val enginePackage: String)

object AudioCacheKey {
    fun create(engine: String, voice: String, pitch: Float, text: String): String =
        MessageDigest.getInstance("SHA-256").digest("3\u0000$engine\u0000$voice\u0000$pitch\u0000$text".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
