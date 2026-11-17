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
    @Test fun onlyEnglishAndDerivedSpeakersCanBeSelected() {
        assertNull(NativeKokoro.speakerName("kokoro:28"))
        assertEquals(103, NativeKokoro.blendPairs().size)
        assertNotNull(NativeKokoro.speakerName("kokoro:54"))
        assertNotNull(NativeKokoro.speakerName("kokoro:156"))
        assertNull(NativeKokoro.speakerName("kokoro:157"))
        assertNull(NativeKokoro.speakerName("unrelated:2"))
        assertNull(NativeKokoro.speakerId("kokoro:-1"))
    }
    @Test fun automaticAssignmentsMatchModelGenderMetadata() {
        assertEquals(VoiceDelegation.Gender.FEMALE, VoiceDelegation.gender("kokoro:2"))
        assertEquals(VoiceDelegation.Gender.MALE, VoiceDelegation.gender("kokoro:18"))
        assertEquals(VoiceDelegation.Gender.MALE, VoiceDelegation.gender("kokoro:26"))
    }
}
