package app.roadleaf

import app.roadleaf.data.QueueEntry
import app.roadleaf.playback.ListeningPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Device-private recovery data for checks that run on a listener's own phone: reader, progress and
 * listening preferences, every book's saved and learned cast, and the chapter queue. Nothing from it
 * is written to a report.
 */
class PhoneListeningState(private val app: RoadleafApp) {
    private val file = File(app.filesDir, "phone-listening-test-backup.json")
    private val names = listOf("web-reader", "web-listening", "playback-target", "listening")
    private val casts = listOf("voice-cast", "learned-cast")
    var snapshot: JSONObject? = null
        private set
    fun hasBackup() = file.exists()
    private fun encode(values: Map<String, *>): JSONObject = JSONObject().apply { values.forEach { (key, value) ->
        val type = when (value) { is String -> "string"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is Boolean -> "boolean"; is Set<*> -> "set"; else -> error("Unsupported preference type") }
        put(key, JSONObject().put("type", type).put("value", if (value is Set<*>) JSONArray(value.toList()) else value))
    } }
    private fun restorePreferences(name: String, values: JSONObject) {
        val edit = app.getSharedPreferences(name, 0).edit().clear()
        values.keys().forEach { key -> val entry = values.getJSONObject(key)
            when (entry.getString("type")) {
                "string" -> edit.putString(key, entry.getString("value"))
                "int" -> edit.putInt(key, entry.getInt("value"))
                "long" -> edit.putLong(key, entry.getLong("value"))
                "float" -> edit.putFloat(key, entry.getDouble("value").toFloat())
                "boolean" -> edit.putBoolean(key, entry.getBoolean("value"))
                "set" -> edit.putStringSet(key, entry.getJSONArray("value").let { list -> (0 until list.length()).map { list.getString(it) }.toSet() })
            }
        }
        check(edit.commit())
        check(encode(app.getSharedPreferences(name, 0).all).toString().let { current ->
            val json = JSONObject(current); json.length() == values.length() && values.keys().asSequence().all { json.getJSONObject(it).toString() == values.getJSONObject(it).toString() }
        }) { "Phone preferences did not restore" }
    }
    suspend fun capture() = withContext(Dispatchers.IO) {
        check(!file.exists()) { "An earlier phone test needs recovery first" }
        val root = JSONObject().put("preferences", JSONObject().apply { names.forEach { put(it, encode(app.getSharedPreferences(it, 0).all)) } })
            .put("casts", JSONObject().apply { casts.forEach { put(it, encode(app.getSharedPreferences(it, 0).all)) } })
            .put("queue", JSONArray().apply { app.dao.queue().forEach { put(JSONObject().put("position", it.position).put("chapterId", it.chapterId)) } })
        file.writeText(root.toString()); snapshot = root
    }
    suspend fun restore(rollbackCast: Boolean) = withContext(Dispatchers.IO) {
        val root = snapshot ?: JSONObject(file.readText())
        names.forEach { restorePreferences(it, root.getJSONObject("preferences").getJSONObject(it)) }
        if (rollbackCast) casts.forEach { restorePreferences(it, root.getJSONObject("casts").getJSONObject(it)) }
        app.listening.save(ListeningPreferences(app).settings.value)
        for (pref in listOf("listening") + casts) check(app.getSharedPreferences(pref, 0).edit().commit())
        val queue = root.getJSONArray("queue").let { list -> (0 until list.length()).map { list.getJSONObject(it).let { QueueEntry(it.getInt("position"), it.getString("chapterId")) } } }
        app.dao.replaceQueue(queue)
        check(app.dao.queue() == queue)
        check(file.delete()) { "Restored phone backup could not be cleared" }
    }
}
