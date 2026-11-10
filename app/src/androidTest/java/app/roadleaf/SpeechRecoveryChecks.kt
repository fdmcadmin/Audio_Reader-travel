package app.roadleaf

import android.app.Instrumentation
import app.roadleaf.playback.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class SpeechRecoveryChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp, private val report: MutableList<String>) {
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }; return result.get().getOrThrow()
    }
    suspend fun run() {
        val settings = app.listening.settings.value
        for (timeout in listOf(false, true)) {
            var attempts = 0
            val speech = main { OfflineSpeech(app) { completed, limit ->
                attempts++
                if (attempts == 1) {
                    if (timeout) withTimeout(10) { delay(100) } else throw SpeechSynthesisStopped()
                } else withTimeout(limit) { completed.await() }
            } }
            try {
                val text = "Original recovery check ${UUID.randomUUID()}. The quiet road leads home."
                val file = withContext(Dispatchers.Main) { speech.render(text, settings) }
                check(attempts == 2 && WaveAudio.durationMs(file) > 500)
                check(withContext(Dispatchers.Main) { speech.render(text, settings) }.name == file.name && attempts == 2)
                check(app.listening.settings.value == settings)
                report.add("PASS real installed engine reconnects after injected ${if (timeout) "lost-callback timeout" else "stopped request"}, retries exactly once, produces valid same-voice cached audio and preserves narrator settings")
            } finally { main { speech.close() } }
        }
        if (settings.enginePackage == "com.brahmadeo.supertonic.tts") {
            var attempts = 0
            val speech = main { OfflineSpeech(app) { completed, limit ->
                attempts++
                if (attempts == 1) throw SpeechSynthesisStopped() else withTimeout(limit) { completed.await() }
            } }
            try {
                val text = "Original phrase recovery ${UUID.randomUUID()}. " + "He went to the spring beside the quiet river. She followed with the folded map. ".repeat(3)
                val neutral = settings.copy(pitch = 1f)
                val file = withContext(Dispatchers.Main) { speech.render(text, neutral) }
                check(attempts >= 3 && file.name == AudioCacheKey.create(settings.enginePackage, settings.voiceName, 1f, text) + ".wav")
                val observed = attempts
                check(WaveAudio.durationMs(file) > 1000 && withContext(Dispatchers.Main) { speech.render(text, neutral) }.name == file.name && attempts == observed)
                check(app.listening.settings.value == settings)
                report.add("PASS real Supertonic long-request interruption recovers every original word through short same-voice phrases, stores a valid canonical WAV and reuses that exact audio on the next render; narrator unchanged")
            } finally { main { speech.close() } }
        }
    }
}
