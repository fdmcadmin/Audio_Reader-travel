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
    @Test(expected = IllegalArgumentException::class) fun v11LabelCannotSilentlyUseV10SpeakerIds() {
        NativeKokoro.voiceIdForTag("sherpa-onnx", "kokoro-multi-lang-v1_1", "af_bella", 2)
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
