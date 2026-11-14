package app.roadleaf

import app.roadleaf.browser.BrowserStory
import app.roadleaf.browser.WebAppWindowGate
import app.roadleaf.browser.BrowserTextHandoff
import app.roadleaf.browser.BrowserStoryMatch
import org.junit.Assert.*
import org.junit.Test

class BrowserStoryTest {
    @Test fun browserIdsProtectConfiguredProfilesAcrossTabs() {
        val first = "https://novelai.net/stories?id=story-one"
        assertTrue(BrowserStory.sameStory(first, "novelai.net/stories?id=story-one"))
        assertTrue(BrowserStory.sameStory(first, "https://www.novelai.net/stories/story-one"))
        assertFalse(BrowserStory.sameStory(first, "https://novelai.net/stories?id=story-two"))
        assertFalse(BrowserStory.sameStory(first, "https://other.example/stories?id=story-one"))
        assertFalse(BrowserStory.sameStory("https://novelai.net/stories", "https://novelai.net/stories"))
    }
    @Test fun highlightsSupportBothDirectionsAndRejectStaleCarets() {
        val text = "First line.\r\nSecond line."
        assertEquals(13, BrowserStory.selectionOffset(text, 13, 19))
        assertEquals(13, BrowserStory.selectionOffset(text, 19, 13))
        assertEquals(12, BrowserStory.offsetOf(text, 13))
        assertNull(BrowserStory.selectionOffset(text, 13, 13))
        assertNull(BrowserStory.selectionOffset(text, -1, 13))
        assertNull(BrowserStory.selectionOffset(text, 13, 100))
    }
    @Test fun linkedStoryMustMatchSavedTextRatherThanLastBookOrCharacterNames() {
        val saved = "An original traveller crossed a quiet valley and met a guide.\n"
        val other = "A different tale has the same guide but begins in another village.\n"
        assertEquals("one", BrowserStoryMatch.unique(saved + "A new complete paragraph.\n", listOf("one" to saved, "two" to other)))
        assertNull(BrowserStoryMatch.unique(other, listOf("one" to saved)))
        assertNull(BrowserStoryMatch.unique(saved.replace("quiet", "noisy"), listOf("one" to saved)))
        assertNull(BrowserStoryMatch.unique(saved.take(30), listOf("one" to saved)))
    }
    @Test fun duplicateSavedSnapshotsDoNotGuessWhichCollectionToApply() {
        val saved = "An original traveller crossed a quiet valley and met a guide.\n"
        assertNull(BrowserStoryMatch.unique(saved, listOf("one" to saved, "two" to saved)))
        assertNull(BrowserStoryMatch.unique(saved, listOf("one" to "")))
        assertEquals("one", BrowserStoryMatch.unique(saved, listOf("one" to saved, "one" to saved)))
    }
    @Test fun verifiedNovelAiOriginOnly() {
        listOf("novelai.net", "novelai.net/stories?id=story-one", "https://novelai.net/stories", "https://www.novelai.net/stories/one").forEach { assertTrue(it, BrowserStory.novelAiAddress(it)) }
        listOf("http://novelai.net/stories", "novelai.net.attacker.test/stories", "https://novelai.net@attacker.test/stories", "https://attacker.test/novelai.net", "https://novelai.net:444/stories", "https://novelai.net/login", "NovelAI story", "https://novelai.net/stories extra").forEach { assertFalse(it, BrowserStory.novelAiAddress(it)) }
    }
    @Test fun directBookmarkRequiresAnIndividualNovelAiStory() {
        assertTrue(BrowserStory.storyAddress("https://novelai.net/stories/a-story-id"))
        assertTrue(BrowserStory.storyAddress("https://novelai.net/stories?id=11111111-2222-3333-4444-555555555555"))
        assertFalse(BrowserStory.storyAddress("https://novelai.net/stories"))
        assertFalse(BrowserStory.storyAddress("https://novelai.net/stories?id="))
        assertFalse(BrowserStory.storyAddress("https://other.example/stories/a-story-id"))
    }
    @Test fun editorOnlyNeverPasswordOrNativeBrowserSearch() {
        val text = "An original traveller crossed the quiet valley toward a distant village."
        assertEquals(text, BrowserStory.editorText(text, true, false, true))
        listOf(Triple(false, false, true), Triple(true, true, true), Triple(true, false, false)).forEach { (editable, password, web) ->
            assertThrows(IllegalArgumentException::class.java) { BrowserStory.editorText(text, editable, password, web) }
        }
    }
    @Test fun unfinishedGenerationWaitsWithoutDuplicateParagraphs() {
        val old = "First complete paragraph.\n"
        assertNull(BrowserStory.completedText(old + "The next paragraph is still streaming", old))
        val next = old + "The next paragraph is now complete.\n"
        assertEquals(next, BrowserStory.completedText(next + "A third begins", old))
        assertNull(BrowserStory.completedText(next + "A third continues", next))
        assertEquals(next + "A third finishes.\n", BrowserStory.completedText(next + "A third finishes.\n", next))
    }
    @Test fun streamMayExtendManuallyReadFinalParagraph() {
        val old = "First paragraph.\nA final paragraph"
        val next = old + " continues naturally.\nMore is streaming"
        assertEquals(old + " continues naturally.\n", BrowserStory.completedText(next, old))
    }
    @Test fun switchingStoriesOrEditingEarlierTextStopsFollow() {
        val old = "The original story began beside a quiet river.\n"
        assertThrows(IllegalArgumentException::class.java) { BrowserStory.completedText("A different story began.\n", old) }
        assertThrows(IllegalArgumentException::class.java) { BrowserStory.completedText(old.replace("quiet", "noisy"), old) }
        assertThrows(IllegalArgumentException::class.java) { BrowserStory.completedText(old.take(20), old) }
    }
    @Test fun finishedPrefixCanBeCapturedDuringUninterruptedStreaming() {
        val old = "An accepted first paragraph.\n"
        val ready = old + "A complete new paragraph.\n"
        assertEquals(ready, BrowserStory.stableCompletedText(ready + "The next par", ready + "The next paragraph keeps growing", old))
        assertNull(BrowserStory.stableCompletedText(ready + "The next", old + "A rewritten new paragraph.\nThe next", old))
        assertThrows(IllegalArgumentException::class.java) { BrowserStory.stableCompletedText(ready, "A different story now appears.\n", old) }
    }
    @Test fun sizeBoundsAndLineEndingsProtectCapture() {
        assertThrows(IllegalArgumentException::class.java) { BrowserStory.editorText("short", true, false, true) }
        assertThrows(IllegalArgumentException::class.java) { BrowserStory.editorText("a".repeat(BrowserStory.MAX_TEXT + 1), true, false, true) }
        assertEquals("A".repeat(40) + "\nB\nC", BrowserStory.editorText("A".repeat(40) + "\r\nB\rC", true, false, true))
    }
    @Test fun webAppRequiresFreshExplicitLaunchAndSameWindow() {
        val gate = WebAppWindowGate()
        val webApp = "org.chromium.chrome.browser.webapps.SameTaskWebApkActivity"
        gate.event(webApp, 20, 20_000, 1)
        assertFalse(gate.allows(20))
        gate.event(webApp, 20, 20_000, 19_500)
        assertTrue(gate.allows(20)); assertFalse(gate.allows(21))
        gate.event("android.widget.FrameLayout", 20, 21_000, 19_500)
        assertTrue(gate.allows(20))
        gate.event("com.google.android.apps.chrome.Main", 20, 21_500, 19_500)
        assertFalse(gate.allows(20))
        gate.event(webApp, 21, 22_000, 21_500)
        assertTrue(gate.allows(21))
        gate.event("org.chromium.chrome.browser.customtabs.CustomTabActivity", 21, 22_500, 21_500)
        assertFalse(gate.allows(21))
        gate.event(webApp, 22, 24_000, 23_500); gate.reset(); assertFalse(gate.allows(22))
    }
    @Test fun largeStoryTransferIsOneShotAndBoundToItsSession() {
        val handoff = BrowserTextHandoff()
        val text = "An original story paragraph.\n".repeat(12_000)
        val id = handoff.put("session-a", text)
        assertNull(handoff.take(id, "session-b")); assertNull(handoff.take("other", "session-a"))
        assertEquals(text, handoff.take(id, "session-a")); assertNull(handoff.take(id, "session-a"))
        val old = handoff.put("a", text); val current = handoff.put("a", text)
        assertNull(handoff.take(old, "a")); assertEquals(text, handoff.take(current, "a"))
        val cleared = handoff.put("a", text); handoff.clear(); assertNull(handoff.take(cleared, "a"))
    }
}
