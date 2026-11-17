package app.roadleaf

import app.roadleaf.playback.BookVoiceCast
import app.roadleaf.playback.CastCharacter
import app.roadleaf.playback.DialogueState
import app.roadleaf.playback.NarrationText
import app.roadleaf.playback.SceneCast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaggedThoughtTest {
    private val cast = BookVoiceCast(
        sceneOnly = true,
        narratorVoice = "narrator",
        characters = listOf(CastCharacter("Kira", voice = "kira"), CastCharacter("Captain Chris Voss", aliases = "Chris,Voss", voice = "voss"))
    )

    @Test fun labelledThoughtKeepsKirasVoiceWithoutReadingTheLabel() {
        val source = "💭 Kira's Thoughts: \"He is looking at me.\"\nShe turns away."
        val plan = SceneCast.plan(source, cast, DialogueState())
        assertEquals(source, plan.utterances.joinToString("") { it.text })
        val thought = plan.utterances.single { it.text.contains("He is looking at me") }
        assertEquals("kira", thought.voice)
        assertEquals(0.97f, thought.pitchScale)
        assertTrue(plan.utterances.any { it.voice == "kira" && it.text.contains("She turns away") })
        val spoken = plan.utterances.joinToString("") { NarrationText.english(it.text) }
        assertTrue(spoken.contains("He is looking at me"))
        assertTrue(!spoken.contains("Kira's Thoughts") && !spoken.contains("💭"))
    }

    @Test fun sceneNarrationFollowsExplicitThoughtViewpointUntilTheNextOne() {
        val kira = SceneCast.plan("💭 Kira's Thoughts: \"He is looking at me.\"", cast, DialogueState())
        val herScene = SceneCast.plan("She turns away and waits for him.", cast, kira.next)
        assertEquals("kira", herScene.utterances.single().voice)
        val chris = SceneCast.plan("💭 Chris's Thoughts: \"She is safe.\"", cast, herScene.next)
        val hisScene = SceneCast.plan("He watches the corridor.", cast, chris.next)
        assertEquals("voss", hisScene.utterances.single().voice)
        val newScene = SceneCast.plan("---\nAn empty corridor waits.", cast, hisScene.next)
        assertEquals("narrator", newScene.utterances.last().voice)
    }

    @Test fun shortCombatOrdersRetainEverySourceWord() {
        val lines = listOf(
            "Riker looks from the targeting solution to Davos.",
            "“Can we hold that line?”",
            "“While we’re docked. Thrust would spoil it.”",
            "“Main battery, fire on the empty cargo section. Small gun, hold.”"
        )
        var state = DialogueState()
        for (line in lines) {
            val plan = SceneCast.plan(line, cast, state)
            assertEquals(line, plan.utterances.joinToString("") { it.text })
            state = plan.next
        }
    }
}
