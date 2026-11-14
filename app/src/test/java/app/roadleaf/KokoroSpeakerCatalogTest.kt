package app.roadleaf

import app.roadleaf.playback.NativeKokoro
import app.roadleaf.playback.VoiceDelegation
import org.junit.Assert.*
import org.junit.Test

class KokoroSpeakerCatalogTest {
    @Test fun canonicalCharacterIdsAreStable() {
        assertEquals("af_bella", NativeKokoro.speakerName("kokoro:2"))
        assertEquals("am_puck", NativeKokoro.speakerName("kokoro:18"))
        assertEquals("bm_george", NativeKokoro.speakerName("kokoro:26"))
    }
    @Test fun onlyEnglishSpeakersCanBeSelected() {
        assertNull(NativeKokoro.speakerName("kokoro:28"))
        assertNull(NativeKokoro.speakerName("kokoro:103"))
        assertNull(NativeKokoro.speakerName("unrelated:2"))
        assertNull(NativeKokoro.speakerId("kokoro:-1"))
    }
    @Test fun taggedLorebookSpeakersMatchTheInstalledV10Catalog() {
        assertEquals("kokoro:2", NativeKokoro.voiceIdForTag("sherpa-onnx", "kokoro-multi-lang-v1_0", "af_bella", 2))
        assertEquals("kokoro:18", NativeKokoro.voiceIdForTag("sherpa-onnx", "kokoro-multi-lang-v1_0", "am_puck", 18))
        assertNotEquals(NativeKokoro.voiceIdForSpeaker("af_bella"), NativeKokoro.voiceIdForSpeaker("am_puck"))
    }
    @Test fun v11MislabelIsCorrectedOnlyForAnExactV10SpeakerPair() {
        val taggedSpeakers = listOf(
            "af_bella" to 2, "am_puck" to 18, "af_nova" to 7,
            "am_liam" to 15, "af_kore" to 5, "am_onyx" to 17,
        )
        taggedSpeakers.forEach { (name, sid) ->
            assertEquals("kokoro:$sid", NativeKokoro.voiceIdForTag("sherpa-onnx", "kokoro-multi-lang-v1_1", name, sid))
        }
        assertTrue(NativeKokoro.correctedV11Label("kokoro-multi-lang-v1_1"))
    }
    @Test(expected = IllegalStateException::class) fun anActualV11SpeakerCannotUseTheV10Catalog() {
        NativeKokoro.voiceIdForTag("sherpa-onnx", "kokoro-multi-lang-v1_1", "af_maple", 0)
    }
    @Test(expected = IllegalArgumentException::class) fun speakerNameAndIdMustAgree() {
        NativeKokoro.voiceIdForTag("sherpa-onnx", "kokoro-multi-lang-v1_0", "af_bella", 18)
    }
    @Test fun automaticAssignmentsMatchModelGenderMetadata() {
        assertEquals(VoiceDelegation.Gender.FEMALE, VoiceDelegation.gender("kokoro:2"))
        assertEquals(VoiceDelegation.Gender.MALE, VoiceDelegation.gender("kokoro:18"))
        assertEquals(VoiceDelegation.Gender.MALE, VoiceDelegation.gender("kokoro:26"))
    }
}
