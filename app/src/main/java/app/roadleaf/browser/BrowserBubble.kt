package app.roadleaf.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.roadleaf.R
import app.roadleaf.browser.BubbleGeometry.Side

/**
 * Roadleaf's floating bubble over a browser, like a driver app's: a small round button at the edge of the screen
 * that never opens by itself. Tap it for the reading controls. Drag it anywhere and it settles on the nearer side.
 * Drag it onto the ✕ that appears at the bottom, or tap ✕ on its card, to close it until Roadleaf is opened again.
 * Closing hides the controls only; a reading in progress carries on.
 */
// RtlHardcoded: the bubble sits on a physical screen edge, whatever the language. ClickableViewAccessibility: a tap
// calls performClick, so TalkBack and other accessibility clicks reach the same click listener.
@SuppressLint("RtlHardcoded", "SetTextI18n", "ClickableViewAccessibility")
class BrowserBubble(
    private val context: Context,
    private val windows: WindowManager,
    private val type: Int,
    private val preferences: BrowserReaderPreferences,
    private val onClosed: () -> Unit = {},
) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val size = dp(56)
    private val margin = dp(6)
    private val slop = dp(8)
    private val targetSize = dp(64)
    private val targetFromBottom = dp(96)
    private val main = Handler(Looper.getMainLooper())
    private val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
    private val root = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var spot = preferences.bubbleSpot
    private var peeking = false
    private var lastPeek = ""
    private var lastPeekAt = 0L
    private val endPeek = Runnable { if (peeking) { peeking = false; render() } }
    private var target: FrameLayout? = null
    private var targetHot = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    /** The reading controls on the card; the browser reader fills it. */
    val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var expanded = false
        private set
    var showing = false
        private set

    /** The round button. */
    val bubble = FrameLayout(context).apply {
        background = oval(FILL, RING)
        contentDescription = "Roadleaf reading controls"
        isClickable = true
        addView(leaf(), FrameLayout.LayoutParams(size - dp(8), size - dp(8), Gravity.CENTER))
        // Posted, as Android does for clicks, so the card replaces the bubble after this touch is finished.
        setOnClickListener { main.post { expand() } }
    }

    /** A short note beside the bubble, such as "Reading … from top", that goes away by itself. */
    val note = TextView(context).apply {
        textSize = 13f; setTextColor(Color.WHITE); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(230)
        background = rounded(FILL, dp(12)); setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { expand() }
    }

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(FILL, dp(16)); setPadding(dp(10), dp(2), dp(4), dp(10))
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(leaf().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(28), dp(28)))
        header.addView(TextView(context).apply {
            text = "Roadleaf"; textSize = 15f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(headerButton("−", "Back to the bubble") { collapse() })
        header.addView(headerButton("✕", "Close Roadleaf's bubble") { close() })
        addView(header)
        addView(body)
    }

    init {
        bubble.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && dx * dx + dy * dy > slop * slop) {
                        dragging = true
                        if (peeking) { main.removeCallbacks(endPeek); peeking = false; root.removeView(note) }
                        showTarget()
                    }
                    if (dragging) {
                        val (width, height) = screen()
                        // On the right side the window's x counts from the right edge. Kept on screen, where Android keeps the window.
                        params.x = (startX + (if (spot.side == Side.LEFT) dx else -dx).toInt()).coerceIn(0, (width - size).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (height - size).coerceAtLeast(0))
                        if (showing) runCatching { windows.updateViewLayout(root, params) }
                        markTarget(overTarget())
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    dragging = false
                    val drop = overTarget()
                    hideTarget()
                    if (drop) close() else settle()
                } else view.performClick()
                MotionEvent.ACTION_CANCEL -> if (dragging) { dragging = false; hideTarget(); settle() }
            }
            true
        }
    }

    /** Shows the bubble, never the card. False if Android refused the window. */
    fun show(): Boolean {
        if (showing) return true
        spot = preferences.bubbleSpot
        expanded = false; peeking = false
        render()
        // The hidden ✕ target goes first, so the bubble is always drawn above it while dragged.
        addTarget()
        showing = runCatching { windows.addView(root, params) }.isSuccess
        if (!showing) removeTarget()
        return showing
    }

    fun hide() {
        main.removeCallbacks(endPeek)
        removeTarget()
        if (showing) runCatching { windows.removeView(root) }
        showing = false; dragging = false
    }

    /** Closes the bubble until Roadleaf is opened again. */
    fun close() {
        hide()
        preferences.bubbleClosed = true
        onClosed()
    }

    fun expand() {
        if (!showing) return
        main.removeCallbacks(endPeek)
        peeking = false; expanded = true
        render()
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        render()
    }

    /** A note beside the collapsed bubble for a few seconds. The same note is not repeated within a minute. */
    fun peek(text: String, millis: Long = 4500) {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160) ?: return
        if (!showing || expanded || dragging) return
        val now = SystemClock.elapsedRealtime()
        if (line == lastPeek && now - lastPeekAt < 60_000) return
        lastPeek = line; lastPeekAt = now
        note.text = line
        peeking = true
        render()
        main.removeCallbacks(endPeek)
        main.postDelayed(endPeek, millis)
    }

    private fun screen() = context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    private fun render() {
        val (width, height) = screen()
        params.gravity = Gravity.TOP or if (spot.side == Side.LEFT) Gravity.LEFT else Gravity.RIGHT
        params.x = margin
        val top = BubbleGeometry.top(spot, height, size, margin)
        // Views are swapped one at a time: removing the bubble while a finger is on it would cancel the drag.
        if (note.parent != null) root.removeView(note)
        if (expanded) {
            if (bubble.parent != null) root.removeView(bubble)
            val cardWidth = minOf(dp(320), width - 2 * margin)
            val layout = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (card.parent == null) root.addView(card, layout) else card.layoutParams = layout
            card.measure(View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            params.y = minOf(top, height - card.measuredHeight - margin).coerceAtLeast(margin)
        } else {
            if (card.parent != null) root.removeView(card)
            if (bubble.parent == null) root.addView(bubble, LinearLayout.LayoutParams(size, size))
            if (peeking && note.text.isNotEmpty()) {
                // The note sits on the inner side, so the bubble keeps its place at the edge.
                val layout = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                if (spot.side == Side.RIGHT) root.addView(note, 0, layout.apply { marginEnd = dp(6) })
                else root.addView(note, layout.apply { marginStart = dp(6) })
            }
            params.y = top
        }
        if (showing) runCatching { windows.updateViewLayout(root, params) }
    }

    /** The bubble's centre on screen while it is collapsed. */
    private fun center(): Pair<Int, Int> {
        val (width, _) = screen()
        val x = if (spot.side == Side.LEFT) params.x + size / 2 else width - params.x - size / 2
        return x to params.y + size / 2
    }

    private fun overTarget(): Boolean {
        val (width, height) = screen()
        val (x, y) = center()
        return BubbleGeometry.overClose(x, y, width, height, targetFromBottom, dp(64))
    }

    /** Keeps the bubble on the side nearer to where it was let go, at the height it was dropped. */
    private fun settle() {
        val (width, height) = screen()
        val (x, _) = center()
        spot = BubbleGeometry.Spot(BubbleGeometry.side(x, width), BubbleGeometry.height(params.y, height))
        preferences.bubbleSpot = spot
        render()
    }

    private val targetOuter = targetSize + dp(20)
    private val targetParams = WindowManager.LayoutParams(targetOuter, targetOuter, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }

    /** The ✕ drop target near the bottom of the screen: never touchable, hidden until a drag starts. */
    private fun addTarget() {
        if (target != null) return
        val frame = FrameLayout(context).apply {
            visibility = View.INVISIBLE
            addView(TextView(context).apply {
                text = "✕"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = oval(TARGET, TARGET_RING)
                contentDescription = "Drop here to close Roadleaf's bubble"
            }, FrameLayout.LayoutParams(targetSize, targetSize, Gravity.CENTER))
        }
        targetParams.y = screen().second - targetFromBottom - targetOuter / 2
        if (runCatching { windows.addView(frame, targetParams) }.isSuccess) { target = frame; targetHot = false }
    }

    private fun showTarget() {
        val frame = target ?: return
        // Placed again in case the screen turned since the bubble appeared.
        targetParams.y = screen().second - targetFromBottom - targetOuter / 2
        runCatching { windows.updateViewLayout(frame, targetParams) }
        frame.visibility = View.VISIBLE
    }

    private fun hideTarget() {
        markTarget(false)
        target?.visibility = View.INVISIBLE
    }

    private fun markTarget(hot: Boolean) {
        if (hot == targetHot) return
        targetHot = hot
        target?.getChildAt(0)?.apply {
            scaleX = if (hot) 1.25f else 1f; scaleY = scaleX
            background = oval(if (hot) TARGET_HOT else TARGET, TARGET_RING)
        }
    }

    private fun removeTarget() {
        target?.let { runCatching { windows.removeView(it) } }
        target = null; targetHot = false
    }

    private fun leaf() = ImageView(context).apply {
        setImageResource(R.drawable.ic_roadleaf); scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(LEAF_BACKGROUND) }
        clipToOutline = true
    }

    private fun headerButton(label: String, description: String, action: () -> Unit) = TextView(context).apply {
        text = label; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
        minWidth = dp(48); minHeight = dp(48); contentDescription = description
        isClickable = true; setOnClickListener { action() }
    }

    private fun oval(fill: Int, ring: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill); setStroke(dp(2), ring) }
    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply { cornerRadius = radius.toFloat(); setColor(fill) }

    private companion object {
        const val FILL = 0xF0193B2A.toInt()
        const val RING = 0xFF68B593.toInt()
        const val LEAF_BACKGROUND = 0xFF132924.toInt()
        const val TARGET = 0xCC1B1B1B.toInt()
        const val TARGET_HOT = 0xEEB3261E.toInt()
        const val TARGET_RING = 0x88FFFFFF.toInt()
    }
}
