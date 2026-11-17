package app.roadleaf.playback

import java.util.Locale

/** Hayai advertises multilingual Kokoro speakers under the bundle's first language. */
object HayaiVoices {
    const val ENGINE = "dev.ahmedmohamed.hayaitts"
    private val english = Regex("^(af|am|bf|bm)(?:_[a-z0-9_]+)?$", RegexOption.IGNORE_CASE)

    fun speaker(name: String): String = name.substringAfterLast('#', "")

    fun isEnglishKokoro(name: String): Boolean = name.contains("kokoro", true) && english.matches(speaker(name))

    fun locale(name: String, advertised: String, engine: String): String =
        if (engine == ENGINE && isEnglishKokoro(name)) {
            if (speaker(name).startsWith('b', true)) "en-GB" else "en-US"
        } else advertised

    fun supports(name: String, advertised: Locale, requested: Locale, engine: String): Boolean =
        locale(name, advertised.toLanguageTag(), engine).let { Locale.forLanguageTag(it).language == requested.language }

    fun gender(name: String): VoiceDelegation.Gender = when {
        !isEnglishKokoro(name) -> VoiceDelegation.Gender.UNKNOWN
        speaker(name).startsWith("af", true) || speaker(name).startsWith("bf", true) -> VoiceDelegation.Gender.FEMALE
        else -> VoiceDelegation.Gender.MALE
    }
}
