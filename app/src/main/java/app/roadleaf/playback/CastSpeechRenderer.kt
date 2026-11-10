package app.roadleaf.playback

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One speech engine, a bounded original text span and a single playback file per span. */
class CastSpeechRenderer(private val speech: OfflineSpeech, private val cache: File) {
    data class Rendered(val file: File, val next: DialogueState)
    suspend fun render(text: String, cast: BookVoiceCast, previous: DialogueState, settings: ListeningSettings,
        pronounce: (String) -> String, lookahead: String = ""): Rendered {
        val plan = SceneCast.plan(text, cast, previous, lookahead)
        val files = mutableListOf<File>()
        for (utterance in plan.utterances) {
            val replaced = pronounce(utterance.text)
            val spoken = if (settings.localeTag.startsWith("en", true)) NarrationText.english(replaced) else replaced
            // Match Supertonic's bounded English batches before entering its own splitter.
            val limit = if (settings.enginePackage == "com.brahmadeo.supertonic.tts") 300 else 550
            val prepared = if (settings.enginePackage == "com.brahmadeo.supertonic.tts") SpeechUtterances.singleParagraph(spoken) else spoken
            for (part in SpeechUtterances.split(prepared, limit)) {
                currentCoroutineContext().ensureActive()
                if (part.any(Char::isLetterOrDigit)) files.add(speech.render(part, settings.copy(voiceName = utterance.voice.ifBlank { settings.voiceName },
                    pitch = (settings.pitch * utterance.pitchScale).coerceIn(0.7f, 1.3f)),
                    fallbackVoice = if (utterance.voice.isNotBlank()) settings.voiceName else null))
            }
        }
        val file = withContext(Dispatchers.IO) {
            if (files.isEmpty()) WaveAudio.pause(cache) else WaveAudio.join(files, File(cache, "speech"))
        }
        return Rendered(file, plan.next)
    }
}
