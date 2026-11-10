package app.roadleaf

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.roadleaf.playback.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real Media3/focus requests, injected call/route fixtures. No Phone permission grant or novel data. */
@UnstableApi
class HeadphoneMusicChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp, private val report: MutableList<String>) {
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }; return result.get().getOrThrow()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(40)
        check(condition()) { "Headphone music fixture did not reach expected state" }
    }
    fun run() {
        // Android 8–11 now use the legacy call monitor; these checks inject call state either way.
        check(Build.VERSION.SDK_INT >= 26) { "Headphone music checks require Android 8+" }
        val name = "headphone-music-check-${UUID.randomUUID()}"
        val preferences = AudioOutputPreferences(app, name)
        val directory = File(app.cacheDir, name).apply { mkdirs() }
        val file = WaveAudio.join(List(75) { WaveAudio.pause(directory) }, directory, gapMs = 0)
        val device = OutputDevice("fixture-headphones", "Fixture headphones", "Bluetooth media", 1)
        var output = OutputState(OutputSelection(device.key, device.name), listOf(device))
        val state = MutableStateFlow(HeadphoneMusicState())
        var permission = false
        var calls = CallProtection(true)
        var observer: ((CallProtection) -> Unit)? = null
        var requester = "app.roadleaf.debug"
        val audio = main { ExoPlayer.Builder(app).build() }
        val mode = main { HeadphoneMusicMode(app, audio, preferences, { output }, state, { audio.pause() }, {},
            phonePermission = { permission }, monitorFactory = { callback -> observer = callback
                object : CallProtectionMonitor {
                    override fun start() { callback(calls) }
                    override fun current() = calls
                    override fun close() { observer = null }
                }
            }) }
        val player = main { ChapterSessionPlayer(audio, { emptyList() }, ChapterAudioClock(), { false }, {}, mode::allowPlayback,
            { !HeadphoneMusicPolicy.blocksCarController(mode.enabled, requester) }) }
        try {
            check(main { !mode.setEnabled(true) && !mode.enabled && !preferences.musicMode.value })
            report.add("PASS denied call-detection prerequisite leaves coexistence off; no Phone permission granted by the test")
            permission = true
            check(main { mode.setEnabled(true) && audio.audioAttributes.contentType == C.AUDIO_CONTENT_TYPE_SPEECH && !audio.playWhenReady })
            check(AudioOutputPreferences(app, name).musicMode.value)
            main { audio.setMediaItem(MediaItem.fromUri(file.toURI().toString())); audio.prepare(); player.play() }
            await { main { audio.isPlaying } }
            report.add("PASS opt-in mode persists, uses speech media without automatic focus, stays paused on change and permits a guarded manual Play")
            main { instrumentation.targetContext.startActivity(Intent().setClassName("app.roadleaf.focusprobe", "app.roadleaf.focusprobe.ProbeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("gain", android.media.AudioManager.AUDIOFOCUS_GAIN).putExtra("holdMs", 4000)) }
            await { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Focus result: 1")?.isNotEmpty() == true }
            val position = main { audio.currentPosition }; Thread.sleep(900)
            check(main { audio.isPlaying && audio.currentPosition > position + 500 })
            report.add("PASS real granted permanent media-focus request from a foreground helper does not stop the no-focus fixture player; this does not establish physical output separation")
            main { requester = "com.google.android.projection.gearhead"; player.pause() }
            check(main { audio.playWhenReady })
            main { requester = "app.roadleaf.debug"; player.pause() }
            check(main { !audio.playWhenReady })
            report.add("PASS wrapper policy ignores a simulated car Pause in this mode while keeping the owner's Pause functional; real Android Auto identity still requires a car test")
            main { player.play(); calls = CallProtection(true, true); observer?.invoke(calls) }
            await { main { !audio.isPlaying && !audio.playWhenReady } }
            main { player.play() }; check(main { !audio.playWhenReady })
            main { calls = CallProtection(true); observer?.invoke(calls) }
            check(main { !audio.playWhenReady })
            report.add("PASS injected active-call state pauses actual Media3 playback, blocks Play and does not automatically resume after idle")
            main { output = output.copy(devices = emptyList()); player.play() }
            check(main { !audio.playWhenReady })
            main { output = output.copy(devices = listOf(device)) }
            check(main { !audio.playWhenReady })
            report.add("PASS absent headphone fixture blocks manual Play; returning the output cannot start narration")
            main { player.play(); permission = false; player.pause(); player.play() }
            check(main { !audio.playWhenReady && !mode.enabled && !preferences.musicMode.value && audio.audioAttributes.contentType == C.AUDIO_CONTENT_TYPE_UNKNOWN })
            report.add("PASS loss of the call-permission prerequisite stops playback, turns coexistence off and restores the normal media-focus configuration")
        } finally {
            main { mode.close(); player.release() }
            app.getSharedPreferences(name, 0).edit().clear().commit()
            directory.deleteRecursively()
        }
    }
}
