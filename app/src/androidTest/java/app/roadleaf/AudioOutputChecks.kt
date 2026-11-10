package app.roadleaf

import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import app.roadleaf.playback.*
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class AudioOutputChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp,
    private val controller: MediaController, private val report: MutableList<String>) {
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }; return result.get().getOrThrow()
    }
    private fun command(action: String, args: Bundle = Bundle.EMPTY) = main {
        controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }.get(20, TimeUnit.SECONDS).also { check(it.resultCode == SessionResult.RESULT_SUCCESS) }
    private suspend fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 45_000
        while (!condition() && System.currentTimeMillis() < until) delay(75)
        check(condition()) { "Output check timed out: ${app.outputState.value.message}; ${app.playbackStatus.value}" }
    }
    suspend fun run() {
        val backup = PhoneListeningState(app)
        val previous = app.audioOutput.selection.value
        val previousResult = app.audioOutput.lastResult.value
        val settings = app.listening.settings.value
        backup.capture()
        try {
            command(NarrationService.OUTPUTS)
            val speaker = app.outputState.value.devices.firstOrNull { it.kind == "Phone speaker" } ?: error("No built-in speaker output")
            command(NarrationService.SET_OUTPUT, Bundle().apply { putString("key", speaker.key) })
            check(AudioOutputPreferences(app).selection.value == OutputSelection(speaker.key, speaker.name))
            check(main { !controller.playWhenReady })
            report.add("PASS connected outputs enumerate without a Bluetooth permission; explicit choice persists and stays paused")
            command(NarrationService.TEST_OUTPUT)
            await { main { controller.isPlaying } }
            await { app.outputState.value.confirmed }
            check(app.outputState.value.reportedDevice == speaker.name)
            await { main { controller.playbackState == Player.STATE_ENDED && !controller.playWhenReady } }
            check(app.audioOutput.lastResult.value.contains("matched=true"))
            check(AudioOutputPreferences(app).lastResult.value == app.audioOutput.lastResult.value)
            report.add("PASS audible original-text output test completed with actual Roadleaf AudioTrack routing confirmed to the selected phone speaker; observation survives reopening")
            val missing = OutputSelection(AudioOutputPolicy.key(8, "roadleaf-missing-fixture", "Absent headphones"), "Absent headphones")
            app.audioOutput.save(missing); command(NarrationService.OUTPUTS)
            main { controller.play() }; delay(300)
            check(main { !controller.playWhenReady && !controller.isPlaying })
            check(app.outputState.value.message.startsWith("Reconnect"))
            report.add("PASS media-controller Play with a missing selected output stays paused, despite the phone speaker remaining available")
            command(NarrationService.TEST_OUTPUT); delay(1500)
            check(main { !controller.playWhenReady && !controller.isPlaying })
            report.add("PASS output preview cannot fall back to another output while the saved selected device is absent")
            val rejected = main { controller.sendCustomCommand(SessionCommand(NarrationService.SET_OUTPUT, Bundle.EMPTY), Bundle().apply { putString("key", "not-a-connected-output") }) }.get(20, TimeUnit.SECONDS)
            check(rejected.resultCode != SessionResult.RESULT_SUCCESS && app.audioOutput.selection.value == missing)
            command(NarrationService.SET_OUTPUT, Bundle().apply { putString("key", "") })
            check(main { !controller.playWhenReady } && app.audioOutput.selection.value == OutputSelection())
            check(app.outputState.value.report(app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()).contains("requires a parked listening check"))
            report.add("PASS unavailable choices are rejected; returning to system/Android Auto defaults stays paused; shareable result distinguishes observed routing from untested other-app/car separation")
            check(app.listening.settings.value == settings)
        } finally {
            main { controller.pause() }
            app.audioOutput.save(previous); app.audioOutput.observe(previousResult); command(NarrationService.OUTPUTS)
            backup.restore(rollbackCast = true)
        }
        report.add("PASS original phone preferences, reading position, queue, previous cast and narrator restored; selected-output test helper left no test books")
    }
}
