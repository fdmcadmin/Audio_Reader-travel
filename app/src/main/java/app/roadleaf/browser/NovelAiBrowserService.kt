package app.roadleaf.browser

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import app.roadleaf.Features
import app.roadleaf.RoadleafApp
import app.roadleaf.data.Book
import app.roadleaf.playback.NarrationService
import app.roadleaf.playback.ParagraphResume
import app.roadleaf.playback.StartPoint
import app.roadleaf.playback.awaitCommand
import app.roadleaf.sources.LiveWebPage
import app.roadleaf.sources.LiveWebPages
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Opt-in local screen reader for the owner's own browsers: the NovelAI story editor, or the page shown
 * when the owner taps Read page. No gestures, typing, passwords, clipboard, network or cookie access.
 */
@UnstableApi
@Suppress("DEPRECATION", "SetTextI18n")
class NovelAiBrowserService : AccessibilityService() {
    private enum class Mode { NOVELAI, PAGE }
    private val app get() = application as RoadleafApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connection: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    // The floating bubble; its card holds the controls. It never opens by itself.
    private var bubble: BrowserBubble? = null
    private var overlayMode: Mode? = null
    private var hint: TextView? = null
    private var profileChoice: CheckBox? = null
    private val actions = mutableListOf<Button>()
    private var selectedEditor: AccessibilityNodeInfo? = null
    private var acceptedText = ""
    private var watchedToken: String? = null
    private var internalToken: String? = null
    private val webAppGate = WebAppWindowGate()
    private var webAppPackage: String? = null
    private var webAppLaunchMs = 0L
    private var selectedAddress: String? = null
    private var probe: Job? = null
    private var watch: Job? = null
    private var capture: Job? = null
    private var changed = false
    private var browsers: Set<String> = BrowserApps.known.keys
    private var activeBrowser: String? = null
    // The page most recently read from a browser tab; following continues with its next chapter.
    private var tabPage: LiveWebPage? = null
    private val windows get() = getSystemService(WINDOW_SERVICE) as WindowManager
    private val browser get() = app.browserSession.value?.browserPackage ?: activeBrowser ?: "com.android.chrome"

