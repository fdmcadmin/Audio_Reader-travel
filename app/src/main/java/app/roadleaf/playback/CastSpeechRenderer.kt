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
        pronounce: (String) -> String, lookahead: String = "", explicitSpeakers: List<String> = emptyList(),
        thoughtSpeaker: String = "", tailMs: Int = 0): Rendered {
        val plan = SceneCast.plan(text, cast, previous, lookahead, explicitSpeakers, thoughtSpeaker)
        val files = mutableListOf<File>()
        val pauses = mutableListOf<Int>()
        for (utterance in plan.utterances) {
            val replaced = pronounce(utterance.text)
            val spoken = if (settings.localeTag.startsWith("en", true)) NarrationText.english(replaced) else replaced
            // Match Supertonic's bounded English batches before entering its own splitter.
            val limit = if (settings.enginePackage == "com.brahmadeo.supertonic.tts") 300 else 550
            val prepared = if (settings.enginePackage == "com.brahmadeo.supertonic.tts") SpeechUtterances.singleParagraph(spoken) else spoken
            val voice = settings.copy(voiceName = utterance.voice.ifBlank { settings.voiceName },
                pitch = (settings.pitch * utterance.pitchScale).coerceIn(0.7f, 1.3f))
            for (sentence in SpeechUtterances.sentences(prepared, settings.localeTag)) {
                val parts = SpeechUtterances.split(sentence, limit).filter { it.any(Char::isLetterOrDigit) }
                for ((index, part) in parts.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    files.add(speech.render(part, voice, fallbackVoice = settings.voiceName.takeIf { utterance.voice.isNotBlank() }))
                    pauses.add(if (index == parts.lastIndex && SpeechUtterances.endsSentence(sentence)) SENTENCE_BEAT_MS else 0)
                }
            }
        }
        val file = withContext(Dispatchers.IO) {
            if (files.isEmpty()) WaveAudio.pause(cache) else {
                val beats = pauses.mapIndexed { index, pause -> maxOf(pause, if (index == pauses.lastIndex) tailMs else JOIN_BEAT_MS) }
                WaveAudio.join(files, File(cache, "speech"), pausesAfter = beats)
            }
        }
        return Rendered(file, plan.next)
    }

    private companion object {
        const val JOIN_BEAT_MS = 90
        const val SENTENCE_BEAT_MS = 180
    }
}
