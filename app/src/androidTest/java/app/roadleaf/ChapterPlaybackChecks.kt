package app.roadleaf

import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import app.roadleaf.playback.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class ChapterPlaybackChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp, private val report: MutableList<String>) {
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(30)
        check(condition()) { "Chapter player state did not arrive" }
    }
    suspend fun run() {
        val selected = mutableListOf<String>(); val transitions = mutableListOf<String?>()
        val clock = ChapterAudioClock()
        val directory = File(app.cacheDir, "chapter-check-${UUID.randomUUID()}").apply { mkdirs() }
        val silence = WaveAudio.pause(directory)
        fun logical(id: String) = MediaItem.Builder().setMediaId("chapter:$id").setMediaMetadata(MediaMetadata.Builder()
            .setTitle("Chapter $id").setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).build()).build()
        fun raw(chapter: String, part: Int, start: Long) = MediaItem.Builder().setMediaId("$chapter:$part").setUri(silence.toURI().toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Chapter $chapter").setExtras(Bundle().apply {
                putString("chapterId", chapter); putLong(ChapterSessionPlayer.START_MS, start); putLong(ChapterSessionPlayer.DURATION_MS, 400)
            }).build()).build()
        val audio = main { ExoPlayer.Builder(app).build() }
        var pending = false
        val player = main { ChapterSessionPlayer(audio, { listOf(logical("one"), logical("two")) }, clock, { pending }, { selected.add(it); Unit }) }
        val session = main { MediaSession.Builder(app, player).setId("chapter-check-${UUID.randomUUID()}").build() }
        val connection = main { MediaController.Builder(app, session.token).buildAsync() }
        val controller = connection.get(20, TimeUnit.SECONDS)
        try {
            main {
                clock.append("chapter:one", 400, false); clock.append("chapter:one", 400, true); clock.append("chapter:two", 400, true)
                player.addListener(object : Player.Listener { override fun onMediaItemTransition(item: MediaItem?, reason: Int) { transitions.add(item?.mediaId) } })
                audio.setMediaItems(listOf(raw("one", 0, 0), raw("one", 1, 400), raw("two", 0, 0))); audio.prepare()
            }
            await { main { controller.playbackState == Player.STATE_READY && controller.mediaItemCount == 2 } }
            check(main { controller.currentMediaItem?.mediaId } == "chapter:one")
            check(main { controller.duration } == 800L)
            check(main { controller.currentMediaItem?.mediaMetadata?.mediaType } == MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            check(main { controller.currentMediaItem?.localConfiguration } == null)
            main { audio.seekTo(1, 120) }
            await { main { controller.currentPosition >= 500 } }
            check(main { controller.currentMediaItemIndex == 0 && transitions.count { it == "chapter:one" } == 1 })
            report.add("PASS media session: two real chapters from three private WAVs; stable chapter UID, continuous chapter position, audiobook metadata and no private file URI")
            main { controller.seekBack() }
            await { main { controller.currentPosition < 100 } }
            main { controller.seekForward() }
            await { main { controller.currentPosition >= 750 && controller.currentMediaItemIndex == 0 } }
            report.add("PASS backward/forward seeks cross prepared private windows while retaining the chapter")
            main { controller.seekToNextMediaItem() }
            await { main { selected.lastOrNull() == "chapter:two" } }
            main { audio.seekTo(2, 0) }
            await { main { controller.currentMediaItemIndex == 1 } }
            main { controller.seekToPreviousMediaItem() }
            await { main { selected.lastOrNull() == "chapter:one" } }
            check(main { transitions.count { it == "chapter:two" } == 1 })
            report.add("PASS Bluetooth/media next and previous choose actual chapters, not speech windows")
            main { audio.seekTo(0, 0); audio.play() }
            await { main { controller.isPlaying } }
            await { main { audio.currentMediaItemIndex == 1 } }
            check(main { controller.currentMediaItem?.mediaId == "chapter:one" && controller.currentPosition >= 400 })
            report.add("PASS automatic private-window transition continues the same chapter and elapsed time")
            main { pending = true; audio.seekTo(2, 390); audio.play() }
            await { main { audio.playbackState == Player.STATE_ENDED } }
            check(main { controller.playbackState == Player.STATE_BUFFERING })
            report.add("PASS incremental synthesis reports buffering instead of a false chapter ending")
        } finally {
            main { controller.release(); session.release(); player.release() }
            directory.deleteRecursively()
        }

        val bookA = "cast-check-${UUID.randomUUID()}"; val bookB = "cast-check-${UUID.randomUUID()}"
        val settings = app.listening.settings.value
        val speech = main { OfflineSpeech(app) }
        try {
            val saved = BookVoiceCast(true, "Arlo", "main-one", "male-one", "female-one", listOf(
                CastCharacter("Vale", role = CastRole.CUSTOM, voice = "fixed", delivery = CharacterDelivery.FOLLOW_STORY, startsLower = true, pronoun = CharacterPronoun.HE),
                CastCharacter("Mara", delivery = CharacterDelivery.NATURAL, pronoun = CharacterPronoun.SHE)),
                mainPronoun = CharacterPronoun.HE, chapterViewpoints = mapOf("test-chapter" to "Vale"), narratorVoice = "book-narrator")
            app.casts.save(bookA, saved)
            app.casts.save(bookB, BookVoiceCast(true, "Different MC", "main-two"))
            check(app.casts.get(bookA).mainCharacter == "Arlo" && app.casts.get(bookB).mainVoice == "main-two")
            check(VoiceCastPreferences(app).get(bookA) == saved)
            check(VoiceCastPreferences(app).get(bookB).narratorVoice.isBlank())
            val state = DialogueState('”', "main", "Arlo said, ", DeliveryCues.LOWER_PITCH, "Mara studied the map.", " Vale nodded. ")
            check(VoiceCastPreferences.state(VoiceCastPreferences.encode(state)) == state)
            check(VoiceCastPreferences.state("""{"quote":"”","actor":"main","lead":"Arlo said, "}""").pitchScale == 1f)
            check(VoiceCastPreferences.state("""{"quote":"”","actor":"main","lead":"Arlo said, "}""").narrativeLead.isEmpty())
            check(app.casts.get("https://tales.example/an-unsaved-book").mainCharacter.isEmpty()) { "A book without a saved cast must not start with a preset main character" }
            report.add("PASS book-specific cast/delivery persistence and pitched dialogue checkpoint round trip; legacy pitch defaults to natural; narrator preference unchanged")
            val catalog = withContext(Dispatchers.Main) { speech.catalog(settings.enginePackage) }
            val neutral = withContext(Dispatchers.Main) { speech.render("The road is quiet. We should stay together and find our way home.", settings.copy(pitch = 1f)) }
            val adjusted = withContext(Dispatchers.Main) { speech.render("The road is quiet. We should stay together and find our way home.", settings.copy(pitch = DeliveryCues.LOWER_PITCH)) }
            check(neutral != adjusted && !neutral.readBytes().contentEquals(adjusted.readBytes()))
            check(kotlin.math.abs(WaveAudio.durationMs(adjusted) - WaveAudio.durationMs(neutral)) < 150)
            report.add("PASS actual installed offline engine produces distinct locally pitched PCM with preserved duration; neutral narration cache retained")
            val voices = catalog.voices.filter { it.localeTag == settings.localeTag }
            if (voices.size >= 2) {
                val cast = BookVoiceCast(true, "Arlo", voices[1].name)
                val result = withContext(Dispatchers.Main) { CastSpeechRenderer(speech, app.cacheDir).render(
                    "He went to the spring. Arlo said, \"We should return.\"", cast, DialogueState(), settings.copy(voiceName = voices[0].name), { it }) }
                check(WaveAudio.durationMs(result.file) > 500)
                report.add("PASS two installed offline voices synthesize and join into valid chapter audio through one engine; engine=${catalog.enginePackage}")
            } else report.add("LIMIT emulator has fewer than two installed ${settings.localeTag} voices; cast selection tested, audible multi-voice quality awaits a phone")
            check(app.listening.settings.value == settings)
        } finally {
            main { speech.close() }
            app.getSharedPreferences("voice-cast", 0).edit().remove(bookA).remove(bookB).commit()
        }
    }
}
