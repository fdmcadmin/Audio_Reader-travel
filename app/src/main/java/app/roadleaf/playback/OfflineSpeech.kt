package app.roadleaf.playback

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import android.os.SystemClock
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class OfflineSpeech(private val context: Context,
    private val awaitSynthesis: suspend (CompletableDeferred<Unit>, Long) -> Unit = { result, timeout -> withTimeout(timeout) { result.await() } }) {
    private var engine: TextToSpeech? = null
    private var enginePackage = ""
    private var appliedVoice = ""
    private var appliedPitch = Float.NaN
    private var cachedVoices: List<Voice>? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val mutex = Mutex()
    private val folder = File(context.cacheDir, "speech")
    private var lastTrimAt: Long? = null
    private var lastBudget = 0L
    // Engine and voice that have already spoken since the engine started, so warming up again is pointless.
    private var warmed = ""

    private suspend fun connect(requested: String): TextToSpeech {
        val installed = context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo?.takeIf { service -> service.enabled && service.exported }?.packageName }.distinct().sorted()
        val target = SpeechEngineSelection.select(requested,
            Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH), installed)
        engine?.takeIf { enginePackage == target }?.let { return it }
        engine?.shutdown(); engine = null
        return startEngine(target)
    }
    private suspend fun startEngine(packageName: String?): TextToSpeech {
        val initialized = CompletableDeferred<Unit>()
        val tts = try { TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) initialized.complete(Unit)
            else initialized.completeExceptionally(IllegalStateException("Speech engine could not start. Install voice data in Android Text-to-speech settings."))
        }, packageName) } catch (e: SecurityException) {
            throw IllegalStateException("This speech app cannot connect on this Android version. Try its official Play Store build or choose another offline engine.", e)
        }
        engine = tts
        try { withTimeout(20_000) { initialized.await() } } catch (e: Exception) { tts.shutdown(); engine = null; throw e }
        enginePackage = packageName ?: tts.defaultEngine.orEmpty()
        // Playback speed is applied once by Media3; do not inherit a second system TTS rate.
        check(tts.setSpeechRate(1f) == TextToSpeech.SUCCESS) { "Could not set the narrator's base speaking rate." }
        appliedVoice = ""; appliedPitch = Float.NaN; cachedVoices = null; warmed = ""
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.d("RoadleafPrep", "provider acknowledged request")
            }
            override fun onDone(id: String?) { id?.let { pending.remove(it)?.complete(Unit) } }
            override fun onStop(id: String?, interrupted: Boolean) { id?.let { pending.remove(it)?.completeExceptionally(SpeechSynthesisStopped()) } }
            @Deprecated("Android callback") override fun onError(id: String?) { id?.let { pending.remove(it)?.completeExceptionally(IllegalStateException("Offline synthesis failed. Check this voice's installed data.")) } }
            override fun onError(id: String?, errorCode: Int) { onError(id) }
        })
        return tts
    }
    private fun offlineVoices(tts: TextToSpeech, refresh: Boolean = false): List<Voice> {
        if (!refresh) cachedVoices?.let { return it }
        return tts.voices.orEmpty().filter {
        !it.isNetworkConnectionRequired && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }.also { cachedVoices = it }
    }
    suspend fun catalog(requestedEngine: String): SpeechCatalog = mutex.withLock {
        val tts = connect(requestedEngine)
        SpeechCatalog(tts.engines.map { SpeechEngineOption(it.name, it.label) }.sortedBy { it.label },
            offlineVoices(tts, refresh = true).sortedWith(compareBy<Voice> { it.locale.toLanguageTag() }.thenBy { it.name }).map { OfflineVoiceOption(it.name, it.locale.toLanguageTag(), it.quality, it.latency) }, enginePackage)
    }
    suspend fun render(text: String, settings: ListeningSettings, fallbackVoice: String? = null): File = mutex.withLock {
        SpeechRecovery.run(reset = {
            if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.d("RoadleafPrep", "reconnecting incomplete speech request")
            engine?.stop(); engine?.shutdown(); engine = null; cachedVoices = null; appliedVoice = ""; appliedPitch = Float.NaN; warmed = ""
        }) { renderAttempt(text, settings, fallbackVoice) }
    }
    /**
     * Starts the engine and has the narrator's voice say one short word into a file that is thrown away, so the engine
     * and its voice model are loaded before a story's first passage. True when the voice is ready.
     */
    suspend fun warmUp(settings: ListeningSettings): Boolean = mutex.withLock {
        val id = UUID.randomUUID().toString()
        val scratch = File(folder, "warm-up-$id.part")
        var tts: TextToSpeech? = null
        try {
            tts = connect(settings.enginePackage)
            val voice = voiceFor(tts, settings, null)
            val key = "$enginePackage/${voice.name}"
            if (warmed == key) return@withLock true
            if (appliedVoice != voice.name) { check(tts.setVoice(voice) == TextToSpeech.SUCCESS); appliedVoice = voice.name }
            if (appliedPitch != 1f) { check(tts.setPitch(1f) == TextToSpeech.SUCCESS); appliedPitch = 1f }
            val done = CompletableDeferred<Unit>()
            pending[id] = done
            withContext(Dispatchers.IO) { folder.mkdirs() }
            check(tts.synthesizeToFile("Ready.", Bundle(), scratch, id) == TextToSpeech.SUCCESS)
            // Never hold the engine for long: a voice that doesn't answer is simply left for the story itself.
            if (withTimeoutOrNull(6_000) { done.await() } == null) { tts.stop(); return@withLock false }
            warmed = key
            true
        } catch (cancelled: CancellationException) {
            tts?.stop()
            // An engine that didn't start in time is a failed warm-up, not a cancelled one.
            if (currentCoroutineContext().isActive) false else throw cancelled
        }
        catch (_: Exception) { false }
        finally { pending.remove(id); withContext(NonCancellable + Dispatchers.IO) { scratch.delete() } }
    }
    private fun voiceFor(tts: TextToSpeech, settings: ListeningSettings, fallbackVoice: String?): Voice {
        val voices = offlineVoices(tts)
        val locale = Locale.forLanguageTag(settings.localeTag)
        val requestedVoice = if (fallbackVoice != null && voices.none { it.name == settings.voiceName }) fallbackVoice else settings.voiceName
        return if (requestedVoice.isNotBlank()) voices.firstOrNull { it.name == requestedVoice }
            ?: error("The selected offline voice is unavailable. Refresh Voice settings and choose another voice.")
        else voices.filter { it.locale.language == locale.language }.sortedWith(compareBy<Voice> { it.locale.toLanguageTag() != settings.localeTag }.thenBy { it.latency }.thenByDescending { it.quality }.thenBy { it.name }).firstOrNull()
            ?: error("Install an offline ${locale.displayLanguage} voice in Android Text-to-speech settings, then refresh voices. Network voices are disabled.")
    }
    private suspend fun renderAttempt(text: String, settings: ListeningSettings, fallbackVoice: String?): File {
        return withTimeout(90_000) {
            val tts = connect(settings.enginePackage)
            val voice = voiceFor(tts, settings, fallbackVoice)
            if (appliedVoice != voice.name) { check(tts.setVoice(voice) == TextToSpeech.SUCCESS); appliedVoice = voice.name }
            // Apply pitch once to cached PCM: some installed neural engines ignore setPitch().
            if (appliedPitch != 1f) { check(tts.setPitch(1f) == TextToSpeech.SUCCESS); appliedPitch = 1f }
            require(text.length <= TextToSpeech.getMaxSpeechInputLength()) { "Pronunciation made this segment too long. Shorten the replacement." }
            val file = withContext(Dispatchers.IO) { folder.mkdirs(); File(folder, AudioCacheKey.create(enginePackage, voice.name, 1f, text) + ".wav") }
            if (withContext(Dispatchers.IO) { file.exists() && file.length() > 44 }) return@withTimeout withContext(Dispatchers.IO) { WavePitch.adjust(file, settings.pitch) }
            val temporary = File(folder, file.name + ".part")
            val id = UUID.randomUUID().toString(); val completed = CompletableDeferred<Unit>()
            pending[id] = completed
            val started = SystemClock.elapsedRealtime()
            val tracing = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            if (tracing) android.util.Log.d("RoadleafPrep", "synthesis begin voice=${voice.name} chars=${text.length}")
            try {
                check(tts.synthesizeToFile(text, Bundle(), temporary, id) == TextToSpeech.SUCCESS) { "Could not start offline speech." }
                awaitSynthesis(completed, if (enginePackage == "com.brahmadeo.supertonic.tts") 15_000 else 90_000)
                warmed = "$enginePackage/${voice.name}"
                withContext(Dispatchers.IO) { check(temporary.length() > 44 && temporary.renameTo(file)) { "Could not save audio." } }
                withContext(Dispatchers.IO) { WavePitch.adjust(file, settings.pitch) }
            } catch (failure: Exception) {
                if (failure is CancellationException) tts.stop()
                val incomplete = failure is TimeoutCancellationException || failure is SpeechSynthesisStopped
                if (!incomplete || enginePackage != "com.brahmadeo.supertonic.tts" || text.length <= 100) throw failure
                currentCoroutineContext().ensureActive()
                tts.stop()
                // Some provider requests acknowledge but never finish. Retry the complete
                // wording as short phrases, retaining the exact voice and canonical cache key.
                val pieces = SpeechUtterances.split(SpeechUtterances.singleParagraph(text), 100)
                check(pieces.joinToString("").filterNot(Char::isWhitespace) == text.filterNot(Char::isWhitespace))
                if (tracing) android.util.Log.d("RoadleafPrep", "short-phrase recovery voice=${voice.name} parts=${pieces.size}")
                val files = mutableListOf<File>()
                for (piece in pieces) if (piece.any(Char::isLetterOrDigit)) files.add(renderAttempt(piece, settings.copy(voiceName = voice.name, pitch = 1f), null))
                withContext(Dispatchers.IO) {
                    val joined = WaveAudio.join(files, folder)
                    val recovered = File(folder, file.name + ".recovering")
                    try { joined.copyTo(recovered, overwrite = true); check(recovered.renameTo(file)) { "Could not save recovered speech" } }
                    finally { recovered.delete() }
                }
                withContext(Dispatchers.IO) { WavePitch.adjust(file, settings.pitch) }
            }
            finally {
                if (tracing) android.util.Log.d("RoadleafPrep", "synthesis finish voice=${voice.name} elapsed=${SystemClock.elapsedRealtime() - started}ms saved=${file.exists()}")
                pending.remove(id); withContext(NonCancellable + Dispatchers.IO) { temporary.delete() }
            }
        }
    }
    /** Scan occasionally; the playlist's active files cannot be evicted. */
    suspend fun trimCache(protected: Set<String>, mode: ResourceMode) {
        val now = SystemClock.elapsedRealtime()
        if (lastBudget == mode.cacheBytes && lastTrimAt?.let { now - it < mode.trimIntervalMs } == true) return
        withContext(Dispatchers.IO) {
            val files = folder.listFiles().orEmpty().filter { it.extension == "wav" }.sortedBy { it.lastModified() }
            var size = files.sumOf { it.length() }
            for (file in files) if (size > mode.cacheBytes && file.absolutePath !in protected) { val bytes = file.length(); if (file.delete()) size -= bytes }
        }
        lastTrimAt = now; lastBudget = mode.cacheBytes
    }
    fun close() { engine?.stop(); pending.values.forEach { it.cancel() }; pending.clear(); engine?.shutdown(); engine = null; warmed = "" }
}