    override fun onServiceConnected() {
        browsers = BrowserApps.packages(this)
        message("Ready in your browser. Open a NovelAI story or any chapter page, then tap Roadleaf's bubble. No saving is required.")
        scope.launch {
            app.browserSession.distinctUntilChangedBy { it?.token }.collect { session ->
                val internal = session != null && session.token == internalToken
                internalToken = null
                if (!internal) { probe?.cancel(); changed = false; capture?.cancel(); clearEditor(); hideOverlay() }
                val newWebApp = session?.webAppPackage
                if (newWebApp != null && (newWebApp != webAppPackage || session.webAppLaunchMs != webAppLaunchMs)) {
                    webAppGate.reset()
                    webAppPackage = newWebApp.takeIf { pkg -> NovelAiWebApp.installed(this@NovelAiBrowserService).any { it.packageName == pkg } }
                    webAppLaunchMs = session.webAppLaunchMs
                }
                val oldToken = watchedToken; watchedToken = session?.token
                if (oldToken != null && oldToken != session?.token)
                    runCatching { controller?.awaitCommand(NarrationService.BROWSER_STOP, Bundle().apply { putString("token", oldToken) }) }
                serviceInfo = serviceInfo.apply {
                    packageNames = browsers.toTypedArray()
                    eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                }
            }
        }
        // A finished tab chapter continues once the owner has opened its next chapter in the browser.
        scope.launch { app.webChapterEnded.collect { ended -> if (ended != null && ended == tabPage?.url) followTab() } }
        // Play after a chapter read from the browser ended: read the page the browser shows now, once you've moved on.
        scope.launch { app.browserPlayRequest.drop(1).collect { continueOnScreen() } }
        inspect()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in browsers) return
        activeBrowser = pkg
        if (webAppPackage != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            webAppGate.event(event.className?.toString().orEmpty(), event.windowId, SystemClock.elapsedRealtime(), webAppLaunchMs)
        changed = true
        if (probe?.isActive != true) probe = scope.launch {
            while (changed && isActive) { changed = false; delay(1200); inspect(); follow(); followTab() }
        }
    }
    private fun browserRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() !in browsers) { root.recycle(); return null }
        return root
    }
    private fun verifiedRoot(): AccessibilityNodeInfo? {
        val root = browserRoot() ?: return null
        val address = BrowserEditorNodes.address(root)
        val allowed = if (address != null) BrowserStory.novelAiAddress(address) else webAppPackage != null && webAppGate.allows(root.windowId)
        if (!allowed) { root.recycle(); return null }
        return root
    }
    /** Any other secure page whose full address the browser shows. */
    private fun pageAddress(): String? {
        val root = browserRoot() ?: return null
        try {
            val address = BrowserEditorNodes.address(root)
            if (address != null && BrowserStory.novelAiAddress(address)) return null
            return BrowserPages.pageUrl(address)
        } finally { root.recycle() }
    }
    private fun inspect() {
        // A chapter Roadleaf opened in this browser (Continue reading): read it once it shows and has settled.
        app.browserReader.autoRead.value?.let { pending ->
            if (SystemClock.elapsedRealtime() > pending.untilMs) app.browserReader.autoRead.value = null
            else if (capture?.isActive != true && pageAddress() == pending.url) {
                app.browserReader.autoRead.value = null
                scope.launch { delay(2_500); if (pageAddress() == pending.url) readPage(fresh = pending.fresh) }
            }
        }
        // Closed with ✕: hidden until Roadleaf is opened again. A reading in progress carries on.
        if (app.browserReader.bubbleClosed) { hideOverlay(); return }
        val novelAi = verifiedRoot()?.let { it.recycle(); true } ?: false
        val mode = when {
            novelAi -> Mode.NOVELAI
            app.browserReader.anyPage && pageAddress() != null -> Mode.PAGE
            else -> null
        }
        if (mode == null) { hideOverlay(); return }
        if (bubble != null && overlayMode == mode) return
        hideOverlay()
        if (mode == Mode.NOVELAI) showNovelAiControls() else showPageControls()
        // Check only while the bubble is shown, so it disappears when you leave the page; idle costs nothing.
        watch?.cancel()
        watch = scope.launch { while (isActive && bubble != null) { delay(1500); inspect() } }
    }
    /** A collapsed bubble whose card the caller fills with controls. */
    private fun controls(): Pair<BrowserBubble, LinearLayout> {
        val made = BrowserBubble(this, windows, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, app.browserReader) {
            hideOverlay()
            message("Bubble closed. Reading carries on. Open Roadleaf to bring the bubble back.", peek = false)
            Toast.makeText(this, "Roadleaf bubble closed. Open Roadleaf to bring it back.", Toast.LENGTH_LONG).show()
        }
        return made to made.body
    }
    private fun actionRow(row: LinearLayout, vararg choices: Pair<String, () -> Unit>) {
        val buttons = LinearLayout(this)
        choices.forEach { (label, action) -> buttons.addView(Button(this).apply {
            text = label; textSize = 12f; isEnabled = label == "Stop" || label == "Pause / Play" || capture?.isActive != true
            contentDescription = "Roadleaf: $label"
            setOnClickListener { action() }; if (label != "Stop" && label != "Pause / Play") actions.add(this)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        row.addView(buttons)
    }
    private fun place(made: BrowserBubble, mode: Mode) {
        if (made.show()) { bubble = made; overlayMode = mode }
        else message("Could not show reader controls. Re-enable Roadleaf's browser accessibility connection.")
    }
    private fun showNovelAiControls() {
        val (made, row) = controls()
        hint = TextView(this).apply {
            text = "Select story text to start there, or start at the first visible word. Resume continues where you stopped."
            textSize = 12f; maxLines = 4; setTextColor(android.graphics.Color.WHITE)
        }
        row.addView(hint)
        actionRow(row, "From top" to { beginRead(BrowserStart.TOP) }, "Resume" to { beginRead(BrowserStart.RESUME) })
        actionRow(row, "Read selection" to { beginRead(BrowserStart.AT, fromSelection = true) },
            "Visible top" to { beginRead(BrowserStart.AT, fromVisibleTop = true) })
        actionRow(row, "Check collection" to { beginRead(BrowserStart.TOP, checkOnly = true) })
        profileChoice = CheckBox(this).apply {
            text = "Use linked profiles"; textSize = 12f; setTextColor(android.graphics.Color.WHITE)
            isChecked = app.browserReader.useProfiles
            setOnCheckedChangeListener { _, value -> app.browserReader.useProfiles = value }
        }.also(row::addView)
        actionRow(row, "Pause / Play" to { scope.launch {
            if (app.browserSession.value == null || acceptedText.isEmpty()) { message("Choose From top or Resume for this story first."); return@launch }
            runCatching { activeController().let {
                require(it.currentMediaItem?.mediaMetadata?.extras?.getString("bookId") == app.browserSession.value?.bookId) { "The reader changed books. Choose a read button for this story." }
                if (it.playWhenReady) it.pause() else {
                    val root = verifiedRoot() ?: error("Open the verified story page first.")
                    val address = BrowserEditorNodes.address(root); root.recycle()
                    require(selectedAddress == null || selectedAddress == address) { "Story address changed. Choose a read button for this story." }
                    val editor = selectedEditor ?: error("Select the story-writing box again.")
                    require(editor.refresh() && BrowserEditorNodes.text(editor).startsWith(acceptedText)) { "The story changed. Choose From top." }
                    it.play()
                }
            } }
                .onFailure { message(it.message ?: "Cannot reach the reader.") }
        } }, "Stop" to { app.browserSession.value = null; message("Stopped. Tap a read button to start again. Your saved library is unchanged.") })
        place(made, Mode.NOVELAI)
    }
    private fun showPageControls() {
        val (made, row) = controls()
        hint = TextView(this).apply {
            text = "This page in ${BrowserApps.label(activeBrowser)}. Read page starts at the top. Your sign-in stays in your browser."
            textSize = 12f; maxLines = 4; setTextColor(android.graphics.Color.WHITE)
        }
        row.addView(hint)
        actionRow(row, "Read page" to { readPage(fresh = true) }, "Resume" to { readPage(fresh = false) })
        CheckBox(this).apply {
            text = "Continue when I open the next chapter"; textSize = 12f; setTextColor(android.graphics.Color.WHITE)
            isChecked = app.browserReader.followPages
            setOnCheckedChangeListener { _, value -> app.browserReader.followPages = value }
        }.also(row::addView)
        actionRow(row, "Pause / Play" to { scope.launch {
            runCatching { activeController().let { player ->
                when {
                    // Nothing read yet: Play reads the page on screen.
                    player.currentMediaItem?.mediaMetadata?.extras?.getString("webUrl") == null -> readPage(fresh = false)
                    player.playWhenReady -> player.pause()
                    // After a finished chapter, Play reads the page you moved to (the narration service asks for it).
                    else -> player.play()
                }
            } }.onFailure { message(it.message ?: "Cannot reach the reader.") }
        } }, "Stop" to { tabPage = null; controller?.pause(); message("Stopped. Tap Read page to start again.") })
        place(made, Mode.PAGE)
    }
    /** Reads the page the browser shows now, through the same narration as Roadleaf's own reader. */
    private fun readPage(fresh: Boolean, expectedUrl: String? = null) {
        if (capture?.isActive == true) return
        capture = scope.launch {
            actions.forEach { it.isEnabled = false }
            try {
                val url = pageAddress() ?: error("This browser shows only the site name. Use Share → Roadleaf for this page, or turn on full addresses in the browser.")
                if (expectedUrl != null && url != expectedUrl) return@launch
                val root = browserRoot() ?: error("Open the page in your browser first.")
                val snapshot = try { withContext(Dispatchers.Default) { BrowserPageNodes.capture(root) } } finally { root.recycle() }
                val page = withContext(Dispatchers.Default) { LiveWebPages.parse(BrowserPageHtml.build(snapshot.title, snapshot.nodes), url) }
                val player = activeController()
                val selection = player.awaitCommand(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", page.url); putBoolean("fresh", fresh) }).extras.getLong("selection")
                player.awaitCommand(NarrationService.WEB_PAGE, Bundle().apply {
                    putString("url", page.url); putString("title", page.title); putString("bookTitle", page.bookTitle)
                    putString("text", page.text); putString("next", page.nextUrl); putLong("request", 0); putLong("selection", selection)
                    putBoolean("autoNext", false); putString("via", "tab")
                })
                tabPage = page
                bubble?.collapse()
                message("Reading ${page.title}${if (page.nextUrl != null && app.browserReader.followPages) " · open the next chapter when you are ready; Roadleaf continues there" else ""}")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message(error.message ?: "Cannot read this page.")
            } finally { actions.forEach { it.isEnabled = true } }
        }
    }
    /** Roadleaf reads only what the browser shows and never turns pages: Play reads the page you moved to. */
    private fun continueOnScreen() {
        when (BrowserContinue.after(app.webChapterEnded.value, pageAddress())) {
            BrowserContinue.Next.READ_SHOWN -> readPage(fresh = false)
            BrowserContinue.Next.SAME_PAGE -> message("Chapter finished • open the next chapter in your browser, then press Play.")
            BrowserContinue.Next.NO_PAGE -> message("Open the next chapter in your browser, then press Play.")
        }
    }
    /** Continues only after the previous chapter ended and the owner opened its next chapter. */
    private fun followTab() {
        val page = tabPage ?: return
        val next = page.nextUrl ?: return
        if (!app.browserReader.followPages || app.webChapterEnded.value != page.url || capture?.isActive == true) return
        if (pageAddress() != next) return
        scope.launch { delay(1500); if (pageAddress() == next && tabPage == page) readPage(fresh = false, expectedUrl = next) }
    }
    private fun beginRead(start: BrowserStart, checkOnly: Boolean = false, fromSelection: Boolean = false,
                          fromVisibleTop: Boolean = false) {
        // Every tap reports what happened; nothing fails silently.
        if (capture?.isActive == true) { message("Still starting the last request. Wait a moment, then try again."); return }
        val root = verifiedRoot() ?: run { message("Open your NovelAI story, tap inside its story box, then choose a read button."); return }
        val address = BrowserEditorNodes.address(root)
        val editor = try { BrowserEditorNodes.focusedEditor(root) } catch (error: Exception) { message(error.message.orEmpty()); return } finally { root.recycle() }
        val text = try { BrowserEditorNodes.text(editor) } catch (error: Exception) { editor.recycle(); message(error.message.orEmpty()); return }
        // Use a real selection, never an old insertion caret left elsewhere in the manuscript.
        val selected = if (fromSelection && editor.textSelectionEnd > editor.textSelectionStart)
            editor.textSelectionStart else -1
        val raw = editor.text?.toString().orEmpty()
        val visible = if (fromVisibleTop) BrowserEditorNodes.firstVisibleOffset(editor) else null
        val caret = when { fromSelection -> selected; fromVisibleTop -> visible; else -> null }
        val cursor = caret?.takeIf { it >= 0 }?.let { atRaw ->
            val at = if (fromVisibleTop) atRaw else BrowserStory.offsetOf(raw, atRaw)
            val bounded = at.coerceIn(0, text.length - 1)
            StartPoint.word(text, bounded)?.first ?: bounded
        }
        if ((fromSelection || fromVisibleTop) && cursor == null) {
            editor.recycle()
            message(if (fromSelection) "Highlight text in the story box, then tap Read selection."
                    else "This browser does not expose which story text is visible. Highlight a starting word and tap Read selection.")
            return
        }
        capture = scope.launch {
            actions.forEach { it.isEnabled = false }
            profileChoice?.isEnabled = false
            var retained = false
            try {
                val linked = app.browserStories.matchingBook(text)
                val description = collectionDescription(linked)
                if (checkOnly) { message(description); return@launch }
                val configured = app.browserSession.value?.takeIf { it.pinnedBook && !it.temporary }
                // An explicit setup choice still goes through capture's same-story/replacement guard.
                val saved = configured?.let { app.dao.book(it.bookId) } ?: linked
                var temporary = saved == null && configured == null
                var begin = start
                var offset = cursor ?: -1
                if (start == BrowserStart.RESUME && (temporary || saved == null || app.browserStories.progress(saved.id) == null)) {
                    // A story not saved to Library: continue where the last reading stopped, if the text before it is unchanged.
                    val place = withContext(Dispatchers.Default) { app.browserStories.memory.find(text) }
                    when {
                        place == null -> { message("No saved place for this story yet. Choose From top."); return@launch }
                        place >= text.trimEnd().length -> { message("No new text since you stopped. Write or generate more, then tap Resume."); return@launch }
                        else -> { begin = BrowserStart.AT; offset = ParagraphResume.start(text, place); temporary = temporary || saved == null }
                    }
                }
                val profileBook = linked?.takeIf { book -> app.browserReader.useProfiles && app.casts.get(book.id).let { it.profileSetId.isNotBlank() && it.characters.any { character -> character.profileId.isNotBlank() } } }?.id
                val chosen = BrowserReadingSession(UUID.randomUUID().toString(),
                    if (temporary) "free-${UUID.randomUUID()}" else saved?.id ?: requireNotNull(configured).bookId,
                    if (temporary) BrowserStory.UNSAVED_TITLE else saved?.title ?: requireNotNull(configured).title,
                    browser, configured?.follow ?: app.browserReader.follow, configured?.allowReplacement == true,
                    webAppPackage, webAppLaunchMs, temporary, profileBook, app.browserReader.useProfiles, pinnedBook = false)
                // Establish the token before handing over the editor; the observer must not cancel this click.
                internalToken = chosen.token; app.browserSession.value = chosen
                selectedEditor?.recycle(); selectedEditor = editor; retained = true; selectedAddress = address; acceptedText = ""
                val result = activeController().awaitCommand(NarrationService.BROWSER_READ, arguments(chosen, text).apply {
                    putString("start", begin.name); if (begin == BrowserStart.AT) putInt("offset", offset)
                })
                if (app.browserSession.value?.token != chosen.token) return@launch
                acceptedText = text
                app.browserSession.value = chosen.copy(allowReplacement = false)
                app.browserReader.remember(chosen)
                // Reading a NovelAI story counts as adding NovelAI: it then shows in Roadleaf's Library and Sources.
                app.browserReader.showNovelAi(true)
                bubble?.collapse()
                message("${result.extras.getString("message")}\n$description${if (!chosen.useProfiles) " · profiles off" else ""}")
                // If Android doesn't start the audio (for example a selected output that isn't connected), say why
                // beside the bubble. Checked separately so the buttons are usable again at once.
                scope.launch {
                    val player = activeController()
                    val playing = withTimeoutOrNull(6000) { while (!player.isPlaying) delay(250); true } == true
                    if (!playing && app.browserSession.value?.token == chosen.token) message("Not playing yet: ${app.playbackStatus.value}")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (retained) clearEditor()
                message(error.message ?: "Cannot read this editor.")
            } finally { if (!retained) editor.recycle(); actions.forEach { it.isEnabled = true }; profileChoice?.isEnabled = true }
        }
    }
    private fun collectionDescription(book: Book?): String {
        if (book == null) return "Not saved in Library and no linked collection · narrator voice only."
        val cast = app.casts.get(book.id)
        val count = cast.characters.count { it.profileId.isNotBlank() }
        return if (cast.profileSetId.isNotBlank() && count > 0) "${book.title} · $count linked profiles${if (!cast.enabled) " (voices disabled in book settings)" else ""}"
            else "${book.title} · no linked collection; saved voice choices available."
    }
    private suspend fun follow() {
        val session = app.browserSession.value ?: return
        if (!session.follow || acceptedText.isEmpty() || capture?.isActive == true) return
        val root = verifiedRoot() ?: return
        val address = BrowserEditorNodes.address(root); root.recycle()
        if (selectedAddress != null && address != selectedAddress) { clearEditor(); message("Story address changed. Tap From top for the displayed story."); return }
        // Unchanged from 0.3.19, the follow that ran for hours at a time in the NovelAI web app.
        val editor = selectedEditor ?: return
        try {
            require(editor.refresh()) { "The story box changed. Tap it, then a read button again." }
            val current = BrowserEditorNodes.text(editor)
            if (BrowserStory.completedText(current, acceptedText) == null) return
            delay(800)
            val verified = verifiedRoot() ?: return
            val stillAddress = BrowserEditorNodes.address(verified); verified.recycle()
            if (selectedAddress != null && selectedAddress != stillAddress) { clearEditor(); message("Story changed. Select its story box again."); return }
            if (!editor.refresh() || app.browserSession.value?.token != session.token) return
            val next = BrowserStory.stableCompletedText(current, BrowserEditorNodes.text(editor), acceptedText) ?: return
            val result = activeController().awaitCommand(NarrationService.BROWSER_APPEND, arguments(session, next))
            // Routine progress: shown on the card and in Roadleaf, not beside the bubble.
            acceptedText = next; message(result.extras.getString("message") ?: "New completed paragraphs ready for local narration.", peek = false)
        } catch (error: Exception) { if (error is CancellationException) throw error; clearEditor(); message(error.message ?: "Follow stopped; select the story again.") }
    }
    private fun arguments(session: BrowserReadingSession, text: String) = Bundle().apply { putString("token", session.token); putString("payload", app.browserText.put(session.token, text)) }
    private suspend fun activeController(): MediaController {
        controller?.let { return it }
        val future = connection ?: MediaController.Builder(this, SessionToken(this, ComponentName(this, NarrationService::class.java))).buildAsync().also { connection = it }
        return suspendCancellableCoroutine { pending -> future.addListener({ if (pending.isActive) try { pending.resume(future.get().also { controller = it }) } catch (error: Exception) { pending.resumeWithException(error) } }, ContextCompat.getMainExecutor(this)) }
    }
    /** Shown on the card and in Roadleaf; with [peek], also briefly beside the collapsed bubble. */
    private fun message(text: String, peek: Boolean = true) {
        app.browserStatus.value = BrowserReadingStatus(true, text.take(600)); hint?.text = text
        if (peek) bubble?.peek(text)
    }
    private fun clearEditor() { selectedEditor?.recycle(); selectedEditor = null; acceptedText = ""; selectedAddress = null; app.browserText.clear() }
    private fun hideOverlay() {
        bubble?.hide()
        bubble = null; overlayMode = null; actions.clear(); hint = null; profileChoice = null
    }
    override fun onInterrupt() { app.browserSession.value = null; message("Browser reading interrupted. Tap a read button on the story page to continue.") }
    override fun onDestroy() {
        val token = watchedToken
        if (token != null) controller?.sendCustomCommand(SessionCommand(NarrationService.BROWSER_STOP, Bundle.EMPTY), Bundle().apply { putString("token", token) })
        app.browserSession.value = null; app.browserStatus.value = BrowserReadingStatus()
        scope.cancel(); clearEditor(); hideOverlay(); connection?.let(MediaController::releaseFuture)
        super.onDestroy()
    }
}
