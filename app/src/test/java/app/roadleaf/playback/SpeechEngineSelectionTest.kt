package app.roadleaf.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechEngineSelectionTest {
    @Test fun userInstalledEngineWorksWithoutSystemDefault() {
        assertEquals("offline", SpeechEngineSelection.select("", null, listOf("offline")))
    }
    @Test fun validSystemDefaultIsPreserved() {
        assertEquals("preferred", SpeechEngineSelection.select("", "preferred", listOf("other", "preferred")))
    }
    @Test fun explicitSelectionWins() {
        assertEquals("other", SpeechEngineSelection.select("other", "preferred", listOf("other", "preferred")))
    }
    @Test(expected = IllegalArgumentException::class) fun removedSelectionDoesNotSilentlyChangeNarrator() {
        SpeechEngineSelection.select("removed", "preferred", listOf("preferred"))
    }
    @Test(expected = IllegalArgumentException::class) fun missingEngineGivesSetupError() {
        SpeechEngineSelection.select("", null, emptyList())
    }
}
