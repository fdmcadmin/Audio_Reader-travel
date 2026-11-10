package app.roadleaf.playback

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Only names, evidence counts, voice bindings and bounded replay hashes; no chapter prose. */
class LearnedCastPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("learned-cast", Context.MODE_PRIVATE)
    private val revision = MutableStateFlow(0)
    val changes = revision.asStateFlow()
    fun get(book: String): LearnedCast = runCatching {
        val raw = preferences.getString(book, null) ?: return LearnedCast()
        require(raw.length <= 131072)
        val json = JSONObject(raw)
        val array = json.optJSONArray("people") ?: JSONArray()
        val people = (0 until minOf(array.length(), AdaptiveCast.MAX_PEOPLE)).map { index ->
            val c = array.getJSONObject(index)
            val name = c.getString("name"); require(name.isNotBlank() && name.length <= 80)
            fun count(key: String) = c.optInt(key).coerceIn(0, 10000)
            LearnedCharacter(name, count("turns"), count("windows"), count("viewpoint"), count("viewpointWindows"),
                count("male"), count("female"), c.optString("voice").take(240), c.optString("engine").take(240), c.optInt("attitudeScore").coerceIn(-2, 2), count("attitudeWindows"))
        }
        val seen = json.optJSONArray("seen") ?: JSONArray()
        LearnedCast(json.optInt("windows").coerceIn(0, 1000000), json.optString("main").take(80), people,
            (0 until minOf(seen.length(), AdaptiveCast.MAX_SEEN)).map { seen.getString(it).take(32) },
            json.optJSONArray("perspectives")?.let { views -> (0 until minOf(views.length(), ChapterPerspectives.MAX_CHAPTERS)).map { i ->
                val view = views.getJSONObject(i); val evidence = view.getJSONArray("evidence")
                ChapterPerspective(view.getString("chapter").take(2048), (0 until minOf(evidence.length(), 16)).map { n ->
                    evidence.getJSONObject(n).let { ViewpointEvidence(it.getString("name").take(80), it.optInt("hits").coerceIn(0,1000), it.optInt("windows").coerceIn(0,1000)) }
                })
            } }.orEmpty())
    }.getOrDefault(LearnedCast())
    fun save(book: String, value: LearnedCast) {
        require(book.isNotBlank() && value.people.size <= AdaptiveCast.MAX_PEOPLE && value.seen.size <= AdaptiveCast.MAX_SEEN)
        require(value.perspectives.size <= ChapterPerspectives.MAX_CHAPTERS)
        val json = JSONObject().put("windows", value.windows).put("main", value.main)
            .put("perspectives", JSONArray().apply { value.perspectives.forEach { view -> put(JSONObject().put("chapter", view.chapter)
                .put("evidence", JSONArray().apply { view.evidence.take(16).forEach { put(JSONObject().put("name", it.name).put("hits", it.hits).put("windows", it.windows)) } })) } })
            .put("people", JSONArray().apply { value.people.forEach { c -> put(JSONObject().put("name", c.name)
                .put("turns", c.turns).put("windows", c.windows).put("viewpoint", c.viewpoint).put("viewpointWindows", c.viewpointWindows)
                .put("male", c.maleCues).put("female", c.femaleCues).put("voice", c.voice).put("engine", c.engine)
                .put("attitudeScore", c.attitudeScore).put("attitudeWindows", c.attitudeWindows)) } })
            .put("seen", JSONArray(value.seen))
        preferences.edit { putString(book, json.toString()) }; revision.value++
    }
    fun clear(book: String) { preferences.edit { remove(book) }; revision.value++ }
}
