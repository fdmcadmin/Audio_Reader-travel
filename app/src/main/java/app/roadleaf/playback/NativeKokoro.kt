package app.roadleaf.playback

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Optional, local Kokoro v1.0 engine. It bypasses Android's one-voice-per-engine
 * list entirely. This is a sibling of Android TTS, not a replacement for it.
 *
 * The int8 v1.0 model supplies at least 53 speakers, with 28 English speakers.
 * The later v1.1-zh "103 speakers" model is DIFFERENT: it has only 3 English
 * voices and incompatible speaker IDs. Never claim that its IDs are v1.0 IDs.
 */
internal class NativeKokoro(private val context: Context) {
    companion object {
        const val ENGINE_ID = "roadleaf.native.kokoro"
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"
        private const val PREFIX = "kokoro:"
        private val ENGLISH = listOf(
            "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica",
            "af_kore", "af_nicole", "af_nova", "af_river", "af_sarah",
            "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir",
            "am_liam", "am_michael", "am_onyx", "am_puck", "am_santa",
            "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
            "bm_daniel", "bm_fable", "bm_george", "bm_lewis"
        )
        fun speakerName(id: String): String? = id.removePrefix(PREFIX).toIntOrNull()
            ?.takeIf { id.startsWith(PREFIX) && it in ENGLISH.indices }?.let(ENGLISH::get)
        fun speakerId(voice: String): Int? = voice.removePrefix(PREFIX).toIntOrNull()
            ?.takeIf { voice.startsWith(PREFIX) && it in ENGLISH.indices }
        fun voiceIdForSpeaker(name: String): String? = ENGLISH.indexOf(name.trim().lowercase(java.util.Locale.ROOT))
            .takeIf { it >= 0 }?.let { PREFIX + it }
        fun voiceIdForTag(service: String, model: String, name: String, sid: Int): String {
            require(service.equals("sherpa-onnx", true)) { "This lorebook's voice service is not supported by Roadleaf." }
            require(model in setOf("kokoro-multi-lang-v1_0", "kokoro-int8-multi-lang-v1_0")) {
                "The lorebook names model $model, but these speaker IDs require Kokoro v1.0. Correct the profile metadata before importing."
            }
            val id = voiceIdForSpeaker(name) ?: error("Unknown English Kokoro speaker: $name")
            require(speakerId(id) == sid) { "Kokoro speaker name and ID disagree for $name." }
            return id
        }
        fun installed(context: Context): Boolean {
            val dir = folder(context)
            return File(dir, "model.int8.onnx").length() > 1_000_000L &&
                File(dir, "voices.bin").length() > 1_000_000L &&
                File(dir, "tokens.txt").length() > 30L &&
                File(dir, "lexicon-us-en.txt").length() > 10_000L &&
                File(dir, "lexicon-gb-en.txt").length() > 10_000L
        }
        fun folder(context: Context) = File(context.filesDir, "kokoro-v1_0")
        fun options(context: Context): List<OfflineVoiceOption> =
            if (!installed(context)) emptyList() else ENGLISH.indices.map {
                OfflineVoiceOption(PREFIX + it, if (it < 20) "en-US" else "en-GB", 500, 100)
            }
    }

    private var tts: OfflineTts? = null
    private var loadedModelStamp = 0L

    private fun model(): OfflineTts {
        check(installed(context)) {
            "Import the Kokoro v1.0 int8 model in Voice settings before using this engine."
        }
        val dir = folder(context)
        val stamp = File(dir, "model.int8.onnx").lastModified()
        if (tts != null && stamp == loadedModelStamp) return requireNotNull(tts)
        close()
        val enUS = File(dir, "lexicon-us-en.txt").absolutePath
        val enGB = File(dir, "lexicon-gb-en.txt").absolutePath
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, "model.int8.onnx").absolutePath,
                    voices = File(dir, "voices.bin").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath,
                    lexicon = enUS + "," + enGB
                ),
                numThreads = 2,
                provider = "cpu"
            ),
            maxNumSentences = 1
        )
        val instance = OfflineTts(config = cfg)
        check(instance.numSpeakers() >= ENGLISH.size) {
            instance.free()
            "This model does not contain Kokoro v1.0's 28 English speakers."
        }
        tts = instance
        loadedModelStamp = stamp
        return instance
    }

    suspend fun render(
        text: String,
        requested: String,
        pitch: Float,
        outputFolder: File
    ): File = withContext(Dispatchers.Default) {
        val sid = if (requested.isBlank()) 0 else speakerId(requested)
            ?: error("Unknown Kokoro voice. Select one of the English voices in Voice settings.")
        require(text.length <= 3_900) { "Speech segment too long for offline Kokoro; split the passage." }
        val key = AudioCacheKey.create(ENGINE_ID, PREFIX + sid, 1f, text)
        outputFolder.mkdirs()
        val audio = File(outputFolder, key + ".wav")
        if (!audio.exists() || audio.length() <= 44) {
            val synthesized = model().generate(text, sid = sid, speed = 1f)
            val samples = synthesized.samples
            val rate = synthesized.sampleRate
            require(rate in 8_000..96_000 && samples.isNotEmpty() && samples.size < Int.MAX_VALUE / 2) {
                "The offline model returned invalid speech audio."
            }
            val partial = File(outputFolder, key + ".part")
            try {
                partial.outputStream().buffered().use { output ->
                    output.write(WaveAudio.header(rate, samples.size * 2))
                    val buf = ByteArray(8192)
                    var cursor = 0
                    while (cursor < samples.size) {
                        val count = minOf(buf.size / 2, samples.size - cursor)
                        for (i in 0 until count) {
                            val sample = (samples[cursor + i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                            buf[2 * i] = (sample and 255).toByte()
                            buf[2 * i + 1] = ((sample ushr 8) and 255).toByte()
                        }
                        output.write(buf, 0, count * 2)
                        cursor += count
                    }
                }
                check(partial.renameTo(audio)) { "Could not save Kokoro audio." }
            } finally { partial.delete() }
        }
        WavePitch.adjust(audio, pitch)
    }

    fun close() {
        tts?.free()
        tts = null
        loadedModelStamp = 0L
    }
}
