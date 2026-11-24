package app.roadleaf.playback

import org.junit.Assert.*
import org.junit.Test

class RoadleafNativeTest {
    @Test fun numericVoicesRoundTripAndRejectInvalidSources() {
        for (code in listOf("F01", "M15", RoadleafNativeSpeech.EXAMPLE, "M:[(1/0),0,0,0]|[2,10,12]")) {
            val entry = requireNotNull(RoadleafNativeSpeech.parse(code))
            assertEquals(entry, RoadleafNativeSpeech.parse(entry.id))
        }
        for (code in listOf("F00", "M16", "F:[(16/1),10,0,0]|[0,5,4]", "F:[1,0,0,0]|[-1,5,4]")) {
            assertNull(RoadleafNativeSpeech.parse(code))
        }
    }

    @Test fun voicesRenderDistinctSpeechAndKeepSentenceBreaks() {
        val text = "Wait here. Can you hear the bell?"
        val groups = RoadleafEnglish.groups(text)
        assertTrue(groups.any { it.breakMark == '.' })
        assertTrue(groups.any { it.breakMark == '?' })
        val female = RoadleafFormant.synthesize(text, requireNotNull(RoadleafNativeSpeech.parse("F01")).voice)
        val male = RoadleafFormant.synthesize(text, requireNotNull(RoadleafNativeSpeech.parse("M01")).voice)
        assertTrue(female.size > RoadleafFormant.SAMPLE_RATE && female.any { it > 100 })
        assertFalse(female.contentEquals(male))
    }
}
