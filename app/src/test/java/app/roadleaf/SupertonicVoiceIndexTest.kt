package app.roadleaf

import app.roadleaf.playback.AdaptiveVoices
import app.roadleaf.playback.ListeningSettings
import app.roadleaf.playback.NativeSupertonic
import app.roadleaf.playback.OfflineVoiceOption
import app.roadleaf.playback.SpeechCatalog
import app.roadleaf.playback.SupertonicVoiceIndex
import app.roadleaf.playback.BookVoiceCast
import app.roadleaf.playback.CastCharacter
import app.roadleaf.playback.CastRole
import app.roadleaf.playback.CharacterPronoun
import app.roadleaf.playback.VoiceDelegation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupertonicVoiceIndexTest {
    @Test fun optedInImportAssignsDeferredCharactersWithoutUsingNarratorVoices() {
        val femaleNarrator = "supertonic3:0:F1"
        val maleNarrator = "supertonic3:5:M1"
        val femaleCharacter = "supertonic3-rvs:F06"
        val maleCharacter = "supertonic3-rvs:M06"
        val cast = BookVoiceCast(exclusiveVoices = true, separateViewpointNarrators = true,
            femaleNarratorVoice = femaleNarrator, maleNarratorVoice = maleNarrator,
            characters = listOf(
                CastCharacter("Mira", role = CastRole.AUTO, pronoun = CharacterPronoun.SHE),
                CastCharacter("Bram", role = CastRole.CUSTOM, pronoun = CharacterPronoun.HE, voice = "other-engine")
            ))
        val result = VoiceDelegation.assignBuiltIn(cast,
            listOf(femaleNarrator, maleNarrator, femaleCharacter, maleCharacter), femaleNarrator)
        assertEquals(listOf(femaleCharacter, maleCharacter), result.cast.validated().characters.map { it.voice })
        assertEquals(setOf(femaleNarrator, maleNarrator), result.cast.viewpointNarrators)
        assertEquals(2, result.assigned)
    }

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
