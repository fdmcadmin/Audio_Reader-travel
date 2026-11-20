package app.roadleaf

import app.roadleaf.playback.AdaptiveVoices
import app.roadleaf.playback.ListeningSettings
import app.roadleaf.playback.NativeSupertonic
import app.roadleaf.playback.OfflineVoiceOption
import app.roadleaf.playback.SpeechCatalog
import app.roadleaf.playback.SupertonicVoiceIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupertonicVoiceIndexTest {
    @Test fun stockAndBlendedVoiceIdsResolveToTheExpectedIndex() {
        val names = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5", "F1+F2")
        assertEquals(6, SupertonicVoiceIndex.speaker("en-supertonic-M2", names))
        assertEquals(10, SupertonicVoiceIndex.speaker("supertonic3:10:F1+F2", names))
        assertNull(SupertonicVoiceIndex.speaker("supertonic3:10:M1+M2", names))
        assertNull(SupertonicVoiceIndex.speaker("supertonic3:169:M1+M2", names))
    }

    @Test fun builtInVoiceCatalogPreservesThePackVoiceFamilies() {
        val engine = NativeSupertonic.ENGINE_ID
        val female = "supertonic3:10:F1+F2"
        val male = "supertonic3:20:M1+M2"
        val voices = listOf(female, male, "vctk:0").map { OfflineVoiceOption(it, "en-US", 400, 100) }
        val cast = AdaptiveVoices.from(SpeechCatalog(emptyList(), voices, engine), ListeningSettings(enginePackage = engine))
        assertEquals(listOf(female), cast.female)
        assertEquals(listOf(male), cast.male)
        assertEquals(female, cast.narrator)
    }
}
