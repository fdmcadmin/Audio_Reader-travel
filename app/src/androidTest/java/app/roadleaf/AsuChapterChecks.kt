package app.roadleaf

import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.roadleaf.importing.ExpeditionJson
import app.roadleaf.playback.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Run only with the operator's local fixture in the debug app's cache. */
@UnstableApi
internal class AsuChapterChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp,
    private val report: MutableList<String>) {
    private fun progress(message: String) {
        report.add(message)
        File(requireNotNull(app.getExternalFilesDir(null)), "asu-chapter-check/playback-report.txt").appendText("$message\n")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }

    suspend fun run() {
        val work = File(requireNotNull(app.getExternalFilesDir(null)), "asu-chapter-check")
        File(work, "playback-report.txt").writeText("")
        val inputs = work.listFiles { file -> file.isFile && ExpeditionJson.chapterNumber(file.name) == 4 }.orEmpty().sortedBy { it.name }
        require(inputs.size == 2) { "Place both local Chapter 4 JSON files in the test cache." }
        val chapters = inputs.map { ExpeditionJson.parse(it.name, it.readText()) }
        require(chapters.map { it.part } == listOf(1, 2))
        val settings = ListeningSettings(enginePackage = RoadleafAsuSpeech.ENGINE_ID)
        val speech = OfflineSpeech(app)
        val voices = AdaptiveVoices.from(speech.catalog(settings.enginePackage), settings)
        val bank = requireNotNull(voices.asu)
        val crew = (0..161).map { CastCharacter("Fixture $it", role = CastRole.AUTO) }
        val imported = bank.assignImported(BookVoiceCast(characters = crew), "", LearnedCast()).cast.validated()
        check(imported.characters.take(161).map { it.voice }.toSet() == bank.starters.toSet())
        val overflow = imported.characters.last().voice
        check(overflow !in bank.starters && bank.isCharacterVoice(overflow))
        check(bank.assignImported(imported, "", LearnedCast()).cast.characters.last().voice == overflow)

        val existing = LearnedCast(people = bank.starters.mapIndexed { index, id ->
            LearnedCharacter("Fixture $index", turns = 1, windows = 1, voice = id, engine = settings.enginePackage)
        })
        val key = "asu-instrumentation-" + java.util.UUID.randomUUID()
        val paths = JSONArray()
        val waveFiles = mutableListOf<File>()
        try {
            var learned = LearnedCast()
            for (chapter in chapters) {
                val file = File(work, "part-${chapter.part}.txt").apply { writeText(chapter.text) }
                ExpeditionJson.saveSpeakers(file.path, chapter)
                var state = DialogueState()
                val candidates = mutableListOf<Pair<String, String>>()
                for ((index, offset) in chapter.speakerOffsets.withIndex()) {
                    val end = chapter.speakerOffsets.getOrElse(index + 1) { chapter.text.length }
                    val original = chapter.text.substring(offset, end).trimEnd()
                    val attribution = ExpeditionJson.paragraphAt(file.path, offset)
                    val plan = AdaptiveCast.prepare(BookVoiceCast(), learned, voices, "part-${chapter.part}", offset,
                        original, state, attribution.dialogueSpeakers)
                    learned = plan.next
                    val cast = SceneCast.plan(original, plan.cast, state, explicitSpeakers = attribution.quoteSpeakers,
                        thoughtSpeaker = attribution.thoughtSpeaker)
                    state = cast.next
                    for (utterance in cast.utterances.filter { it.voice in bank.starters }) {
                        SpeechUtterances.sentences(NarrationText.english(utterance.text), "en-US")
                            .filter { it.length in 30..200 && it.any(Char::isLetter) }
                            .forEach { line -> candidates.add(line to utterance.voice) }
                    }
                }
                val choices = candidates.distinct().sortedByDescending { it.first.length }.take(3)
                check(choices.size == 3) { "Part ${chapter.part} is missing attributed auditions." }
                val (text, voice) = choices.first()
                val replacement = if (chapter.part == 1) null else {
                    val member = checkNotNull(learned.people.firstOrNull { it.voice == voice })
                    val plan = AdaptiveCast.prepare(BookVoiceCast(), existing, voices, "overflow", 0, text,
                        explicitSpeakers = listOf(member.name))
                    val binding = plan.next.people.single { it.name == member.name }
                    check(binding.voice !in bank.starters && bank.isCharacterVoice(binding.voice))
                    app.learnedCasts.save(key, plan.next)
                    val restored = LearnedCastPreferences(app).get(key)
                    check(restored.people.size == 162 && restored.people.single { it.name == member.name }.voice == binding.voice)
                    val again = AdaptiveCast.prepare(BookVoiceCast(), restored, voices, "overflow", 0, text,
                        explicitSpeakers = listOf(member.name))
                    check(again.next.people.single { it.name == member.name }.voice == binding.voice)
                    binding.voice
                }
                progress("PASS Chapter 4 part ${chapter.part}: ${chapter.speakerOffsets.size} paragraph metadata entries routed through the planner")
                for ((index, choice) in choices.withIndex()) {
                    val (line, assignedVoice) = choice
                    val finalVoice = replacement?.takeIf { assignedVoice == voice } ?: assignedVoice
                    val audio = speech.render(line, settings.copy(voiceName = finalVoice))
                    check(WaveAudio.durationMs(audio) > 250)
                    val export = File(work, "audio-part-${chapter.part}-${index + 1}.wav")
                    audio.copyTo(export, overwrite = true)
                    paths.put(JSONObject().put("file", export.name).put("text", line))
                    waveFiles += audio
                }
            }
            progress("PASS all 161 recipes used before overflow; the 162nd character recipe survived reload and replay")
            val combined = WaveAudio.join(waveFiles, File(app.cacheDir, "speech"), pausesAfter = List(waveFiles.size) { 250 })
            File(work, "audit.json").writeText(paths.toString())
            for (speed in listOf(1f, 1.25f, 1.5f)) audition(combined, speed)
        } finally {
            app.learnedCasts.clear(key)
            speech.close()
        }
    }

    private fun audition(wave: File, speed: Float) {
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(app).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(wave)))
                setPlaybackSpeed(speed)
                prepare()
                play()
            }
        }
        try {
            val started = SystemClock.elapsedRealtime()
            val expected = WaveAudio.durationMs(wave)
            var hearing = false
            var ended = false
            var prior = -1L
            while (!ended && SystemClock.elapsedRealtime() - started <= expected + 20_000) {
                instrumentation.runOnMainSync {
                    check(player.playerError == null && player.playbackParameters.speed == speed) { "ASU playback failed at ${speed}x." }
                    check(player.currentPosition >= prior) { "ASU position jumped backward." }
                    prior = player.currentPosition
                    hearing = hearing || player.isPlaying
                    ended = player.playbackState == Player.STATE_ENDED
                }
                Thread.sleep(50)
            }
            check(hearing && ended && abs(prior - expected) <= 200) { "ASU clip did not reach the end at ${speed}x." }
            progress("PASS Chapter 4 excerpts played to completion at ${speed}x (${expected}ms source audio)")
        } finally { instrumentation.runOnMainSync { player.release() } }
    }
}
