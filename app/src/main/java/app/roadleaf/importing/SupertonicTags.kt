package app.roadleaf.importing

import org.json.JSONObject

/** Explicit voice metadata only. Lorebook prose is never interpreted as a voice instruction. */
internal object SupertonicTags {
    const val ENGINE = "com.brahmadeo.supertonic.tts"
    private val voiceId = Regex("(?i)^en-supertonic-[fm][1-9][0-9]*$")

    fun voice(service: String, taggedVoice: String): String {
        if (!service.equals("supertonic", true) && service != ENGINE) return ""
        val voice = taggedVoice.trim()
        return voice.takeIf { it.length <= 160 && voiceId.matches(it) }.orEmpty()
    }

    fun voice(profile: JSONObject?): String = profile?.let { voice(it.optString("service"), it.optString("voice")) }.orEmpty()
}
