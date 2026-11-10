package app.roadleaf

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.webkit.WebView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.roadleaf.playback.NarrationService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Real OS focus requests come from a separate disposable app, with no permissions or audio data. */
@UnstableApi
class AudioInterruptionChecks(
    private val instrumentation: Instrumentation,
    private val app: RoadleafApp,
    private val controller: MediaController,
    private val report: MutableList<String>
) {
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
    private fun await(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(75)
        check(condition()) { "Audio state not reached: ${main { "playing=${controller.isPlaying}, requested=${controller.playWhenReady}, suppression=${controller.playbackSuppressionReason}, status=${app.playbackStatus.value}" }}" }
    }
    private fun command(action: String, args: Bundle = Bundle.EMPTY): SessionResult = main {
        controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }.get(20, TimeUnit.SECONDS).also { check(it.resultCode == SessionResult.RESULT_SUCCESS) }
    private fun probe(gain: Int, holdMs: Int = 2500) = main {
        instrumentation.targetContext.startActivity(Intent().setClassName("app.roadleaf.focusprobe", "app.roadleaf.focusprobe.ProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("gain", gain).putExtra("holdMs", holdMs))
    }
    private fun temporaryPause() = await { main { !controller.isPlaying && controller.playWhenReady &&
        controller.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS } }
    private fun assertPaused() {
        val position = main { controller.currentPosition }
        Thread.sleep(650)
        check(main { !controller.isPlaying && !controller.playWhenReady && kotlin.math.abs(controller.currentPosition - position) < 100 })
    }
    fun run() {
        val url = "https://freewebnovel.com/novel/roadleaf-audio-test/chapter-1"
        val prose = "A traveller followed the road beside the river. She paused to study her map, then continued toward the lights of home. ".repeat(60)
        val reader = instrumentation.startActivitySync(Intent(instrumentation.targetContext, WebReaderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("url", url))
        try {
            val web = main { WebReaderActivity::class.java.getDeclaredField("web").apply { isAccessible = true }.get(reader) as WebView }
            main { web.resumeTimers(); web.loadDataWithBaseURL(url, "<html><head><title>Original audio fixture</title></head><body><div id='article'><p>$prose</p></div></body></html>", "text/html", "UTF-8", url) }
            await { main { web.progress == 100 && web.title == "Original audio fixture" } }
            val selected = command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true) })
            fun page(selection: Long = 0, request: Long = 0) = Bundle().apply {
                putString("url", url); putString("title", "Original audio chapter"); putString("bookTitle", "Original audio fixture")
                putString("text", prose); putLong("selection", selection); putLong("request", request); putBoolean("autoNext", false)
            }
            command(NarrationService.WEB_PAGE, page(selection = selected.extras.getLong("selection")))
            await(120_000) { main { controller.currentMediaItem?.mediaId?.startsWith("web:") == true && controller.isPlaying } }
            for (label in listOf("ride-request notification", "ride-acceptance notification")) {
                val pauses = AtomicInteger()
                val listener = object : Player.Listener { override fun onIsPlayingChanged(playing: Boolean) { if (!playing) pauses.incrementAndGet() } }
                main { controller.addListener(listener) }
                probe(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                await { main { !reader.hasWindowFocus() && controller.isPlaying } }
                val position = main { controller.currentPosition }
                Thread.sleep(900)
                check(main { controller.isPlaying && controller.playWhenReady && controller.currentPosition > position })
                await { main { reader.hasWindowFocus() } }
                main { controller.removeListener(listener) }
                check(pauses.get() == 0) { "Notification paused playback ${pauses.get()} times" }
                report.add("PASS $label: real MAY_DUCK focus does not pause narration; position advances while the other app stays foreground")
            }
            probe(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            temporaryPause()
            val callPosition = main { controller.currentPosition }
            Thread.sleep(500)
            check(main { !controller.isPlaying && kotlin.math.abs(controller.currentPosition - callPosition) < 100 })
            await { main { controller.isPlaying && !reader.hasWindowFocus() } }
            report.add("PASS call-style exclusive transient focus pauses narration and resumes when focus returns")
            await { main { reader.hasWindowFocus() } }
            probe(0)
            await { main { !reader.hasWindowFocus() } }
            val before = main { controller.currentPosition }
            Thread.sleep(650)
            check(main { controller.isPlaying && controller.currentPosition > before })
            report.add("PASS switching to another app without an audio request keeps webpage narration playing")
            await { main { reader.hasWindowFocus() } }

            probe(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            await { main { !reader.hasWindowFocus() && controller.isPlaying } }
            main { controller.pause() }
            await { main { reader.hasWindowFocus() } }
            assertPaused()
            report.add("PASS manual Pause during a notification stays paused after focus returns")
            main { controller.play() }; await { main { controller.isPlaying } }

            probe(AudioManager.AUDIOFOCUS_GAIN)
            await { main { !controller.playWhenReady && !controller.isPlaying } }
            await { main { reader.hasWindowFocus() } }
            assertPaused()
            report.add("PASS permanent music-style focus loss stays paused after the other app releases focus")

            main { reader.finish() }
            await { main { reader.isDestroyed } }
            fun beginNavigation(): Long {
                command(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", true); putBoolean("navigate", true) })
                await { main { controller.isPlaying } }
                return requireNotNull(app.webNavigation.value).id
            }
            val pending = beginNavigation()
            probe(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, 4000)
            temporaryPause()
            check(app.webNavigation.value?.id == pending)
            command(NarrationService.WEB_PAGE, page(request = pending))
            Thread.sleep(650)
            check(main { !controller.isPlaying && controller.playWhenReady && controller.currentMediaItem?.mediaId == "web-loading" })
            check(app.webNavigation.value?.id == pending)
            await { main { controller.isPlaying && controller.currentMediaItem?.mediaId?.startsWith("web:") == true } }
            report.add("PASS webpage handoff and prepared speech wait through exclusive call-style focus without reclaiming it early")
            Thread.sleep(2600) // Let the disposable foreground app close before the next independent request.

            beginNavigation()
            probe(AudioManager.AUDIOFOCUS_GAIN)
            await { app.webNavigation.value == null && main { !controller.playWhenReady } }
            Thread.sleep(5200)
            assertPaused()
            report.add("PASS permanent focus loss during webpage loading cancels the handoff and cannot resume later")
        } finally {
            command(NarrationService.WEB_PAUSE)
            main { if (!reader.isDestroyed) reader.finish() }
        }
    }
}
