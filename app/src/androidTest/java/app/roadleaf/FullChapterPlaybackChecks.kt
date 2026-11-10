package app.roadleaf

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import app.roadleaf.playback.*
import app.roadleaf.sources.LiveWebPage
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Real-time, unaccelerated chapter playback. Does not export chapter prose or audio. */
@UnstableApi
class FullChapterPlaybackChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp,
    private val controller: MediaController, private val report: MutableList<String>) {
    private fun <T> main(action: () -> T): T {
        val value = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { value.set(runCatching(action)) }; return value.get().getOrThrow()
    }
    data class Part(val offset: Int, val chars: Int, val key: String, val startMs: Long, val durationMs: Long)
    private data class Snapshot(val position: Long, val duration: Long, val state: Int, val playing: Boolean, val requested: Boolean, val error: String?)
    private fun hasSignal(file: File): Boolean {
        val spec = WaveAudio.layout(file)
        return RandomAccessFile(file, "r").use { input ->
            val step = (spec.frames / 4096).coerceAtLeast(1)
            var frame = 0L; var signal = false
            while (frame < spec.frames && !signal) {
                input.seek(spec.offset + frame * spec.frameBytes)
                signal = when (spec.bits) {
                    8 -> abs(input.readUnsignedByte() - 128) > 1
                    16 -> abs(java.lang.Short.reverseBytes(input.readShort()).toInt()) > 24
                    else -> abs(Float.fromBits(Integer.reverseBytes(input.readInt()))) > 0.001f
                }
                frame += step
            }
            signal
        }
    }
    fun run(page: LiveWebPage): List<Part> {
        val parts = linkedMapOf<Int, Part>()
        var changedChapter: String? = null
        val settings = app.listening.settings.value
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (item?.mediaId != "web:${page.url}") changedChapter = item?.mediaId ?: "empty"
            }
            override fun onEvents(player: Player, events: Player.Events) { observe() }
            fun observe() {
                val extras = controller.currentMediaItem?.mediaMetadata?.extras ?: return
                if (extras.getString("webUrl") != page.url || controller.currentMediaItem?.mediaId != "web:${page.url}") return
                val key = extras.getString("audioKey") ?: return
                val offset = extras.getInt("offset")
                parts.putIfAbsent(offset, Part(offset, extras.getInt("segmentChars"), key,
                    extras.getLong(ChapterSessionPlayer.START_MS), extras.getLong(ChapterSessionPlayer.DURATION_MS)))
            }
        }
        val checked = linkedMapOf<Int, Part>()
        val checkpoints = WebListeningProgress(app.getSharedPreferences("web-listening", 0))
        var previousPosition = -1L
        var stalledMs = 0L; var longestStall = 0L; var currentStall = 0L
        val started = SystemClock.elapsedRealtime()
        var lastTick = started; var nextReport = started + 60_000
        main {
            controller.setPlaybackSpeed(1f); controller.addListener(listener); listener.observe()
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        try {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                check(now - started < 20 * 60_000) { "Full chapter exceeded twenty-minute test limit" }
                val snapshot = main {
                    listener.observe()
                    check(changedChapter == null) { "Unexpected chapter/item transition: $changedChapter" }
                    check(controller.mediaItemCount <= 2 && controller.currentMediaItem?.mediaId == "web:${page.url}")
                    Snapshot(controller.currentPosition, controller.duration, controller.playbackState, controller.isPlaying, controller.playWhenReady, controller.playerError?.message)
                }
                check(snapshot.error == null) { "Playback error: ${snapshot.error}" }
                check(snapshot.position + 250 >= previousPosition) { "Chapter position restarted from $previousPosition to ${snapshot.position}" }
                previousPosition = snapshot.position
                // Media3 compares only the presence of metadata extras, not their contents.
                // The chapter intentionally stays stable; audit spans via the owning service's checkpoints.
                checkpoints.read(page.url)?.takeIf { it.offset !in checked && it.audioKey.isNotBlank() }?.let { saved ->
                    val file = File(app.cacheDir, "speech/${saved.audioKey}")
                    val duration = WaveAudio.durationMs(file)
                    main { parts.putIfAbsent(saved.offset, Part(saved.offset, saved.segmentChars, saved.audioKey, saved.chapterStartMs, duration)) }
                }
                for (part in main { parts.values.toList() }) if (part.offset !in checked) {
                    val expectedOffset = checked.values.lastOrNull()?.let { it.offset + it.chars } ?: 0
                    check(part.offset == expectedOffset && part.chars in 1..1800 && part.offset + part.chars <= page.text.length) { "Missing or overlapping chapter text at ${part.offset}; expected $expectedOffset" }
                    val file = File(app.cacheDir, "speech/${part.key}")
                    check(file.isFile && abs(WaveAudio.durationMs(file) - part.durationMs) <= 2 && hasSignal(file)) { "Invalid, missing or silent speech audio for ${part.offset}" }
                    check(abs(part.startMs - checked.values.sumOf { it.durationMs }) <= 2) { "Chapter clock discontinuity" }
                    checked[part.offset] = part
                }
                if (snapshot.state == Player.STATE_ENDED) {
                    check(snapshot.duration > 0 && abs(snapshot.position - snapshot.duration) < 500) { "Chapter ended at the wrong audio position" }
                    break
                }
                check(snapshot.requested) { "Unexpected pause before chapter completion" }
                val delta = now - lastTick
                if (!snapshot.playing) { stalledMs += delta; currentStall += delta; longestStall = maxOf(longestStall, currentStall) } else currentStall = 0
                check(currentStall < 30_000) { "Chapter stalled for thirty seconds: state=${snapshot.state} position=${snapshot.position} requested=${snapshot.requested}; ${app.playbackStatus.value}" }
                if (now >= nextReport) {
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PROGRESS full chapter: ${(now - started) / 1000}s elapsed, ${snapshot.position / 1000}s chapter position, ${checked.size} verified speech spans; app behind Home\n") })
                    nextReport = now + 60_000
                }
                lastTick = now
                Thread.sleep(200)
            }
            val covered = checked.values.sumOf { it.chars }
            check(covered == page.text.length) { "Chapter text coverage $covered of ${page.text.length}" }
            report.add("PASS full chapter at 1x behind Android Home: $covered/${page.text.length} original characters, ${checked.size} ordered speech spans, non-silent valid WAVs, zero skipped/overlapping spans and zero episode/position resets")
            report.add("PASS full chapter reached final audio naturally: elapsed=${SystemClock.elapsedRealtime() - started}ms, audio=${checked.values.sumOf { it.durationMs }}ms, observed buffering=${stalledMs}ms, longest=${longestStall}ms; no automatic next page; waveform/control verification, not a human listening-quality rating")
            return checked.values.toList()
        } finally {
            main { controller.pause(); controller.removeListener(listener); controller.setPlaybackSpeed(settings.speed); app.listening.save(settings) }
        }
    }
}
