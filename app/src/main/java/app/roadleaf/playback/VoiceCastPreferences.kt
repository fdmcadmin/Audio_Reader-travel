package app.roadleaf.playback

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class VoiceCastPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("voice-cast", Context.MODE_PRIVATE)
    private val revision = MutableStateFlow(0)
    val changes = revision.asStateFlow()
    fun get(book: String): BookVoiceCast = runCatching {
        val json = preferences.getString(book, null)?.let(::JSONObject)
            ?: return BookVoiceCast()
        val characters = json.optJSONArray("characters") ?: JSONArray()
        BookVoiceCast(json.optBoolean("enabled"), json.optString("main"), json.optString("mainVoice"), json.optString("maleVoice"), json.optString("femaleVoice"),
            (0 until characters.length()).map { i -> characters.getJSONObject(i).let {
                CastCharacter(it.getString("name"), it.optString("aliases"), CastRole.entries.firstOrNull { role -> role.name == it.optString("role") } ?: CastRole.CUSTOM, it.optString("voice"),
                    CharacterDelivery.entries.firstOrNull { mode -> mode.name == it.optString("delivery") } ?: CharacterDelivery.FOLLOW_STORY, it.optBoolean("startsLower"),
                    pronoun = CharacterPronoun.entries.firstOrNull { mode -> mode.name == it.optString("pronoun") } ?: CharacterPronoun.AUTO, profileId = it.optString("profileId"))
            } }, adaptive = json.optBoolean("adaptive", json.optBoolean("enabled")), followViewpoint = json.optBoolean("followViewpoint", true),
            chapterViewpoints = json.optJSONObject("chapterViewpoints")?.let { chapters -> chapters.keys().asSequence().take(64).associateWith { chapters.getString(it) } }.orEmpty(),
            mainPronoun = CharacterPronoun.entries.firstOrNull { it.name == json.optString("mainPronoun") } ?: CharacterPronoun.AUTO,
            narratorVoice = json.optString("narratorVoice"), sceneOnly = json.optBoolean("sceneOnly"), profileSetId = json.optString("profileSetId")).validated()
    }.getOrDefault(BookVoiceCast())
    fun save(book: String, value: BookVoiceCast) {
        require(book.isNotBlank()) { "Choose a book first." }
        val cast = value.validated()
        val json = JSONObject().put("enabled", cast.enabled).put("adaptive", cast.adaptive).put("main", cast.mainCharacter).put("mainVoice", cast.mainVoice)
            .put("followViewpoint", cast.followViewpoint).put("chapterViewpoints", JSONObject(cast.chapterViewpoints)).put("mainPronoun", cast.mainPronoun.name).put("narratorVoice", cast.narratorVoice).put("sceneOnly", cast.sceneOnly).put("profileSetId", cast.profileSetId)
            .put("maleVoice", cast.maleVoice).put("femaleVoice", cast.femaleVoice).put("characters", JSONArray().apply { cast.characters.forEach {
                put(JSONObject().put("name", it.name).put("aliases", it.aliases).put("role", it.role.name).put("voice", it.voice)
                    .put("delivery", it.delivery.name).put("startsLower", it.startsLower).put("pronoun", it.pronoun.name).put("profileId", it.profileId))
            } })
        preferences.edit { putString(book, json.toString()) }; revision.value++
    }
    companion object {
        fun state(value: String?): DialogueState = runCatching {
            val json = JSONObject(value.orEmpty()); DialogueState(json.optString("quote").firstOrNull(), json.optString("actor", "narrator"), json.optString("lead").takeLast(180),
                json.optDouble("pitchScale", 1.0).toFloat().takeIf { it.isFinite() && it in 0.7f..1.3f } ?: 1f,
                json.optString("narrativeLead").takeLast(720), json.optString("turnTail").takeLast(240),
                json.optJSONArray("sceneNames")?.let { list -> (0 until minOf(65, list.length())).map { list.getString(it).take(80) }.toSet() }.orEmpty(), json.optBoolean("sceneExplicit"))
        }.getOrDefault(DialogueState())
        fun encode(state: DialogueState) = JSONObject().put("quote", state.closingQuote?.toString().orEmpty()).put("actor", state.actor).put("lead", state.lead.takeLast(180)).put("pitchScale", state.pitchScale.toDouble())
            .put("narrativeLead", state.narrativeLead.takeLast(720)).put("turnTail", state.turnTail.takeLast(240)).put("sceneNames", JSONArray(state.sceneNames.take(65))).put("sceneExplicit", state.sceneExplicit).toString()
    }
}
