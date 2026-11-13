package app.roadleaf

import app.roadleaf.playback.*
import org.junit.Assert.*
import org.junit.Test

class SceneReadAheadTest {
    private val cast = BookVoiceCast(
        adaptive = false,
        mainCharacter = "Cinder",
        mainVoice = "cinder-voice",
        narratorVoice = "neutral",
        storyVoice = "neutral",
        characters = listOf(CastCharacter("Vesper", role = CastRole.CUSTOM, voice = "vesper-voice"))
    )

    @Test fun explicitViewpointSelectsNarrationWithoutChangingCast() {
        val scene = "[POV: Vesper]\nVesper considered the warning. \u201cStay here,\u201d Cinder said."
        val choice = SceneReadAhead.prepare(cast, scene, DialogueState())
        assertEquals("Vesper", choice.viewpoint)
        assertEquals("vesper-voice", choice.cast.storyVoice)
        assertEquals(cast.characters, choice.cast.characters)
        val spoken = SceneCast.plan(scene, cast, DialogueState())
        assertEquals(scene, spoken.utterances.joinToString("") { it.text })
    }

    @Test fun dialogueMentionDoesNotForceNarratorChange() {
        val text = "\"Vesper is waiting,\" Cinder said. \"We should go.\""
        assertEquals("", SceneReadAhead.prepare(cast, text, DialogueState()).viewpoint)
        assertEquals("neutral", SceneReadAhead.prepare(cast, text, DialogueState()).cast.storyVoice)
    }

    @Test fun namedThoughtCanMoveViewpoint() {
        val choice = SceneReadAhead.prepare(cast, "Vesper wondered whether she should leave.", DialogueState())
        assertEquals("Vesper", choice.viewpoint)
        assertEquals("vesper-voice", choice.cast.storyVoice)
    }

    @Test fun disabledViewpointFeatureKeepsOriginalNarrator() {
        val choice = SceneReadAhead.prepare(cast.copy(followViewpoint = false), "[POV: Vesper]", DialogueState())
        assertEquals("neutral", choice.cast.storyVoice)
    }

    @Test fun unrelatedBooksKeepTheirOwnNarrator() {
        val another = BookVoiceCast(narratorVoice = "other", storyVoice = "other", mainCharacter = "Rui", mainVoice = "rui")
        assertEquals("other", SceneReadAhead.prepare(another, "Vesper wondered why.", DialogueState()).cast.storyVoice)
    }
}
