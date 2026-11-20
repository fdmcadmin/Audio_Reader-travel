package app.roadleaf.playback

import java.util.Locale

/** Names and locale metadata for the offline voices that Hayai advertises to Android. */
object HayaiVoices {
    const val ENGINE = "dev.ahmedmohamed.hayaitts"
    private val english = Regex("^(af|am|bf|bm)(?:_[a-z0-9_]+)?$", RegexOption.IGNORE_CASE)

    fun speaker(name: String): String = name.substringAfterLast('#', "")

    fun isEnglishKokoro(name: String): Boolean = name.contains("kokoro", true) && english.matches(speaker(name))

    enum class LibriTtsPack(val modelId: String, val label: String) {
        HIGH("en-us-libritts-high", "Piper LibriTTS High"),
        R_MEDIUM("en-us-libritts-r-medium", "Piper LibriTTS-R Medium")
    }

    data class LibriTtsVoice(val pack: LibriTtsPack, val number: Int?)

    /** Match the model portion only. Preserve the original ID for preview, playback and exports. */
    fun libriTts(name: String, engine: String): LibriTtsVoice? {
        if (engine != ENGINE) return null
        val parts = name.split('#', limit = 3)
        val model = parts.first().lowercase(Locale.ROOT).replace('_', '-').removePrefix("vits-piper-")
        val pack = LibriTtsPack.entries.firstOrNull { it.modelId == model } ?: return null
        val number = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 }
        if (parts.size > 1 && number == null) return null
        return LibriTtsVoice(pack, number)
    }

    fun locale(name: String, advertised: String, engine: String): String = when {
        libriTts(name, engine) != null -> "en-US"
        engine == ENGINE && isEnglishKokoro(name) -> if (speaker(name).startsWith('b', true)) "en-GB" else "en-US"
        else -> advertised
    }

    fun supports(name: String, advertised: Locale, requested: Locale, engine: String): Boolean =
        locale(name, advertised.toLanguageTag(), engine).let { Locale.forLanguageTag(it).language == requested.language }

    fun gender(name: String): VoiceDelegation.Gender = when {
        !isEnglishKokoro(name) -> VoiceDelegation.Gender.UNKNOWN
        speaker(name).startsWith("af", true) || speaker(name).startsWith("bf", true) -> VoiceDelegation.Gender.FEMALE
        else -> VoiceDelegation.Gender.MALE
    }
}
