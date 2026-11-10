package app.roadleaf

import app.roadleaf.browser.BrowserStory
import app.roadleaf.browser.ReadPlaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserResumeTest {
    private val story = "The ferry left at dawn, and the harbour lights faded one by one behind it.\n" +
        "By noon the island was a green line on the horizon, and the gulls had stopped following.\n"
    private val later = story + "In the evening a new chapter began, written after the reader had stopped.\n"

    @Test fun anUnsavedReadingIsFoundAgainAfterMoreTextIsWritten() {
        val stop = story.indexOf("By noon")
        val places = ReadPlaces.update(emptyList(), story, stop, now = 1)
        assertEquals(stop, ReadPlaces.find(places, later))
        assertEquals(stop, ReadPlaces.find(places, story))
    }

    @Test fun anEditBeforeThePlaceOrAnotherStoryFindsNothing() {
        val places = ReadPlaces.update(emptyList(), story, story.indexOf("By noon"), now = 1)
        assertNull(ReadPlaces.find(places, later.replace("ferry", "barge")))
        assertNull(ReadPlaces.find(places, "A completely different story that begins somewhere else entirely, far from any harbour."))
        assertNull(ReadPlaces.find(places, story.take(30)))
    }

    @Test fun eachStoryKeepsOnlyItsLatestPlaceAndNoText() {
        var places = ReadPlaces.update(emptyList(), story, story.indexOf("By noon"), now = 1)
        places = ReadPlaces.update(places, later, later.indexOf("In the evening"), now = 2)
        assertEquals(1, places.size)
        assertEquals(later.indexOf("In the evening"), ReadPlaces.find(places, later + "More.\n"))
        places = ReadPlaces.update(places, later, story.indexOf("gulls"), now = 3)
        assertEquals(story.indexOf("gulls"), ReadPlaces.find(places, later))
        val saved = ReadPlaces.encode(places)
        assertFalse(saved.contains("ferry") || saved.contains("harbour"))
        assertEquals(places, ReadPlaces.decode(saved))
    }

    @Test fun veryEarlyPlacesAreNotKeptAndTheListIsBounded() {
        assertEquals(emptyList<ReadPlaces.Place>(), ReadPlaces.update(emptyList(), story, 10, now = 1))
        var places = emptyList<ReadPlaces.Place>()
        repeat(30) { places = ReadPlaces.update(places, "Story number $it. " + story, 60, now = it.toLong()) }
        assertEquals(ReadPlaces.MAX, places.size)
    }

    @Test fun aCursorInTheEditorMapsToTheReadText() {
        val raw = "First line.\r\nSecond line with a word.\r\nThird."
        val read = raw.replace("\r\n", "\n")
        val caret = raw.indexOf("word")
        assertEquals(read.indexOf("word"), BrowserStory.offsetOf(raw, caret))
        assertEquals(read.length, BrowserStory.offsetOf(raw, raw.length + 5))
        assertEquals(0, BrowserStory.offsetOf(raw, -1))
    }
}
