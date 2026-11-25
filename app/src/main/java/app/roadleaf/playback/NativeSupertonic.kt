package app.roadleaf.playback

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File

internal object SupertonicVoiceIndex {
    private const val PREFIX = "supertonic3:"
    private val nativeId = Regex("""supertonic3:(\d{1,3}):([FM][1-5][A-Za-z0-9+-]*)""")
    private val stockId = Regex("""en-supertonic-([FM])([1-5])""")
    private val stockNames = "FM".flatMap { group -> (1..5).map { "$group$it" } }

    fun tableSize(count: Int) = 48L + count * (50 * 256 + 8 * 16) * 4L
    fun label(id: String) = nativeId.matchEntire(id)?.groupValues?.get(2)
    fun id(index: Int, name: String) = "$PREFIX$index:$name"

    fun speaker(id: String, names: List<String>): Int? {
        val match = nativeId.matchEntire(id)
        if (match != null) {
            val index = match.groupValues[1].toInt()
            return index.takeIf { names.getOrNull(it) == match.groupValues[2] }
        }
        val stock = stockId.matchEntire(id)?.let { it.groupValues[1] + it.groupValues[2] } ?: return null
        return stockNames.indexOf(stock).takeIf { it >= 0 }
    }

    fun read(folder: File): List<String> {
        val names = File(folder, "voices.txt").readLines()
        require(names.size in setOf(120, 170) && names.take(10) == stockNames &&
            names.toSet().size == names.size && names.all { label(id(0, it)) != null }) {
            "Invalid Supertonic voice index."
        }
        val table = File(folder, "voice.bin")
        require(table.length() == tableSize(names.size)) { "Invalid Supertonic voice table size." }
        DataInputStream(table.inputStream().buffered()).use { input ->
            val expected = listOf(names.size, 50, 256, names.size, 8, 16)
            require(expected.all { java.lang.Long.reverseBytes(input.readLong()) == it.toLong() }) {
                "Invalid Supertonic voice table dimensions."
            }
        }
        return names
    }
}

/** Supertonic voices share one resident native model. All synthesis is local. */
internal class NativeSupertonic(private val context: Context) {
    companion object {
        const val ENGINE_ID = "roadleaf.native.supertonic3"
        const val LABEL = "Supertonic 3 • built into Roadleaf"

        fun options(context: Context): List<OfflineVoiceOption> = if (SupertonicModelInstall.installed(context)) {
            val legacy = SupertonicVoiceIndex.read(SupertonicModelInstall.folder(context)).mapIndexed { index, label ->
                OfflineVoiceOption(SupertonicVoiceIndex.id(index, label), "en-US", 400, 100)
            }
            val presets = SupertonicStep3Pack.read(context)?.presets.orEmpty().map {
                OfflineVoiceOption(it.id, "en-US", 400, 100)
            }
            val weighted = SupertonicWeightedPack.read(context)?.recipes.orEmpty().map {
                OfflineVoiceOption(it.id, "en-US", 400, 100)
            }
            legacy.take(10) + weighted + presets + legacy.drop(10)
        } else emptyList()
    }

    private var tts: OfflineTts? = null
    private var loadedPack: String? = null
    private val voices by lazy { SupertonicVoiceIndex.read(SupertonicModelInstall.folder(context)) }
    private val step3 by lazy { SupertonicStep3Pack.read(context) }

    private fun model(pack: SupertonicWeightedPack?): OfflineTts {
        if (loadedPack == pack?.digest) tts?.let { return it }
        close()
        val folder = SupertonicModelInstall.folder(context)
        fun path(name: String) = File(folder, name).absolutePath
        val legacy = File(folder, "voice.bin")
        val baseline = step3?.mergedTable(context, legacy, voices.size) ?: legacy
        val baselineCount = voices.size + step3?.presets.orEmpty().size
        val table = pack?.appendTable(baseline, baselineCount, context.cacheDir) ?: baseline
        val instance = OfflineTts(config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(supertonic = OfflineTtsSupertonicModelConfig(
                durationPredictor = path("duration_predictor.int8.onnx"),
                textEncoder = path("text_encoder.int8.onnx"),
                vectorEstimator = path("vector_estimator.int8.onnx"),
                vocoder = path("vocoder.int8.onnx"),
                ttsJson = path("tts.json"),
                unicodeIndexer = path("unicode_indexer.bin"),
                voiceStyle = table.absolutePath
            ), numThreads = 2, provider = "cpu"),
            maxNumSentences = 1
        ))
        check(instance.numSpeakers() == baselineCount + pack?.recipes.orEmpty().size) {
            instance.free()
            "Supertonic did not load the complete voice table."
        }
        tts = instance
        loadedPack = pack?.digest
        return instance
    }

    suspend fun render(text: String, requested: String, fallback: String?, pitch: Float, outputFolder: File): File =
        withContext(Dispatchers.Default) {
            check(SupertonicModelInstall.installed(context)) { "Set up Supertonic 3 under Voice → Narrator before listening." }
            val pack = SupertonicWeightedPack.read(context)
            val weightedStart = voices.size + step3?.presets.orEmpty().size
            fun speaker(id: String): Int? {
                val weighted = pack?.recipes?.indexOfFirst { it.id == id } ?: -1
                if (weighted >= 0) return weightedStart + weighted
                val preset = step3?.presets?.indexOfFirst { it.id == id } ?: -1
                return if (preset >= 0) voices.size + preset else SupertonicVoiceIndex.speaker(id, voices)
            }
            val sid = speaker(requested) ?: fallback?.let(::speaker) ?: 0
            val preset = step3?.presets?.getOrNull(sid - voices.size)
            val weighted = pack?.recipes?.getOrNull(sid - weightedStart)
            val identity = when {
                weighted != null -> "${weighted.id}:${requireNotNull(pack).digest}"
                preset != null -> requireNotNull(step3).cacheIdentity(preset)
                else -> SupertonicVoiceIndex.id(sid, voices[sid])
            }
            val speed = preset?.speed ?: 1f
            val key = AudioCacheKey.create(ENGINE_ID, identity, speed, text)
            val audio = File(outputFolder, "$key.wav")
            if (!audio.exists() || audio.length() <= 44) {
                outputFolder.mkdirs()
                val owner = currentCoroutineContext()
                owner.ensureActive()
                // The JNI callback looks up a typed invoke method that R8 may replace with a generic bridge.
                // Supertonic defaults to English and the direct call retains the chosen voice and rate.
                val generated = model(pack).generate(text, sid = sid, speed = speed)
                owner.ensureActive()
                check(generated.samples.isNotEmpty()) { "Supertonic returned no speech for this passage." }
                val partial = File(outputFolder, "$key.part")
                try {
                    check(generated.save(partial.absolutePath) && WaveAudio.durationMs(partial) > 0 && partial.renameTo(audio)) {
                        "Could not save Supertonic audio."
                    }
                } finally { partial.delete() }
            }
            WavePitch.adjust(audio, pitch)
        }

    fun close() { tts?.free(); tts = null; loadedPack = null }
}
