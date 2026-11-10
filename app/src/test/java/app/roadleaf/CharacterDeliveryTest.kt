package app.roadleaf

import app.roadleaf.playback.*
import org.junit.Assert.*
import org.junit.Test

class CharacterDeliveryTest {
    private val opponent = CastCharacter("Vale", role = CastRole.CUSTOM, voice = "opponent", startsLower = true)
    private val teacher = CastCharacter("Mara", role = CastRole.CUSTOM, voice = "teacher")
    private val profile = BookVoiceCast(mainCharacter = "Arin", mainVoice = "mc", characters = listOf(opponent, teacher))
    private fun quote(cast: BookVoiceCast, text: String) = VoiceCastPlanner(cast).plan(text).utterances.first { it.text.contains('"') }
    @Test fun lowerDeliveryIsPerCharacterAndKeepsTheSameVoice() {
        val hostile = quote(profile, "Vale replied, \"Wait.\"")
        assertEquals("opponent", hostile.voice); assertEquals(0.92f, hostile.pitchScale)
        assertEquals(1f, quote(profile, "Mara replied, \"Wait.\"").pitchScale)
        assertEquals(1f, quote(profile, "Arin replied, \"Wait.\"").pitchScale)
        assertEquals(1f, VoiceCastPlanner(profile).plan("Vale watched the road.").utterances.single().pitchScale)
    }
    @Test fun explicitWarmAndHostileTagsChangeDeliveryWithoutChangingIdentity() {
        val warm = quote(profile, "\"Thank you,\" Vale replied warmly.")
        assertEquals("opponent", warm.voice); assertEquals(1f, warm.pitchScale)
        val harsh = quote(profile, "Mara mockingly said, \"Wait.\"")
        assertEquals("teacher", harsh.voice); assertEquals(0.92f, harsh.pitchScale)
    }
    @Test fun manualDeliveryOverridesBeatSceneCues() {
        val forced = profile.copy(characters = listOf(opponent.copy(delivery = CharacterDelivery.NATURAL), teacher.copy(delivery = CharacterDelivery.LOWER)))
        assertEquals(1f, quote(forced, "Vale mockingly said, \"Wait.\"").pitchScale)
        assertEquals(0.92f, quote(forced, "Mara kindly said, \"Wait.\"").pitchScale)
    }
    @Test fun openQuotesKeepTheirDeliveryAcrossWindows() {
        val planner = VoiceCastPlanner(profile)
        val first = planner.plan("Vale said, \"The road ")
        assertEquals(0.92f, first.next.pitchScale)
        val end = planner.plan("is quiet,\" Vale replied warmly.", first.next)
        assertEquals(0.92f, end.utterances.first().pitchScale)
        assertEquals(1f, end.next.pitchScale)
    }
    @Test fun directActionsMustTargetTheMcAndCannotComeFromQuotedClaimsOrNegation() {
        assertEquals(1, DeliveryCues.attitude("Vale thanked Arin.", "Vale", "Arin"))
        assertEquals(-1, DeliveryCues.attitude("Vale mocked Arin.", "Vale", "Arin"))
        assertEquals(0, DeliveryCues.attitude("Vale mocked somebody else. Arin waited.", "Vale", "Arin"))
        assertEquals(0, DeliveryCues.attitude("Vale did not mock Arin.", "Vale", "Arin"))
        val state = AdaptiveCast.prepare(profile, LearnedCast(1), AdaptiveVoices(), "chapter", 0, "Arin said, \"Vale thanked Arin.\"").next
        assertTrue(state.people.all { it.attitudeWindows == 0 })
    }
    @Test fun repeatedWarmActionsCanRetireALowerBaselineWithoutReassigningTheVoice() {
        var state = LearnedCast(windows = 1)
        fun advance(index: Int, text: String) { state = AdaptiveCast.prepare(profile, state, AdaptiveVoices(), "chapter", index, text).next }
        advance(0, "Vale thanked Arin.")
        var active = AdaptiveCast.prepare(profile, state, AdaptiveVoices(), "chapter", 1, "A quiet road.").cast
        assertEquals(0.92f, quote(active, "Vale said, \"Wait.\"").pitchScale)
        advance(1, "Vale helped Arin.")
        active = AdaptiveCast.prepare(profile, state, AdaptiveVoices(), "chapter", 2, "A quiet road.").cast
        assertEquals("opponent", quote(active, "Vale said, \"Wait.\"").voice)
        assertEquals(1f, quote(active, "Vale said, \"Wait.\"").pitchScale)
        advance(2, "Vale mocked Arin.")
        advance(3, "Vale threatened Arin.")
        active = AdaptiveCast.prepare(profile, state, AdaptiveVoices(), "chapter", 4, "A quiet road.").cast
        assertEquals(0.92f, quote(active, "Vale said, \"Wait.\"").pitchScale)
    }
    @Test fun replayAndLookbehindCannotCountTheSameActionTwice() {
        val first = AdaptiveCast.prepare(profile, LearnedCast(1), AdaptiveVoices(), "chapter", 0, "Vale thanked Arin.").next
        assertEquals(first, AdaptiveCast.prepare(profile, first, AdaptiveVoices(), "chapter", 0, "Vale thanked Arin.").next)
        val next = AdaptiveCast.prepare(profile, first, AdaptiveVoices(), "chapter", 17, "A quiet road.", DialogueState(lead = "Vale thanked Arin.")).next
        assertEquals(1, next.people.first { it.name == "Vale" }.attitudeWindows)
    }
    @Test fun configuredAliasesContributeAttitudeWithoutCreatingAnotherCharacter() {
        val aliased = profile.copy(characters = listOf(opponent.copy(aliases = "The Warden")))
        val first = AdaptiveCast.prepare(aliased, LearnedCast(1), AdaptiveVoices(), "chapter", 0, "The Warden thanked Arin.").next
        val next = AdaptiveCast.prepare(aliased, first, AdaptiveVoices(), "chapter", 20, "Vale helped Arin.").next
        assertEquals(1, next.people.size)
        assertEquals("Vale", next.people.single().name)
        assertEquals(2, next.people.single().attitudeWindows)
        assertEquals(0, next.people.single().attitudeScore)
    }
    @Test fun vocalActionTagsSupportTheRegisteredCastWithoutTreatingWalkingAsSpeech() {
        assertEquals("teacher", quote(profile, "\"Ready,\" Mara smiled.").voice)
        assertEquals("opponent", quote(profile, "\"Stop,\" Vale roared.").voice)
        assertEquals("", quote(profile, "\"Wait.\" Mara walked away.").voice)
    }
}
