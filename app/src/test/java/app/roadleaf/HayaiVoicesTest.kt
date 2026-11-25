package app.roadleaf

import app.roadleaf.playback.AdaptiveVoices
import app.roadleaf.playback.BookVoiceCast
import app.roadleaf.playback.CastCharacter
import app.roadleaf.playback.CastRole
import app.roadleaf.playback.CharacterPronoun
import app.roadleaf.playback.HayaiVoices
import app.roadleaf.playback.ListeningSettings
import app.roadleaf.playback.OfflineVoiceOption
import app.roadleaf.playback.SpeechCatalog
import app.roadleaf.playback.VoiceDelegation
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HayaiVoicesTest {
    private val female = "kokoro-multi-lang-v1_1#0#af_alloy"
    private val male = "kokoro-multi-lang-v1_1#1#am_adam"
    private val chinese = "kokoro-multi-lang-v1_1#80#zf_001"

    @Test fun librittsChoicesKeepExactIdsAndSeparateTheTwoPacks() {
        val high = "en_US-libritts-high#12"
        val medium = "vits-piper-en_US-libritts_r-medium#12#6104"
        val engine = HayaiVoices.ENGINE
        assertEquals(HayaiVoices.LibriTtsPack.HIGH, HayaiVoices.libriTts(high, engine)?.pack)
        assertEquals(HayaiVoices.LibriTtsPack.R_MEDIUM, HayaiVoices.libriTts(medium, engine)?.pack)
        assertEquals(12, HayaiVoices.libriTts(medium, engine)?.number)
        val voice = OfflineVoiceOption(medium, HayaiVoices.locale(medium, "en", engine), 300, 300)
        assertEquals("en-US", voice.localeTag)
        val choice = voiceChoice(voice, engine)
        assertEquals(medium, choice.id)
        assertEquals(medium, choice.detail)
        assertEquals("Piper LibriTTS-R Medium · speaker 12", choice.title)
        assertEquals(VoiceDelegation.Gender.UNKNOWN, VoiceDelegation.gender(medium))
    }

    @Test fun aPackOnlyIdIsNotTreatedAsAnInstalledSpeaker() {
        val model = "vits-piper-en_US-libritts-high"
        val engine = HayaiVoices.ENGINE
        assertEquals(HayaiVoices.LibriTtsPack.HIGH, HayaiVoices.libriTts(model, engine)?.pack)
        assertNull(HayaiVoices.libriTts(model, engine)?.number)
        assertNull(HayaiVoices.libriTts("$model#oops", engine))
        assertNull(HayaiVoices.libriTts(model, "other.engine"))
        assertEquals("en-GB", HayaiVoices.locale("vits-piper-en_GB-alba-medium", "en-GB", engine))
    }

    @Test fun englishSpeakersInMultilingualBundleAppearUnderEnglish() {
        assertEquals("en-US", HayaiVoices.locale(female, "zh-CN", HayaiVoices.ENGINE))
        assertEquals("en-US", HayaiVoices.locale(male, "zh-CN", HayaiVoices.ENGINE))
        assertEquals("zh-CN", HayaiVoices.locale(chinese, "zh-CN", HayaiVoices.ENGINE))
        assertEquals("zh-CN", HayaiVoices.locale(female, "zh-CN", "another.engine"))
        assertTrue(HayaiVoices.supports(female, Locale.CHINA, Locale.US, HayaiVoices.ENGINE))
        assertFalse(HayaiVoices.supports(chinese, Locale.CHINA, Locale.US, HayaiVoices.ENGINE))
    }

    @Test fun voiceDelegationAndAdaptiveCastUseEnglishSpeakerGender() {
        assertEquals(VoiceDelegation.Gender.FEMALE, VoiceDelegation.gender(female))
        assertEquals(VoiceDelegation.Gender.MALE, VoiceDelegation.gender(male))
        val catalog = SpeechCatalog(emptyList(), listOf(female, male).map { OfflineVoiceOption(it, "en-US", 300, 300) }, HayaiVoices.ENGINE)
        val adaptive = AdaptiveVoices.from(catalog, ListeningSettings(enginePackage = HayaiVoices.ENGINE))
        assertEquals(listOf(female), adaptive.female)
        assertEquals(listOf(male), adaptive.male)
    }

    @Test fun importingWithAnotherEngineReplacesItsUnavailableBookNarrator() {
        val original = BookVoiceCast(narratorVoice = "en-supertonic-F1")
        val mapped = VoiceDelegation.assign(original, listOf(female, male), original.narratorVoice).cast
        assertEquals(female, mapped.narratorVoice)
    }

    @Test fun importedProfilesWaitUntilTheirFirstSpokenScene() {
        val cast = BookVoiceCast(exclusiveVoices = true, characters = listOf("One", "Two", "Three").map {
            CastCharacter(it, role = CastRole.AUTO, pronoun = CharacterPronoun.SHE)
        })
        val mapped = VoiceDelegation.assign(cast, listOf(male, female, "kokoro#2#af_heart"), male).cast
        assertEquals(0, mapped.characters.count { it.role == CastRole.CUSTOM })
        assertEquals(3, mapped.characters.count { it.role == CastRole.AUTO })
        mapped.validated()
    }

    @Test fun castCanSaveTheAsuBankAndOverflow() {
        BookVoiceCast(characters = (1..162).map { CastCharacter("Person $it", role = CastRole.AUTO) }).validated()
        val capacity = app.roadleaf.playback.AdaptiveCast.MAX_PEOPLE
        runCatching { BookVoiceCast(characters = (1..capacity + 1).map { CastCharacter("Person $it") }).validated() }
            .onSuccess { throw AssertionError("Cast exceeded its storage limit") }
    }
}
