package app.roadleaf.playback

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Native VITS VCTK voices for cast members, alongside the selected narrator engine. */
internal class NativeVctk(private val context: Context) {
    companion object {
        const val PREFIX = "vctk:"
        const val SPEAKERS = 109
        fun speakerId(voice: String): Int? = voice.removePrefix(PREFIX).toIntOrNull()
            ?.takeIf { voice.startsWith(PREFIX) && it in 0 until SPEAKERS }
        fun options(context: Context): List<OfflineVoiceOption> =
            if (!installed(context) && !bundled(context)) emptyList()
            else (0 until SPEAKERS).map { OfflineVoiceOption("$PREFIX$it", "en-US", 400, 100) }
        fun folder(context: Context) = File(context.filesDir, "vctk-v1")
        fun installed(context: Context): Boolean = File(folder(context), "vits-vctk.onnx").length() > 100_000_000L &&
            File(folder(context), "lexicon.txt").length() > 10_000L && File(folder(context), "tokens.txt").length() > 10L
        fun bundled(context: Context): Boolean = context.assets.list("models")?.contains("vits.tar.bz2") == true
    }

    private var tts: OfflineTts? = null
    private fun model(): OfflineTts {
        check(installed(context)) { "Bundled VCTK model is not ready." }
        tts?.let { return it }
        val dir = folder(context)
        val instance = OfflineTts(config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
                model = File(dir, "vits-vctk.onnx").absolutePath,
                lexicon = File(dir, "lexicon.txt").absolutePath,
                tokens = File(dir, "tokens.txt").absolutePath
            ), numThreads = 2, provider = "cpu"),
            maxNumSentences = 1
        ))
        check(instance.numSpeakers() == SPEAKERS) {
            instance.free(); "VCTK model did not expose 109 speakers."
        }
        tts = instance
        return instance
    }

    suspend fun render(text: String, requested: String, pitch: Float, outputFolder: File): File = withContext(Dispatchers.Default) {
        if (!installed(context)) VctkModelImport.installBundled(context)
        val sid = speakerId(requested) ?: error("Unknown VCTK voice.")
        require(text.length <= 3_900) { "Speech segment too long for VCTK; split the passage." }
        outputFolder.mkdirs()
        val audio = File(outputFolder, AudioCacheKey.create("roadleaf.native.vctk", requested, 1f, text) + ".wav")
        if (!audio.exists() || audio.length() <= 44) {
            val generated = model().generate(text, sid = sid, speed = 1f)
            val samples = generated.samples
            val rate = generated.sampleRate
            require(rate in 8_000..96_000 && samples.isNotEmpty()) { "VCTK returned invalid audio." }
            val partial = File(outputFolder, audio.name + ".part")
            try {
                partial.outputStream().buffered().use { output ->
                    output.write(WaveAudio.header(rate, samples.size * 2))
                    val bytes = ByteArray(8192)
                    var at = 0
                    while (at < samples.size) {
                        val n = minOf(bytes.size / 2, samples.size - at)
                        repeat(n) { i ->
                            val pcm = (samples[at + i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                            bytes[2 * i] = pcm.toByte()
                            bytes[2 * i + 1] = (pcm ushr 8).toByte()
                        }
                        output.write(bytes, 0, n * 2)
                        at += n
                    }
                }
                check(partial.renameTo(audio)) { "Could not save VCTK audio." }
            } finally { partial.delete() }
        }
        WavePitch.adjust(audio, pitch)
    }

    fun close() { tts?.free(); tts = null }
}
