package app.roadleaf

import app.roadleaf.importing.LorebookCast
import app.roadleaf.importing.LorebookCast.Entry
import app.roadleaf.playback.BookVoiceCast
import app.roadleaf.playback.CastCharacter
import app.roadleaf.playback.CastRole
import app.roadleaf.playback.CharacterPronoun
import app.roadleaf.playback.VoiceDelegation
import app.roadleaf.playback.VoiceDelegation.Gender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastImportTest {
    private val prose = "Mara Vell grew up on the night ferries. She keeps a brass compass that her mother gave her, and she trusts it more than any map."

    @Test fun charactersComeFromTheirCategoryNotPlaces() {
        assertTrue(LorebookCast.isCharacter(Entry(name = "Tobin", text = "A quiet carpenter.", category = "Characters")))
        assertFalse(LorebookCast.isCharacter(Entry(name = "Harbour of Glass", text = "Gender: none. Age: old. A port city.", category = "Places")))
        assertFalse(LorebookCast.isCharacter(Entry(name = "Tobin", text = "Gender: male", category = "Characters", enabled = false)))
    }

    @Test fun uncategorisedEntriesNeedProfileFieldsOrClearPronouns() {
        assertTrue(LorebookCast.isCharacter(Entry(name = "Mara Vell", text = prose)))
        assertTrue(LorebookCast.isCharacter(Entry(name = "Tobin Ashe", text = "[ Tobin Ashe: gender: male; age: 30; occupation: carpenter ]")))
        assertFalse(LorebookCast.isCharacter(Entry(name = "The Salt Road", text = "A trade route along the coast. Caravans use it in spring.")))
        assertFalse(LorebookCast.isCharacter(Entry(name = "rules of magic", text = "Gender: n/a. Age: ancient.")))
    }

    @Test fun pronounsComeFromLabelsThenDominantPronouns() {
        assertEquals(CharacterPronoun.SHE, LorebookCast.pronoun("Name: Mara\nGender: Female\nAge: 24"))
        assertEquals(CharacterPronoun.HE, LorebookCast.pronoun("[ Tobin: gender: male; likes: wood ]"))
        assertEquals(CharacterPronoun.THEY, LorebookCast.pronoun("Pronouns: they/them"))
        assertEquals(CharacterPronoun.SHE, LorebookCast.pronoun(prose))
        assertEquals(CharacterPronoun.AUTO, LorebookCast.pronoun("He met her at the dock. She waved; he nodded."))
    }

    @Test fun namesAreCleanedAndNicknamesComeFromKeys() {
        assertEquals("Mara Vell", LorebookCast.name(Entry(name = "12 Mara Vell — Preset")))
        assertEquals("Mara", LorebookCast.name(Entry(name = "Mara (protagonist)")))
        assertEquals("Tobin", LorebookCast.name(Entry(name = "", keys = listOf("/tob(in)?/i", "Tobin", "Tob"))))
        val result = LorebookCast.characters(listOf(Entry(id = "a1", name = "Mara Vell", keys = listOf("Mara", "Mara Vell", "/mara/i", "Captain"), text = prose)))
        val mara = result.characters.single()
        assertEquals("Mara,Captain", mara.aliases)
        assertEquals(CharacterPronoun.SHE, mara.pronoun)
        assertEquals("a1", mara.profileId)
        assertFalse(mara.toString().contains("compass"))
    }

    @Test fun repeatedCharactersMergeAndTheCastStaysWithinItsLimit() {
        val twice = LorebookCast.characters(listOf(
            Entry(name = "Mara", keys = listOf("Mara", "Vell"), text = "Gender: female"),
            Entry(name = "MARA", keys = listOf("Captain"), text = "Gender: female")))
        assertEquals(1, twice.characters.size)
        assertEquals("Vell,Captain", twice.characters.single().aliases)
        val many = LorebookCast.characters((1..70).map { Entry(id = "c$it", name = "Person $it", category = "Characters") })
        assertEquals(64, many.characters.size)
        assertEquals(70, many.found)
    }

    @Test fun voiceGenderComesOnlyFromLabelledVoiceIds() {
        assertEquals(Gender.FEMALE, VoiceDelegation.gender("en-supertonic-F3"))
        assertEquals(Gender.MALE, VoiceDelegation.gender("en-supertonic-M1"))
        assertEquals(Gender.FEMALE, VoiceDelegation.gender("en-us-x-sfg#female_1-local"))
        assertEquals(Gender.MALE, VoiceDelegation.gender("en-us-x-iom#male_2-local"))
        assertEquals(Gender.UNKNOWN, VoiceDelegation.gender("en-us-x-tpd-local"))
    }

    private val supertonic = listOf("en-supertonic-F1", "en-supertonic-F2", "en-supertonic-M1", "en-supertonic-M2", "en-supertonic-M5")

    @Test fun charactersGetDistinctMatchingVoicesAndTheNarratorStaysTheirOwn() {
        val cast = BookVoiceCast(mainCharacter = "Mara", mainPronoun = CharacterPronoun.SHE, characters = listOf(
            CastCharacter("Tobin", role = CastRole.AUTO, pronoun = CharacterPronoun.HE),
            CastCharacter("Iris", role = CastRole.AUTO, pronoun = CharacterPronoun.SHE)))
        val result = VoiceDelegation.assign(cast, supertonic, narrator = "en-supertonic-M5")
        val voices = listOf(result.cast.mainVoice) + result.cast.characters.map { it.voice }
        assertEquals(Gender.FEMALE, VoiceDelegation.gender(result.cast.mainVoice))
        assertEquals(Gender.MALE, VoiceDelegation.gender(result.cast.characters[0].voice))
        assertEquals(Gender.FEMALE, VoiceDelegation.gender(result.cast.characters[1].voice))
        assertEquals(3, voices.distinct().size)
        assertFalse("en-supertonic-M5" in voices)
        assertTrue(result.cast.characters.all { it.role == CastRole.CUSTOM })
        assertEquals(3, result.assigned)
        assertTrue(result.shared.isEmpty() && result.missing.isEmpty() && !result.unlabelled)
        assertEquals(4, result.available)
    }

    @Test fun chosenVoicesStayAndMissingOnesAreReplaced() {
        val cast = BookVoiceCast(characters = listOf(
            CastCharacter("Iris", voice = "en-supertonic-F2", pronoun = CharacterPronoun.SHE),
            CastCharacter("Wren", voice = "en-supertonic-F9", pronoun = CharacterPronoun.SHE),
            CastCharacter("Old Sal", role = CastRole.NARRATOR)))
        val result = VoiceDelegation.assign(cast, supertonic, narrator = "en-supertonic-M5")
        assertEquals("en-supertonic-F2", result.cast.characters[0].voice)
        assertEquals("en-supertonic-F1", result.cast.characters[1].voice)
        assertEquals(CastRole.NARRATOR, result.cast.characters[2].role)
        assertEquals(listOf("en-supertonic-F9"), result.missing)
    }

    @Test fun whenVoicesRunOutCharactersShareAndMoreVoicesAreSuggested() {
        val cast = BookVoiceCast(characters = listOf("Ada", "Bea", "Cora", "Dot").map { CastCharacter(it, role = CastRole.AUTO, pronoun = CharacterPronoun.SHE) })
        val result = VoiceDelegation.assign(cast, listOf("en-supertonic-F1", "en-supertonic-F2", "en-supertonic-M5"), narrator = "en-supertonic-M5")
        assertTrue(result.cast.characters.all { VoiceDelegation.gender(it.voice) == Gender.FEMALE })
        assertEquals(4, result.shared.size)
        assertTrue(result.needsMoreVoices)
    }

    @Test fun unlabelledEnginesStillGetDistinctVoicesButAskForAListen() {
        val cast = BookVoiceCast(characters = listOf(CastCharacter("Iris", role = CastRole.AUTO, pronoun = CharacterPronoun.SHE),
            CastCharacter("Tobin", role = CastRole.AUTO, pronoun = CharacterPronoun.HE)))
        val result = VoiceDelegation.assign(cast, listOf("en-us-x-aaa-local", "en-us-x-bbb-local", "en-us-x-ccc-local"), narrator = "en-us-x-aaa-local")
        assertEquals(setOf("en-us-x-bbb-local", "en-us-x-ccc-local"), result.cast.characters.map { it.voice }.toSet())
        assertTrue(result.unlabelled)
        assertEquals("", result.cast.femaleVoice)
        assertEquals("", result.cast.maleVoice)
    }

    @Test fun withoutInstalledVoicesNothingChanges() {
        val cast = BookVoiceCast(characters = listOf(CastCharacter("Iris", role = CastRole.AUTO, pronoun = CharacterPronoun.SHE)))
        val result = VoiceDelegation.assign(cast, emptyList(), narrator = "")
        assertEquals(cast.characters, result.cast.characters)
        assertEquals(0, result.assigned)
        assertTrue(result.needsMoreVoices)
    }
}
