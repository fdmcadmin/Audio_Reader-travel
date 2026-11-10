package app.roadleaf

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.TrafficStats
import android.os.Bundle
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.ViewGroup
import android.view.View
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.graphics.Rect
import android.view.Gravity
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import app.roadleaf.browser.BrowserStory
import app.roadleaf.playback.NarrationService
import app.roadleaf.playback.awaitCommand
import app.roadleaf.sources.AddressInput
import app.roadleaf.sources.LiveWebPages
import app.roadleaf.sources.LiveWebPage
import app.roadleaf.sources.SearchEngine
import app.roadleaf.sources.WebAccess
import app.roadleaf.sources.WebAddress
import app.roadleaf.sources.WebNavigation
import app.roadleaf.sources.WebSelection
import app.roadleaf.sources.WebBookLinks
import android.view.inputmethod.EditorInfo
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shown when the next chapter couldn't be read early; it is loaded again, as usual, when the current chapter ends. */
private const val EARLY_RETRY = "Couldn't get the next chapter ready yet • trying again when this one ends"

/**
 * Visible, bounded, user-initiated reading of any secure page. No background crawler or browser-cookie
 * import. Sites keep their own sign-in inside this reader; locked, paid or checked pages are not read.
 */
@UnstableApi
@SuppressLint("SetTextI18n") // This personal baseline is English-only, matching the Compose screens.
class WebReaderActivity : ComponentActivity() {
    private val app get() = application as RoadleafApp
    private val preferences by lazy { getSharedPreferences("web-reader", MODE_PRIVATE) }
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var traffic: TextView
    private lateinit var activeTitle: TextView
    private lateinit var autoNext: CheckBox
    private lateinit var playbackButton: Button
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // A chapter read early becomes the reader's page once its audio starts.
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && ::web.isInitialized) player.currentMediaItem?.mediaMetadata?.extras?.getString("webUrl")
                ?.let(LiveWebPages::canonical)?.takeIf { it == LiveWebPages.canonical(web.url.orEmpty()) }?.let { preferences.edit { putString("url", it) } }
            updatePlaybackButton()
        }
    }
    private lateinit var connection: ListenableFuture<MediaController>
    private var controller: MediaController? = null
    private var finishedUrl: String? = null
    private var pageFailed = false
    private var rendererGone = false
    private var automaticPage = false
    private var manualRead: Job? = null
    private var openingBook: Job? = null
    private var pageMessage = "Open a chapter page, then press Play or Read chapter."
    private val startedBytes = networkBytes()
    // Read by the network thread for resource decisions.
    @Volatile private var mainUrl: String? = null
    @Volatile private var imagesAllowed = false
    private lateinit var signInRow: LinearLayout
    private lateinit var signInText: TextView
    private lateinit var signInButton: Button
    private lateinit var searchButton: Button
    private var signInUrl: String? = null
    // Same-site redirects of an automatic next-chapter load, by navigation request.
    private val redirects = mutableMapOf<Long, String>()

    @SuppressLint("SetJavaScriptEnabled") // Required for normal site rendering; no native JS bridge is exposed.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (saveBrowserShare(intent)) return
        if (openNovelAiSetup(intent)) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true
            setBackgroundColor(Color.rgb(16, 25, 22))
        }
        setContentView(root)
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(4)) }
        header.addView(TextView(this).apply { text = "Roadleaf"; textSize = 23f; setTextColor(Color.rgb(236, 244, 238)); setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(readerButton("Library") { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) })
        root.addView(header)
        val nowReading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Color.rgb(28, 42, 36), 18)
        }
        activeTitle = TextView(this).apply { text = "Your webpage book"; textSize = 18f; maxLines = 2; setTypeface(null, Typeface.BOLD); setTextColor(Color.rgb(236, 244, 238)) }
        status = TextView(this).apply { text = "Open a chapter, then choose Read loaded chapter."; textSize = 13f; maxLines = 2; setTextColor(Color.rgb(186, 204, 192)); setPadding(0, dp(5), 0, 0) }
        nowReading.addView(activeTitle); nowReading.addView(status)
        signInRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
        signInText = TextView(this).apply {
            text = "This site offers sign-in. Signing in is recommended: Roadleaf then reads what your account can see. Your password stays with the site."
            textSize = 13f; setTextColor(Color.rgb(214, 232, 220))
        }
        signInRow.addView(signInText)
        val signInButtons = LinearLayout(this)
        signInButton = readerButton("Sign in") { signInUrl?.let { url -> web.loadUrl(url) }; signInRow.isVisible = false }
        signInButtons.addView(signInButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        signInButtons.addView(readerButton("Not now") {
            WebAccess.siteKey(web.url.orEmpty())?.let { key -> preferences.edit { putStringSet("signInDismissed", dismissedSites() + key) } }
            signInRow.isVisible = false
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        signInRow.addView(signInButtons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        nowReading.addView(signInRow)
        root.addView(nowReading, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(2), dp(16), dp(4)) })
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(8)) }
        val optionsScroll = ScrollView(this).apply { visibility = View.GONE; addView(options) }
        val optionsButton = readerButton("Address, search & options ▾") {
            optionsScroll.isVisible = !optionsScroll.isVisible
        }
        root.addView(optionsButton, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(16), 0, dp(16), dp(4)) })
        root.addView(optionsScroll, LinearLayout.LayoutParams(-1, dp(230)))
        address = EditText(this).apply {
            hint = "Web address or search words"; textSize = 14f; setSingleLine(true)
            setTextColor(Color.rgb(236, 244, 238)); setHintTextColor(Color.rgb(170, 193, 178))
            minHeight = dp(52); setPadding(dp(12), dp(8), dp(12), dp(8)); background = rounded(Color.rgb(28, 42, 36), 12)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_GO) { openTypedPage(); true } else false }
        }
        options.addView(address)
        fun LinearLayout.actionRow(first: Button, second: Button) {
            val row = LinearLayout(this@WebReaderActivity)
            row.addView(first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            row.addView(second, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        options.actionRow(readerButton("Open / search") { openTypedPage() }, readerButton("New book") { showNewBookDialog() })
        searchButton = readerButton(SearchEngine.named(preferences.getString("searchEngine", null))?.let { "Search engine: ${it.label}" } ?: "Choose search engine") { chooseSearchEngine() }
        options.addView(searchButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        imagesAllowed = preferences.getBoolean("images", false)
        options.addView(CheckBox(this).apply {
            text = "Show images (uses more data)"; textSize = 14f; minHeight = dp(48)
            setTextColor(Color.rgb(236, 244, 238)); buttonTintList = ColorStateList.valueOf(Color.rgb(159, 217, 177))
            isChecked = imagesAllowed
            setOnCheckedChangeListener { _, checked ->
                preferences.edit { putBoolean("images", checked) }
                imagesAllowed = checked
                web.settings.loadsImagesAutomatically = checked; web.settings.blockNetworkImage = !checked
            }
        })
        autoNext = CheckBox(this).apply {
            text = "Auto-next chapter"; textSize = 14f; minHeight = dp(48)
            setTextColor(Color.rgb(236, 244, 238)); buttonTintList = ColorStateList.valueOf(Color.rgb(159, 217, 177))
            isChecked = preferences.getBoolean("autoNext", true)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit { putBoolean("autoNext", checked) }
                send(NarrationService.WEB_OPTIONS, Bundle().apply { putBoolean("autoNext", checked) })
            }
        }
        options.addView(autoNext)
        options.addView(readerButton("Character voices") {
            val key = WebBookLinks.resolve(web.url ?: address.text.toString())?.bookUrl
            startActivity(Intent(this, MainActivity::class.java).putExtra("voiceCastBook", key).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        })
        options.addView(readerButton("Resume paragraph") {
            val url = LiveWebPages.canonical(web.url.orEmpty())
            if (url == null) status.text = "Read the displayed chapter first."
            else send(NarrationService.RESUME_PARAGRAPH, Bundle().apply { putString("url", url) })
        })
        options.addView(CheckBox(this).apply {
            text = "Dark webpages"; textSize = 14f; minHeight = dp(48)
            setTextColor(Color.rgb(236, 244, 238)); buttonTintList = ColorStateList.valueOf(Color.rgb(159, 217, 177))
            isChecked = preferences.getBoolean("darkWeb", true)
            setOnCheckedChangeListener { _, checked ->
                preferences.edit { putBoolean("darkWeb", checked) }
                if (Build.VERSION.SDK_INT >= 33) web.settings.isAlgorithmicDarkeningAllowed = checked else darkStyle(checked)
            }
        })
        options.addView(readerButton("Open in my browser") {
            // Your default browser, whichever it is; Android shows a chooser when none is set.
            (LiveWebPages.canonical(address.text.toString().trim()) ?: LiveWebPages.canonical(web.url.orEmpty()))?.let {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, it.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }.onFailure { status.text = "No browser available." }
            }
        })
        traffic = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(170, 193, 178)) }
        options.addView(traffic)
        options.addView(TextView(this).apply { text = "Local voice • text pages use a connection\nChecks after 10 seconds; waits up to 30 seconds."; textSize = 12f; setTextColor(Color.rgb(170, 193, 178)) })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(10)) }
        playbackButton = readerButton("Play", primary = true) { togglePlayback() }
        controls.actionRow(playbackButton, readerButton("Next chapter") {
                if (controller?.currentMediaItem?.mediaMetadata?.extras?.containsKey("webUrl") == true)
                    send(NarrationService.SKIP_CHAPTER)
                else status.text = "Choose Read loaded chapter first."
        })
        controls.addView(readerButton("Read loaded chapter") { readCurrentChapter(freshStart = true) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        // The long-press Read from here item is held back with the other start-at-a-word buttons (Features.START_HERE).
        val view = try { if (Features.START_HERE) selectionWebView() else WebView(this) } catch (_: Exception) {
            // Some devices (certain TVs, cars or trimmed builds) ship without Android System WebView.
            Toast.makeText(this, "This device has no Android System WebView, which the web reader needs. Install or enable it, then try again.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        web = view.apply {
            // Keep the browser renderer important while the media foreground service plays behind other apps.
            // Do not waive its priority just because this activity is no longer visible.
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                loadsImagesAutomatically = imagesAllowed; blockNetworkImage = !imagesAllowed
                mediaPlaybackRequiresUserGesture = true; safeBrowsingEnabled = true
                setSupportMultipleWindows(false)
                if (Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = preferences.getBoolean("darkWeb", true)
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            setDownloadListener { _, _, _, _, _ -> status.text = "Use your browser for permitted file downloads, then import from Library." }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val url = request.url.toString()
                    if (LiveWebPages.address(url) != null) return false
                    val secure = if (url.startsWith("http://", true)) LiveWebPages.address("https://" + url.substring(7)) else null
                    if (secure != null) { status.text = "Opening the secure (https) version of this page…"; view.loadUrl(secure.toString()) }
                    else status.text = "That link opens another app or an insecure page. Use Open in my browser if you need it."
                    return true
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    finishedUrl = null; pageFailed = false; manualRead?.cancel(); web.resumeTimers()
                    mainUrl = url; signInRow.isVisible = false
                    WebBookLinks.resolve(url)?.let { activeTitle.text = it.title }
                    val expected = app.webNavigation.value
                    if (expected != null) {
                        val loaded = LiveWebPages.canonical(url)
                        // A site may redirect its own next-chapter link; another site never counts.
                        if (loaded != expected.url) {
                            if (loaded != null && LiveWebPages.sameSite(loaded, expected.url)) redirects[expected.id] = loaded else fail(expected)
                        }
                    }
                    if (expected == null) pauseOtherSource(url)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (pageFailed || LiveWebPages.canonical(url) != LiveWebPages.canonical(web.url.orEmpty())) return
                    finishedUrl = url
                    // A chapter read early is remembered only when its audio starts, so stopping now resumes the current one.
                    val early = app.webNavigation.value?.early == true
                    LiveWebPages.canonical(url)?.let { address.setText(it); if (!early) preferences.edit { putString("url", it) } }
                    // Only chapter pages become Quick picks; ordinary browsing and searches do not.
                    if (!early) LiveWebPages.canonical(url)?.takeIf { WebBookLinks.resolve(it)?.chapterTitle?.isNotEmpty() == true }?.let { rememberPage(it) }
                    if (Build.VERSION.SDK_INT < 33 && preferences.getBoolean("darkWeb", true)) darkStyle(true)
                    if (app.webNavigation.value == null && manualRead?.isActive != true) {
                        pageMessage = if (Features.START_HERE) "Page ready • Read loaded chapter starts at the top • or long-press a word, then Read from here"
                            else "Page ready • Read loaded chapter starts at the top"
                        status.text = pageMessage
                        checkSignIn()
                    }
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel(); pageFailed = true; finishedUrl = null; status.text = "Secure connection failed. Page reading stopped."
                    app.webNavigation.value?.let(::fail)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) { pageFailed = true; finishedUrl = null; status.text = "Page could not load. Check your connection and open it again."; app.webNavigation.value?.let(::fail) }
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 400) { pageFailed = true; finishedUrl = null; status.text = "Site returned ${response.statusCode}. Check the visible page; reading stopped."; app.webNavigation.value?.let(::fail) }
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val uri = runCatching { URI(request.url.toString()) }.getOrNull()
                    val main = if (request.isForMainFrame) request.url.toString() else mainUrl
                    return if (uri != null && LiveWebPages.permittedResource(uri, main, imagesAllowed)) null
                    else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    rendererGone = true; pageFailed = true; finishedUrl = null; manualRead?.cancel()
                    app.webNavigation.value?.let(::fail)
                    if (controller?.currentMediaItem?.mediaMetadata?.extras?.containsKey("webUrl") == true) controller?.pause()
                    (view.parent as? ViewGroup)?.removeView(view); view.destroy()
                    status.text = "The webpage closed to free memory. Choose Open page to reopen it, then Read chapter."
                    return true
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(dp(12), 0, dp(12), 0) })
        root.addView(controls)
        connection = MediaController.Builder(this, SessionToken(this, ComponentName(this, NarrationService::class.java))).buildAsync()
        connection.addListener({
            runCatching { controller = connection.get(); controller?.addListener(playerListener); pauseOtherSource(web.url.orEmpty()); updatePlaybackButton() }
                .onFailure { status.text = "Playback connection failed. Reopen Roadleaf." }
        }, ContextCompat.getMainExecutor(this))
        // Remains active when the screen locks; stopping at STARTED would break chapter handoffs.
        lifecycleScope.launch {
            app.webNavigation.collectLatest { request ->
                if (request == null) {
                    if (automaticPage) {
                        if (!rendererGone) { web.stopLoading(); web.pauseTimers() }
                        // A canceled collectLatest scan cannot write its terminal message itself.
                        status.text = app.playbackStatus.value
                    }
                    automaticPage = false
                } else {
                    if (rendererGone) { fail(request); status.text = "Reopen the web reader to restore the page."; return@collectLatest }
                    automaticPage = true
                    manualRead?.cancel()
                    address.setText(request.url); finishedUrl = null; web.resumeTimers(); web.loadUrl(request.url)
                    // Reading starts once the page has loaded and settled, not at a fixed ten seconds.
                    status.text = if (request.early) "Getting the next chapter ready…" else "Loading the chapter page • reading starts once it loads (30-second maximum)"
                    safelyRead(request)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.playbackStatus.collect {
                    val audioUrl = controller?.currentMediaItem?.mediaMetadata?.extras?.getString("webUrl")
                    if (app.webNavigation.value != null || (audioUrl != null && LiveWebPages.canonical(audioUrl) == LiveWebPages.canonical(web.url.orEmpty()))) status.text = it
                } }
                launch {
                    while (isActive) {
                        val current = networkBytes()
                        traffic.text = if (startedBytes < 0 || current < startedBytes) "Traffic measurement unavailable"
                            else String.format(Locale.US, "Approx. app traffic since reader opened: %.2f MB • Wi-Fi + mobile", (current - startedBytes) / 1_000_000.0)
                        delay(10_000)
                    }
                }
            }
        }
        if (app.webNavigation.value == null) {
            val initial = intent.getStringExtra("url")?.let(LiveWebPages::canonical)
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let(LiveWebPages::sharedUrl)
                ?: preferences.getString("url", null)?.let(LiveWebPages::canonical)
            if (initial != null) {
                address.setText(initial)
                if (savedInstanceState == null && (intent.getBooleanExtra("loadNewBook", false) || intent.getBooleanExtra("resumeWebBook", false)))
                    openNewBook(initial, fresh = !intent.getBooleanExtra("resumeWebBook", false)) else web.loadUrl(initial)
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun readerButton(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; minHeight = dp(50); minimumHeight = dp(50); minWidth = 0; minimumWidth = 0
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setTextColor(if (primary) Color.rgb(14, 53, 29) else Color.rgb(224, 237, 227))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(50, 180, 225, 194)), rounded(if (primary) Color.rgb(159, 217, 177) else Color.rgb(35, 52, 43), 14), null)
        setOnClickListener { action() }
    }
    private fun rememberPage(url: String) { lifecycleScope.launch {
        runCatching { app.websites.remember(url) }.onSuccess { it?.let { book -> if (LiveWebPages.canonical(web.url.orEmpty()) == url) activeTitle.text = book.title } }
    } }
    private fun updatePlaybackButton() {
        if (!::playbackButton.isInitialized) return
        playbackButton.text = if (controller?.playWhenReady == true || manualRead?.isActive == true || openingBook?.isActive == true) "Pause" else "Play"
        val item = controller?.currentMediaItem
        if (::web.isInitialized && item?.mediaId != "web-loading" && LiveWebPages.canResume(web.url.orEmpty(), item?.mediaMetadata?.extras?.getString("webUrl"), false))
            item?.mediaMetadata?.artist?.takeIf { it.isNotBlank() }?.let { activeTitle.text = it }
    }
    private fun togglePlayback() {
        val active = controller ?: run { status.text = "Playback is connecting. Try again shortly."; return }
        if (active.playWhenReady || manualRead?.isActive == true || openingBook?.isActive == true) {
            manualRead?.cancel(); openingBook?.cancel(); active.pause(); send(NarrationService.WEB_PAUSE); status.text = "Paused"; updatePlaybackButton(); return
        }
        val audioUrl = active.currentMediaItem?.mediaMetadata?.extras?.getString("webUrl")
        if (LiveWebPages.canResume(web.url.orEmpty(), audioUrl, active.currentMediaItem?.mediaId == "web-loading" || active.playbackState == Player.STATE_ENDED)) active.play()
        else readCurrentChapter()
    }

    private fun networkBytes(): Long {
        val received = TrafficStats.getUidRxBytes(Process.myUid())
        val sent = TrafficStats.getUidTxBytes(Process.myUid())
        return if (received < 0 || sent < 0) -1 else received + sent
    }

    /** NovelAI is read in your own browser, not here: a NovelAI link adds it to the Library and shows how. */
    private fun openNovelAiSetup(incoming: Intent): Boolean {
        val url = incoming.getStringExtra("url") ?: incoming.getStringExtra(Intent.EXTRA_TEXT)?.let(LiveWebPages::sharedUrl) ?: return false
        if (!BrowserStory.novelAiAddress(url)) return false
        app.browserReader.showNovelAi(true)
        startActivity(Intent(this, NovelAiBrowserActivity::class.java))
        finish()
        return true
    }
    private fun saveBrowserShare(incoming: Intent): Boolean {
        if (incoming.action != Intent.ACTION_SEND) return false
        val text = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (LiveWebPages.sharedUrl(text) != null) return false
        // Unsupported shared links must never silently fall back to the previous reader page.
        controller?.pause()
        val url = WebBookLinks.sharedUrl(text)
        lifecycleScope.launch {
            val saved = if (url == null) null else runCatching { app.websites.remember(url) }.getOrNull()
            Toast.makeText(this@WebReaderActivity,
                if (saved != null) "Saved to Quick picks. Open this site in your browser."
                else "Share a secure HTTPS book or chapter link.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this@WebReaderActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            finish()
        }
        return true
    }

    // Opening Roadleaf brings back a browser bubble closed with ✕.
    override fun onResume() { super.onResume(); app.browserReader.bubbleClosed = false }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (saveBrowserShare(intent)) return
        if (openNovelAiSetup(intent)) return
        if (rendererGone) { recreate(); return }
        val url = intent.getStringExtra("url")?.let(LiveWebPages::canonical)
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let(LiveWebPages::sharedUrl)
        if (url != null) {
            if (intent.getBooleanExtra("loadNewBook", false) || intent.getBooleanExtra("resumeWebBook", false)) openNewBook(url, fresh = !intent.getBooleanExtra("resumeWebBook", false))
            else if (LiveWebPages.canonical(web.url.orEmpty()) != url) { address.setText(url); openTypedPage() }
        }
    }

    private fun openNewBook(url: String, fresh: Boolean = true) {
        openingBook?.cancel(); manualRead?.cancel(); automaticPage = false
        address.setText(url)
        openingBook = lifecycleScope.launch {
            try {
                val active = withTimeout(20_000) { suspendCancellableCoroutine<MediaController> { continuation ->
                    connection.addListener({
                        if (continuation.isActive) try { continuation.resume(connection.get()) }
                        catch (error: Exception) { continuation.resumeWithException(error) }
                    }, ContextCompat.getMainExecutor(this@WebReaderActivity))
                } }
                controller = active
                active.awaitCommand(NarrationService.WEB_BEGIN, Bundle().apply {
                    putString("url", url); putBoolean("fresh", fresh); putBoolean("navigate", true)
                })
            } catch (_: TimeoutCancellationException) { status.text = "Playback connection timed out. Try Load new book again." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status.text = "Could not load webpage: ${error.message}" }
            finally { playbackButton.post { updatePlaybackButton() } }
        }
        updatePlaybackButton()
    }

    private fun showNewBookDialog() {
        val entry = EditText(this).apply {
            hint = "Paste a chapter's web address"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(false); maxLines = 4
        }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Load new webpage book").setView(entry)
            .setNegativeButton("Cancel", null).setPositiveButton("Load webpage", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = (WebAddress.read(entry.text.toString()) as? AddressInput.Page)?.url?.let(LiveWebPages::canonical)
                if (url == null) entry.error = "Enter a secure web address for the first chapter you want to hear"
                else { dialog.dismiss(); openNewBook(url) }
            }
        }
        dialog.show()
    }

    private fun openTypedPage() {
        if (rendererGone) { recreate(); return }
        when (val input = WebAddress.read(address.text.toString())) {
            is AddressInput.Page -> openPage(LiveWebPages.canonical(input.url) ?: input.url)
            is AddressInput.Search -> SearchEngine.named(preferences.getString("searchEngine", null))?.let { openPage(it.url(input.query)) }
                ?: chooseSearchEngine { openPage(it.url(input.query)) }
            AddressInput.Invalid -> status.text = "Enter a secure web address, or words to search for."
        }
    }

    private fun openPage(url: String) {
        automaticPage = false
        manualRead?.cancel(); controller?.pause(); finishedUrl = null; web.resumeTimers(); web.loadUrl(url)
        status.text = "Let the page load, then choose Read loaded chapter."
    }

    private fun chooseSearchEngine(then: ((SearchEngine) -> Unit)? = null) {
        val engines = SearchEngine.entries
        android.app.AlertDialog.Builder(this).setTitle("Search with")
            .setItems(engines.map { it.label }.toTypedArray()) { _, index ->
                val engine = engines[index]
                preferences.edit { putString("searchEngine", engine.name) }
                searchButton.text = "Search engine: ${engine.label}"
                then?.invoke(engine)
            }.setNegativeButton("Cancel", null).show()
    }

    /** Android 8–12 have no built-in webpage darkening; a reader style gives the same dark view. */
    private fun darkStyle(on: Boolean) {
        if (rendererGone || !::web.isInitialized) return
        val script = if (on) "(function(){var s=document.getElementById('roadleaf-dark');if(!s){s=document.createElement('style');s.id='roadleaf-dark';" +
            "(document.head||document.documentElement).appendChild(s);}s.textContent='html,body{background:#101915!important;color:#e3ece5!important}" +
            "body *{background-color:transparent!important;color:inherit!important;border-color:#2c3a33!important}a,a *{color:#9fd9b1!important}';})()"
            else "(function(){var s=document.getElementById('roadleaf-dark');if(s){s.parentNode.removeChild(s);}})()"
        web.evaluateJavascript(script, null)
    }

    private fun dismissedSites(): Set<String> = preferences.getStringSet("signInDismissed", emptySet()).orEmpty().toSet()

    /** Sign-in is suggested once per site and never required; locked pages are reported when read. */
    private fun checkSignIn() {
        if (rendererGone || app.webNavigation.value != null || automaticPage) return
        val page = web.url.orEmpty()
        val key = WebAccess.siteKey(page) ?: return
        web.evaluateJavascript(WebAccess.SCRIPT) { value ->
            if (rendererGone || isDestroyed || web.url != page) return@evaluateJavascript
            val hint = WebAccess.parse(runCatching { JSONTokener(value).nextValue() as? String }.getOrNull())
            signInUrl = hint?.signInUrl
            signInButton.isVisible = signInUrl != null
            signInRow.isVisible = WebAccess.suggest(hint, key in dismissedSites())
        }
    }

    private fun send(action: String, args: Bundle = Bundle.EMPTY) {
        controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    private fun pauseOtherSource(url: String) {
        val active = controller ?: return
        // Browsing searches or menus keeps your book playing; opening another chapter page pauses it.
        if (app.webNavigation.value != null || LiveWebPages.canonical(url) == null || WebBookLinks.resolve(url)?.chapterTitle.isNullOrEmpty()) return
        val audioUrl = active.currentMediaItem?.mediaMetadata?.extras?.getString("webUrl")
        if (active.currentMediaItem != null && LiveWebPages.canonical(audioUrl.orEmpty()) != LiveWebPages.canonical(url)) active.pause()
    }

    private fun readCurrentChapter(freshStart: Boolean = false) {
        if (rendererGone) { recreate(); return }
        // WEB_BEGIN pauses and clears atomically; a separate asynchronous Pause can cancel that reset.
        manualRead?.cancel(); web.resumeTimers()
        pageMessage = "Reading text from the displayed webpage…"; status.text = pageMessage
        manualRead = lifecycleScope.launch { safelyRead(null, freshStart) }
        updatePlaybackButton()
    }

    // "Read from here": a long-press selection menu item that starts at the selected word.
    private val readFromHereId = View.generateViewId()
    private fun selectionWebView(): WebView = object : WebView(this) {
        override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? = super.startActionMode(callback?.let { withReadFromHere(it) }, type)
        override fun startActionMode(callback: ActionMode.Callback?): ActionMode? = super.startActionMode(callback?.let { withReadFromHere(it) })
    }
    private fun withReadFromHere(original: ActionMode.Callback): ActionMode.Callback = object : ActionMode.Callback2() {
        private fun add(menu: Menu) { if (menu.findItem(readFromHereId) == null) menu.add(Menu.NONE, readFromHereId, 0, "Read from here") }
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = original.onCreateActionMode(mode, menu).also { add(menu) }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val changed = original.onPrepareActionMode(mode, menu)
            val missing = menu.findItem(readFromHereId) == null
            add(menu); return changed || missing
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != readFromHereId) return original.onActionItemClicked(mode, item)
            readFromSelection(mode); return true
        }
        override fun onDestroyActionMode(mode: ActionMode) = original.onDestroyActionMode(mode)
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (original is ActionMode.Callback2) original.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
        }
    }
    private fun readFromSelection(mode: ActionMode) {
        if (rendererGone) { mode.finish(); recreate(); return }
        // Read the selection before the menu closes; closing it can clear the page selection.
        web.evaluateJavascript(WebSelection.SCRIPT) { value ->
            mode.finish()
            val selected = WebSelection.parse(runCatching { JSONTokener(value).nextValue() as? String }.getOrNull())
            if (selected == null) { status.text = "Select a word in the story, then choose Read from here."; return@evaluateJavascript }
            manualRead?.cancel(); web.resumeTimers()
            pageMessage = "Reading from the selected word…"; status.text = pageMessage
            manualRead = lifecycleScope.launch {
                val page = try { snapshotPage(10_000) } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { status.text = "Page not ready: ${error.message ?: "try again in a moment."}"; return@launch }
                val at = WebSelection.locate(page.text, selected.selected, selected.before, selected.after)
                if (at == null) { status.text = "That word isn't in the chapter text Roadleaf reads. Select a word in the story itself."; return@launch }
                safelyRead(null, startAt = at, snapshot = page)
            }
            updatePlaybackButton()
        }
    }

    private fun fail(request: WebNavigation, message: String = "Webpage needs attention. Check the page and choose Read loaded chapter.") =
        send(NarrationService.WEB_FAILED, Bundle().apply { putLong("request", request.id); putString("message", message.take(600)) })

    private suspend fun safelyRead(request: WebNavigation?, freshStart: Boolean = false, startAt: Int? = null, snapshot: LiveWebPage? = null) {
        try {
            val active = requireNotNull(controller) { "Playback is connecting. Try again shortly." }
            val selection = if (request == null) {
                val url = LiveWebPages.canonical(web.url.orEmpty()) ?: error("Open a secure https chapter page first.")
                active.awaitCommand(NarrationService.WEB_BEGIN, Bundle().apply { putString("url", url); putBoolean("fresh", freshStart) }).extras.getLong("selection")
            } else 0L
            web.resumeTimers()
            val page = snapshot ?: if (request == null) snapshotPage(10_000) else scanUntilReady(request)
            val url = page.url
            if (LiveWebPages.canonical(web.url.orEmpty()) != url || (request != null && app.webNavigation.value?.id != request.id)) return
            // No page refresh or scrolling is needed during narration. Resume timers on the next load/read.
            web.stopLoading(); web.pauseTimers()
            val early = request?.early == true
            // An early chapter becomes the Quick pick when its audio starts (the narration service records it then).
            if (!early) app.websites.remember(page.url, page.bookTitle, page.title)
            activeTitle.text = page.bookTitle
            status.text = if (early) "Next chapter ready • it starts when this one ends" else "Preparing local narrator…"
            active.awaitCommand(NarrationService.WEB_PAGE, Bundle().apply {
                putString("url", page.url); putString("title", page.title); putString("bookTitle", page.bookTitle)
                putString("text", page.text); putString("next", page.nextUrl); putLong("request", request?.id ?: 0)
                putString("requested", request?.url)
                putLong("selection", selection)
                putBoolean("autoNext", autoNext.isChecked)
                if (startAt != null) putInt("startAt", startAt)
            })
        } catch (_: TimeoutCancellationException) {
            val message = "Page loading timed out. Check the visible page and try Read loaded chapter again."
            request?.let { fail(it, message) }; status.text = if (request?.early == true) EARLY_RETRY else message
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val message = "Page not loaded: ${error.message ?: "Check the visible chapter and try Read loaded chapter again."}"
            request?.let { fail(it, message) }; status.text = if (request?.early == true) EARLY_RETRY else message
        } finally { playbackButton.post { updatePlaybackButton() } }
    }

    /**
     * Scans from the start: once the page has finished loading and its chapter text has stayed the same for a moment, it
     * is used at once. From ten seconds on, the first readable scan is used, as before; thirty seconds is the limit.
     */
    private suspend fun scanUntilReady(request: WebNavigation): LiveWebPage {
        var lastProblem = "Chapter text is not ready."
        var previous: String? = null
        var unchangedSince = 0L
        while (SystemClock.elapsedRealtime() <= request.deadlineMs) {
            if (app.webNavigation.value?.id != request.id) throw CancellationException("Page handoff cancelled")
            val remaining = request.deadlineMs - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            val target = redirects[request.id]?.let { request.copy(url = it) } ?: request
            if (target.ready(SystemClock.elapsedRealtime(), web.url) && !pageFailed) {
                try {
                    val page = snapshotPage(minOf(10_000, remaining))
                    val finished = finishedUrl?.let(LiveWebPages::canonical) == target.url
                    val now = SystemClock.elapsedRealtime()
                    if (page.text != previous) { previous = page.text; unchangedSince = now }
                    if (now <= request.deadlineMs && request.accepts(now, finished, now - unchangedSince)) return page
                    lastProblem = "Chapter text was still changing."
                } catch (_: TimeoutCancellationException) { previous = null; lastProblem = "Page did not respond." }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { previous = null; lastProblem = error.message ?: lastProblem }
            } else previous = null
            if (!request.early) status.text = "Chapter text is still loading • checking again (30-second maximum)"
            delay(request.scanDelay(SystemClock.elapsedRealtime()))
        }
        error("Loading stopped after 30 seconds. $lastProblem")
    }

    private suspend fun snapshotPage(timeoutMs: Long): LiveWebPage {
        check(!rendererGone) { "Reopen the web reader to restore the page." }
        check(!pageFailed) { "The site could not load securely. Open the page again to retry." }
        val url = LiveWebPages.canonical(web.url.orEmpty()) ?: error("Open a secure https page first.")
        val html = withTimeout(timeoutMs) { suspendCancellableCoroutine<String?> { continuation ->
            web.evaluateJavascript(LiveWebPages.SNAPSHOT_SCRIPT) { value ->
                if (continuation.isActive) continuation.resume(runCatching { JSONTokener(value).nextValue() }.getOrNull() as? String)
            }
        } }
        requireNotNull(html) { "This page is too large for live reading, or its text could not be inspected." }
        return withContext(Dispatchers.Default) { LiveWebPages.parse(html, url) }
    }

    override fun onDestroy() {
        manualRead?.cancel(); openingBook?.cancel()
        controller?.removeListener(playerListener)
        if (isFinishing) send(NarrationService.WEB_OPTIONS, Bundle().apply { putBoolean("autoNext", false) })
        if (::connection.isInitialized) MediaController.releaseFuture(connection)
        if (::web.isInitialized && !rendererGone) { web.stopLoading(); web.resumeTimers(); (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }
}
