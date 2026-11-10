package app.roadleaf

import app.roadleaf.browser.BrowserContinue
import app.roadleaf.browser.BrowserContinue.Next
import app.roadleaf.browser.WebBookOpening
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserContinueTest {
    private val one = "https://tales.example/book/chapter-1"
    private val two = "https://tales.example/book/chapter-2"

    @Test fun playAfterAChapterReadsThePageYouMovedTo() {
        assertEquals(Next.READ_SHOWN, BrowserContinue.after(ended = one, shown = two))
    }

    @Test fun webBooksOpenWhereYouChoseOrAskFirst() {
        val installed = listOf("com.android.chrome", "org.mozilla.firefox")
        assertEquals(WebBookOpening.Target.Ask, WebBookOpening.target(null, installed))
        assertEquals(WebBookOpening.Target.Reader, WebBookOpening.target(WebBookOpening.READER, installed))
        assertEquals(WebBookOpening.Target.Browser("com.android.chrome"), WebBookOpening.target("com.android.chrome", installed))
        // A chosen browser that was uninstalled asks again rather than failing.
        assertEquals(WebBookOpening.Target.Ask, WebBookOpening.target("com.brave.browser", installed))
    }

    @Test fun stillOnTheFinishedPageOrNoBrowserAsksYouToMoveOn() {
        // Roadleaf never turns pages: on the finished page, or with no browser on screen, it says what to do.
        assertEquals(Next.SAME_PAGE, BrowserContinue.after(ended = one, shown = one))
        assertEquals(Next.NO_PAGE, BrowserContinue.after(ended = one, shown = null))
    }
}
