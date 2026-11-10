package app.roadleaf

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import app.roadleaf.browser.BrowserBubble
import app.roadleaf.browser.BubbleGeometry
import java.util.concurrent.atomic.AtomicReference

/**
 * The browser bubble's own behaviour, hosted in Roadleaf's window. Over a browser the same bubble runs as an
 * accessibility overlay, which needs the owner's consent and is not switched on here.
 */
class BrowserBubbleChecks(private val instrumentation: Instrumentation, private val app: RoadleafApp, private val activity: Activity, private val report: MutableList<String>,
    /** With -e bubblePauseMs, holds each look (bubble, card, note, ✕ target) on screen for a screenshot. */
    private val pauseMs: Long = 0) {
    private fun look() { if (pauseMs > 0) Thread.sleep(pauseMs) }
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
    private fun idle() { instrumentation.waitForIdleSync(); Thread.sleep(200); instrumentation.waitForIdleSync() }
    private fun find(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index), description)?.let { return it }
        return null
    }
    private fun touch(view: View, action: Int, x: Float, y: Float, down: Long) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    /**
     * A finger pressed on the bubble's centre and moved by ([dx], [dy]) in small steps. Touches go through the
     * bubble's window like real ones, so anything that cancels a drag in progress shows up here.
     */
    private fun drag(bubble: BrowserBubble, dx: Float, dy: Float, beforeLettingGo: () -> Unit = {}) {
        val root = main { bubble.bubble.rootView }
        val x = main { bubble.bubble.left + bubble.bubble.width / 2f }
        val y = main { bubble.bubble.top + bubble.bubble.height / 2f }
        val down = SystemClock.uptimeMillis()
        main {
            touch(root, MotionEvent.ACTION_DOWN, x, y, down)
            for (step in 1..10) touch(root, MotionEvent.ACTION_MOVE, x + dx * step / 10, y + dy * step / 10, down)
        }
        beforeLettingGo()
        main { touch(root, MotionEvent.ACTION_UP, x + dx, y + dy, down) }
    }
    private fun tap(bubble: BrowserBubble) = drag(bubble, 0f, 0f)

    fun run() {
        val preferences = app.browserReader
        val savedSpot = preferences.bubbleSpot
        val savedClosed = preferences.bubbleClosed
        var closed = 0
        var made: BrowserBubble? = null
        try {
            preferences.bubbleSpot = BubbleGeometry.Spot(BubbleGeometry.Side.RIGHT, 0.3f)
            preferences.bubbleClosed = false
            val bubble = main {
                BrowserBubble(activity, activity.windowManager, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, preferences) { closed++ }
                    .also {
                        it.body.addView(TextView(activity).apply { text = "Tap a word in your story, then Read from here."; textSize = 12f; setTextColor(Color.WHITE) })
                        it.body.addView(Button(activity).apply { text = "From top" })
                    }
            }
            made = bubble
            check(main { bubble.show() }) { "Android refused the bubble window" }
            idle()
            check(main { bubble.showing && !bubble.expanded && bubble.bubble.isAttachedToWindow && !bubble.body.isAttachedToWindow }) { "The bubble must open collapsed" }
            look()
            report.add("PASS the browser bubble appears as a small round button and never opens its controls by itself")

            tap(bubble); idle()
            check(main { bubble.expanded && bubble.body.isAttachedToWindow && !bubble.bubble.isAttachedToWindow }) { "A tap must open the controls card" }
            look()
            main { requireNotNull(find(bubble.body.rootView, "Back to the bubble")).performClick() }; idle()
            check(main { !bubble.expanded && bubble.bubble.isAttachedToWindow && !bubble.body.isAttachedToWindow }) { "− must return to the bubble" }
            report.add("PASS a tap opens the controls card, and − folds it back into the bubble")

            main { bubble.peek("Reading your NovelAI story · from top.\nNot saved in Library.", millis = 700) }; idle()
            check(main { bubble.note.isAttachedToWindow && bubble.note.text.toString() == "Reading your NovelAI story · from top." }) { "The note must show its first line" }
            Thread.sleep(1200); idle()
            check(main { !bubble.note.isAttachedToWindow }) { "The note must go away by itself" }
            main { bubble.peek("Reading your NovelAI story · from top.", millis = 700) }; idle()
            check(main { !bubble.note.isAttachedToWindow }) { "The same note must not repeat within a minute" }
            report.add("PASS a short note shows beside the bubble, goes away by itself and doesn't repeat")

            val metrics = activity.resources.displayMetrics
            val width = metrics.widthPixels.toFloat()
            val height = metrics.heightPixels.toFloat()
            fun px(value: Int) = (value * metrics.density).toInt()
            // Start a drag while a note shows beside the bubble: the note goes, the drag carries on.
            main { bubble.peek("Not playing yet: preparing the narrator", millis = 10_000) }; idle()
            check(main { bubble.note.isAttachedToWindow }) { "The second note must show" }
            look()
            val startX = width - px(6) - px(56) / 2f
            val startY = BubbleGeometry.top(BubbleGeometry.Spot(BubbleGeometry.Side.RIGHT, 0.3f), metrics.heightPixels, px(56), px(6)) + px(56) / 2f
            drag(bubble, width * 0.2f - startX, height * 0.5f - startY); idle()
            val settled = preferences.bubbleSpot
            check(settled.side == BubbleGeometry.Side.LEFT && settled.height in 0.4f..0.55f) { "The dragged bubble settled at $settled" }
            check(closed == 0 && main { bubble.showing && !bubble.expanded && !bubble.note.isAttachedToWindow }) { "A drag must not close or open the bubble, and the note must go" }
            report.add("PASS a dragged bubble settles on the nearer side and remembers where it was left, even when dragged while a note shows")

            // Drop it on the ✕ target, centred 96 dp above the bottom of the screen.
            val centerX = px(6) + px(56) / 2f
            val centerY = BubbleGeometry.top(settled, metrics.heightPixels, px(56), px(6)) + px(56) / 2f
            drag(bubble, width / 2 - centerX, height - px(96) - centerY) { idle(); look() }; idle()
            check(closed == 1 && preferences.bubbleClosed && main { !bubble.showing && !bubble.bubble.isAttachedToWindow }) { "Dropping the bubble on ✕ must close it" }
            report.add("PASS dropping the bubble on ✕ closes it and keeps it closed")

            val setup = instrumentation.startActivitySync(Intent(instrumentation.targetContext, NovelAiBrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            idle()
            check(!preferences.bubbleClosed) { "Opening Roadleaf must bring the bubble back" }
            main { setup.finish() }
            idle()
            report.add("PASS opening Roadleaf brings a closed bubble back")

            check(main { bubble.show() }) { "Android refused the bubble window again" }
            idle()
            tap(bubble); idle()
            main { requireNotNull(find(bubble.body.rootView, "Close Roadleaf's bubble")).performClick() }; idle()
            check(closed == 2 && preferences.bubbleClosed && main { !bubble.showing }) { "✕ on the card must close the bubble" }
            report.add("PASS ✕ on the controls card closes the bubble too")
        } finally {
            made?.let { bubble -> main { bubble.hide() } }
            preferences.bubbleSpot = savedSpot
            preferences.bubbleClosed = savedClosed
        }
    }
}
