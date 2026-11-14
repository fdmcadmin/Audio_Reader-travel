package app.roadleaf.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.*
import app.roadleaf.MainActivity
import app.roadleaf.RoadleafApp
import app.roadleaf.data.*
import app.roadleaf.sources.LiveWebPage
import app.roadleaf.sources.LiveWebPages
import app.roadleaf.sources.WebNavigation
import app.roadleaf.sources.WebBookLinks
import android.os.SystemClock
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.StringReader
import java.io.File
import app.roadleaf.importing.BookFiles
import app.roadleaf.importing.ExpeditionJson
import app.roadleaf.browser.BrowserStory
import app.roadleaf.browser.BrowserStart
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@UnstableApi
class NarrationService : MediaLibraryService() {
    companion object {
        const val LOAD = "app.roadleaf.LOAD"
        const val REORDER = "app.roadleaf.REORDER"
        const val SKIP_CHAPTER = "app.roadleaf.SKIP_CHAPTER"
        const val VOICES = "app.roadleaf.VOICES"
        const val PREVIEW = "app.roadleaf.PREVIEW"
        const val APPLY_SETTINGS = "app.roadleaf.APPLY_SETTINGS"
        const val APPLY_CAST = "app.roadleaf.APPLY_CAST"
        const val RESET_LEARNING = "app.roadleaf.RESET_LEARNING"
        const val WEB_PAGE = "app.roadleaf.WEB_PAGE"
        const val WEB_FAILED = "app.roadleaf.WEB_FAILED"
        const val WEB_OPTIONS = "app.roadleaf.WEB_OPTIONS"
        const val WEB_BEGIN = "app.roadleaf.WEB_BEGIN"
        const val WEB_PAUSE = "app.roadleaf.WEB_PAUSE"
        const val DELETE_BOOK = "app.roadleaf.DELETE_BOOK"
        const val OUTPUTS = "app.roadleaf.OUTPUTS"
        const val SET_OUTPUT = "app.roadleaf.SET_OUTPUT"
        const val TEST_OUTPUT = "app.roadleaf.TEST_OUTPUT"
        const val MUSIC_MODE = "app.roadleaf.MUSIC_MODE"
        const val RESUME_PARAGRAPH = "app.roadleaf.RESUME_PARAGRAPH"
        const val UPDATE_TEXT = "app.roadleaf.UPDATE_TEXT"
        const val BROWSER_READ = "app.roadleaf.BROWSER_READ"
        const val BROWSER_APPEND = "app.roadleaf.BROWSER_APPEND"
        const val BROWSER_STOP = "app.roadleaf.BROWSER_STOP"
        /** Start reading at an exact text offset: `offset` plus either `url` (current webpage) or `bookId` and `chapterId`. */
        const val START_AT = "app.roadleaf.START_AT"
    }
    private val app get() = application as RoadleafApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var outputs: AudioOutputRouting
    private lateinit var headphoneMusic: HeadphoneMusicMode
    private val carCommands = mutableMapOf<MediaSession.ControllerInfo, Pair<SessionCommands, Player.Commands>>()
    private lateinit var chapterPlayer: ChapterSessionPlayer
    private val chapterClock = ChapterAudioClock()
    private var chapterItems = emptyList<MediaItem>()
    private val chapterTiming by lazy { getSharedPreferences("chapter-listening", MODE_PRIVATE) }
    private var restoredAudioKey = ""
    private var sessionCast = BookVoiceCast()
    private var libraryCasts = emptyMap<String, BookVoiceCast>()
    private val learnedBooks = mutableMapOf<String, LearnedCast>()
    private var adaptiveVoices = AdaptiveVoices()
    private var voiceOptionsKey = ""
    private var castState = DialogueState()
    private var castChapter = ""
    private lateinit var speech: OfflineSpeech
    private lateinit var session: MediaLibrarySession
    private val carLibrary by lazy { CarLibrary(app.dao) }
    private val queueMutex = Mutex()
    private val progressMutex = Mutex()
    private var work: Job? = null
    private var refill: Job? = null
    private var expeditionAdvance: Job? = null
    private var checkpoint: Job? = null
    private var queue: ChapterNarrationQueue? = null
    private var activeChapters = emptyList<Chapter>()
    private var expeditionBookId: String? = null
    private var temporaryQueue: List<Chapter>? = null
    private var sessionSettings = ListeningSettings()
    private var pronounce: (String) -> String = { it }
    private var lastProgress: Progress? = null
    private var loading = false
    private var completed = false
    private var preview = false
    private var webPage: LiveWebPage? = null
        set(value) { field = value; app.readingPage.value = value }
    private var webAutoNext = true
    // The page came from the owner's own browser tab; its next chapter is opened there, not here.
    private var webViaTab = false
    private var webRequest = 0L
    private var webTimeout: Job? = null
    private var webPreparing = false
    private var webWaitingForRead = false
    private val webPreferences by lazy { getSharedPreferences("web-listening", MODE_PRIVATE) }
    private val webProgress by lazy { WebListeningProgress(webPreferences) }
    private val targetPreferences by lazy { getSharedPreferences("playback-target", MODE_PRIVATE) }
    private var webSelection = 0L
    private var resettingPlayback = false
    /** A webpage chapter's opening passage, rendered and ready to play. Preparing one changes nothing in playback. */
    private class PreparedWebPage(val page: LiveWebPage, val queue: ChapterNarrationQueue, val part: NarrationPart, val following: NarrationPart?,
        val checkpoint: WebListeningProgress.Checkpoint?, val saved: Progress?, val profile: BookVoiceCast, val context: DialogueState,
        val cast: BookVoiceCast, val rendered: CastSpeechRenderer.Rendered, val duration: Long, val settings: ListeningSettings, val pronounce: (String) -> String)
    /** The next webpage chapter, read early near the end of [from] and prepared while that chapter finishes. */
    private class WebPrefetch(val token: Long, val url: String, val from: String) {
        var page: LiveWebPage? = null
        var autoNext = true
        var prepared: PreparedWebPage? = null
        var job: Job? = null
    }
    private var prefetch: WebPrefetch? = null
    // The page whose next chapter was already read early, so it is asked for only once.
    private var prefetchedFrom: String? = null
    private var warmUp: Job? = null
    private data class BrowserFeed(val token: String, val bookId: String, val chapterId: String, val loaded: Int, val pending: Int, val waiting: Int? = null)
    private var browserFeed: BrowserFeed? = null
    private var browserAdvance: Job? = null
    private var browserPauseEpoch = 0L
    private val temporaryAudioInterruption = MutableStateFlow(false)
    private fun trace(message: () -> String) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.d("RoadleafPrep", message())
    }
    /** A page load at a chapter's end or a book's start; an early read of the next chapter doesn't count. */
    private fun lateNavigation() = app.webNavigation.value?.takeUnless { it.early }
    /** Starts the speech engine and loads the narrator's voice in the background, so the first passage isn't slowed by a cold engine. */
    private fun warmUpNarrator() {
        if (warmUp?.isActive == true) return
        warmUp = scope.launch {
            val started = SystemClock.elapsedRealtime()
            val ready = speech.warmUp(app.listening.settings.value)
            trace { "narrator warm-up ${if (ready) "ready" else "not needed or unavailable"} in ${SystemClock.elapsedRealtime() - started}ms" }
        }
    }

    override fun onCreate() {
        super.onCreate()
        speech = OfflineSpeech(this)
        outputs = AudioOutputRouting(this, app.audioOutput, app.outputState, ::pauseForOutput)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioTrackProvider { config, attributes, sessionId ->
                        DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, sessionId).also(outputs::observe)
                    }.build()
        }
        player = ExoPlayer.Builder(this, renderers)
            // SPEECH forces Media3 to pause even for MAY_DUCK notifications. Generic media
            // permits normal volume ducking while narration advances, as the owner requested.
            // Media3 still pauses for exclusive transient/call focus and permanent media loss.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(15_000).setSeekForwardIncrementMs(30_000).build()
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        headphoneMusic = HeadphoneMusicMode(this, player, app.audioOutput, { app.outputState.value }, app.headphoneMusicState, ::pauseForOutput, ::updateCarControls)
        chapterPlayer = ChapterSessionPlayer(player, { chapterItems }, chapterClock,
            { queue?.exhausted == false || webPreparing }, ::selectPublicChapter, ::canStartNarration, ::controllerRequest)
        outputs.start(player)
        headphoneMusic.start()
        session = MediaLibrarySession.Builder(this, chapterPlayer, object : MediaLibrarySession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val androidAuto = controller.packageName == "com.google.android.projection.gearhead" &&
                    packageManager.getPackagesForUid(controller.uid).orEmpty().contains(controller.packageName)
                if (controller.packageName != packageName && !controller.isTrusted && !androidAuto) return MediaSession.ConnectionResult.reject()
                val base = super.onConnect(session, controller)
                if (HeadphoneMusicPolicy.carController(controller.packageName)) carCommands[controller] = base.availableSessionCommands to base.availablePlayerCommands
                if (HeadphoneMusicPolicy.blocksCarController(headphoneMusic.enabled, controller.packageName))
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(SessionCommands.EMPTY).setAvailablePlayerCommands(Player.Commands.EMPTY).build()
                val commands = base.availableSessionCommands.buildUpon()
                if (controller.packageName == packageName) listOf(LOAD, REORDER, SKIP_CHAPTER, VOICES, PREVIEW, APPLY_SETTINGS, APPLY_CAST, RESET_LEARNING, WEB_PAGE, WEB_FAILED, WEB_OPTIONS, WEB_BEGIN, WEB_PAUSE, DELETE_BOOK, OUTPUTS, SET_OUTPUT, TEST_OUTPUT, MUSIC_MODE, RESUME_PARAGRAPH, UPDATE_TEXT, BROWSER_READ, BROWSER_APPEND, BROWSER_STOP, START_AT).forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands.build()).build()
            }
            override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) { carCommands.remove(controller) }
            override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(LibraryResult.ofItem(carLibrary.root(), params))
            override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
                futureResult { carLibrary.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) }
            override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
                futureResult { if (page < 0 || pageSize <= 0) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItemList(carLibrary.children(parentId, page, pageSize.coerceAtMost(100)), params) }
            override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
                futureResult {
                    val count = carLibrary.children(parentId, 0, 100).size
                    session.notifyChildrenChanged(browser, parentId, count, params)
                    LibraryResult.ofVoid(params)
                }
            override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
                futureResult { session.notifySearchResultChanged(browser, query, carLibrary.search(query, 0, 100).size, params); LibraryResult.ofVoid(params) }
            override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
                futureResult { if (page < 0 || pageSize <= 0) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItemList(carLibrary.search(query, page, pageSize.coerceAtMost(100)), params) }
            override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                if (blockedCar(controller)) Futures.immediateFailedFuture(IllegalStateException("Use Roadleaf or headset controls in headphone music mode.")) else futureResult(playbackWork = true) { queueMutex.withLock {
                    val item = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: error("Choose an offline book")
                    val selection = carLibrary.selection(item.mediaId, item.requestMetadata.searchQuery)
                    prepareCarSelection(selection)
                } }
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                if (blockedCar(controller)) Futures.immediateFailedFuture(IllegalStateException("Use Roadleaf or headset controls in headphone music mode.")) else futureResult(playbackWork = true) { queueMutex.withLock {
                    val item = mediaItems.firstOrNull() ?: error("Choose an offline book")
                    prepareCarSelection(carLibrary.selection(item.mediaId, item.requestMetadata.searchQuery)).mediaItems.toMutableList()
                } }
            override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                if (blockedCar(controller)) Futures.immediateFailedFuture(IllegalStateException("Use Roadleaf or headset controls in headphone music mode.")) else futureResult(playbackWork = true) { queueMutex.withLock {
                    check(targetPreferences.getString("kind", null) !in setOf("web", "none", "temporary")) { "Choose a book or load the selected webpage first" }
                    val last = app.dao.latestProgress() ?: error("Open Roadleaf and choose a book first")
                    prepareCarSelection(CarLibrary.Selection(last.bookId))
                } }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                when (command.customAction) {
                    BROWSER_READ, BROWSER_APPEND -> {
                        val selected = app.browserSession.value ?: return badValue()
                        if (args.getString("token") != selected.token) return badValue()
                        val text = app.browserText.take(args.getString("payload"), selected.token) ?: return badValue()
                        if (text.length !in 40..BrowserStory.MAX_TEXT) return badValue()
                        val manual = command.customAction == BROWSER_READ
                        val start = args.getString("start")?.let { value -> BrowserStart.entries.firstOrNull { it.name == value } }
                            ?: BrowserStart.RESUME // Older app-only callers keep their explicit resume contract.
                        return asyncResult(playbackWork = manual) { queueMutex.withLock {
                            require(app.browserSession.value?.token == selected.token) { "The browser link changed. Select the story again." }
                            if (!manual) require(browserFeed?.token == selected.token && browserFeed?.bookId == selected.bookId) { "Narration switched to another source. Tap Read story to link this story again." }
                            if (manual) { stopRendering(); app.browserStories.releaseTemporaryExcept(selected.bookId.takeIf { selected.temporary }) }
                            val captured = app.browserStories.capture(selected, text, manual)
                            require(app.browserSession.value?.token == selected.token) { "Browser link stopped. The captured text remains in Library." }
                            if (manual) {
                                if (captured.replaced) {
                                    val cast = app.casts.get(selected.bookId)
                                    val viewpoint = captured.previousChapter?.let { cast.chapterViewpoints[it] }
                                    val choices = cast.chapterViewpoints.filterKeys { it != captured.previousChapter } + if (viewpoint == null) emptyMap() else mapOf(captured.chapter.id to viewpoint)
                                    app.casts.save(selected.bookId, cast.copy(chapterViewpoints = choices))
                                }
                                // A top request selects chapter zero without consulting any saved offset/audio;
                                // AT starts at a chosen word or where an unsaved reading stopped.
                                if (start == BrowserStart.AT) loadBookAt(selected.bookId, captured.chapter.id, args.getInt("offset", -1), autoplay = false)
                                else loadBook(selected.bookId, captured.chapter.id.takeIf { start == BrowserStart.TOP }, autoplay = false, fromParagraph = true)
                                require(app.browserSession.value?.token == selected.token) { "Browser link stopped while preparing the narrator." }
                                browserFeed = BrowserFeed(selected.token, selected.bookId, captured.chapter.id, captured.length, captured.length)
                                playNarration(); fillAhead()
                            } else {
                                browserFeed = browserFeed?.copy(pending = captured.length)
                                if (player.playbackState == Player.STATE_ENDED) scheduleBrowserTail()
                            }
                            val message = if (captured.replaced && !captured.matchedParagraph) "Story refreshed; the saved paragraph changed, so reading starts at the beginning."
                                else if (manual) "Reading ${if (selected.temporary) "your NovelAI story" else selected.title} · ${when (start) { BrowserStart.TOP -> "from top"; BrowserStart.AT -> "from your chosen place"; else -> "resuming paragraph" }}. Follow adds completed paragraphs while visible."
                                else if (!player.playWhenReady) "New paragraphs saved. Playback stays paused; press Play to continue."
                                else "New completed paragraphs saved for narration."
                            Bundle().apply { putString("message", message) }
                        } }
                    }
                    BROWSER_STOP -> {
                        val token = args.getString("token") ?: return badValue()
                        return futureResult { queueMutex.withLock {
                            if (browserFeed?.token == token) { stopRendering(); app.playbackStatus.value = "Browser link stopped • saved story remains in Library" }
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        } }
                    }
                    RESUME_PARAGRAPH -> {
                        val book = args.getString("bookId")
                        val requestedUrl = args.getString("url")
                        if (requestedUrl != null && (webPage?.url != requestedUrl || lateNavigation() != null)) return badValue()
                        replaceWork {
                            persist()
                            val page = webPage
                            if (book == null && page != null) loadWebPage(page, 0, webAutoNext, fromParagraph = true)
                            else loadBook(book ?: snapshot()?.bookId ?: error("Choose a book first."), null, fromParagraph = true)
                        }
                    }
                    START_AT -> {
                        val offset = args.getInt("offset", -1)
                        if (offset < 0) return badValue()
                        val url = args.getString("url")
                        if (url != null) {
                            val page = webPage
                            if (page == null || page.url != LiveWebPages.canonical(url) || lateNavigation() != null || offset > page.text.lastIndex) return badValue()
                            replaceWork { persist(); loadWebPage(page, 0, webAutoNext, startAt = offset) }
                        } else {
                            val book = args.getString("bookId") ?: return badValue()
                            val chapter = args.getString("chapterId") ?: return badValue()
                            replaceWork { persist(); loadBookAt(book, chapter, offset) }
                        }
                    }
                    UPDATE_TEXT -> {
                        val book = args.getString("bookId") ?: return badValue()
                        val uri = args.getString("uri") ?: return badValue()
                        return futureResult { queueMutex.withLock {
                            if (activeChapters.any { it.bookId == book }) {
                                stopRendering(); loading = true; player.stop(); player.clearMediaItems(); loading = false
                                activeChapters = emptyList(); chapterItems = emptyList(); lastProgress = null
                            }
                            val result = app.importer.updateText(book, uri.toUri())
                            val cast = app.casts.get(book)
                            if (cast.chapterViewpoints.keys.any { it in result.chapterIds }) app.casts.save(book,
                                cast.copy(chapterViewpoints = cast.chapterViewpoints.mapKeys { result.chapterIds[it.key] ?: it.key }))
                            val message = if (result.matchedParagraph) "Story updated • resume from the saved paragraph in Library." else "Story updated • the saved paragraph changed or was ambiguous; resume starts at the chapter beginning."
                            app.playbackStatus.value = message
                            SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putString("message", message) })
                        } }
                    }
                    MUSIC_MODE -> { if (!headphoneMusic.setEnabled(args.getBoolean("enabled"))) return badValue() }
                    OUTPUTS -> outputs.refresh()
                    SET_OUTPUT -> {
                        try {
                            val key = args.getString("key").orEmpty()
                            if (headphoneMusic.enabled && !HeadphoneMusicPolicy.headphones(OutputSelection(key), app.outputState.value.devices)) headphoneMusic.setEnabled(false)
                            outputs.select(key)
                        }
                        catch (error: IllegalStateException) { app.playbackStatus.value = error.message.orEmpty(); return badValue() }
                    }
                    TEST_OUTPUT -> replaceWork { previewVoice("Roadleaf output test. This voice should come from the device you selected. Check the sound before you start driving.") }
                    WEB_BEGIN -> {
                        val url = LiveWebPages.canonical(args.getString("url").orEmpty()) ?: return badValue()
                        resettingPlayback = true
                        // The page's text follows shortly; have the narrator ready for it.
                        warmUpNarrator()
                        return futureResult(playbackWork = true) { try { queueMutex.withLock {
                            clearPlayback()
                            val selection = ++webSelection
                            targetPreferences.edit { putString("kind", "web"); putString("url", url) }
                            if (args.getBoolean("fresh", false)) webProgress.clear(url)
                            app.activeNarration.value = "Webpage selected • no audio loaded"
                            app.playbackStatus.value = "Previous book cleared. Reading the displayed webpage…"
                            if (args.getBoolean("navigate", false)) startWebLoading(url, "New webpage book")
                            SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putLong("selection", selection) })
                        } } finally { resettingPlayback = false } }
                    }
                    WEB_PAUSE -> {
                        webSelection++
                        work?.cancel()
                        player.pause()
                        webPreparing = false
                        cancelWebNavigation()
                        app.playbackStatus.value = "Web reading paused"
                    }
                    DELETE_BOOK -> {
                        val id = args.getString("bookId") ?: return badValue()
                        val affectsPlayback = player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId") == id || activeChapters.any { it.bookId == id }
                        return futureResult(playbackWork = affectsPlayback) { queueMutex.withLock {
                            val book = requireNotNull(app.dao.book(id)) { "This book is already removed" }
                            val root = File(filesDir, "books")
                            withContext(Dispatchers.IO) { BookFiles.directory(root, id) }
                            val selected = player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId") == id || activeChapters.any { it.bookId == id }
                            if (selected) clearPlayback()
                            progressMutex.withLock { app.dao.deleteBook(id); lastProgress = null }
                            app.learnedCasts.clear(id); learnedBooks.remove(id)
                            withContext(Dispatchers.IO) { BookFiles.delete(root, id) }
                            app.playbackStatus.value = "Deleted ${book.title} from library"
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        } }
                    }
                    VOICES -> return asyncResult {
                        val catalog = speech.catalog(args.getString("engine").orEmpty())
                        val settings = app.listening.settings.value
                        if (settings.enginePackage.isBlank() || settings.enginePackage == catalog.enginePackage) {
                            adaptiveVoices = AdaptiveVoices.from(catalog, settings); voiceOptionsKey = optionsKey(settings)
                        }
                        Bundle().apply {
                            putString("engine", catalog.enginePackage)
                            putStringArrayList("engineIds", ArrayList(catalog.engines.map { it.packageName }))
                            putStringArrayList("engineLabels", ArrayList(catalog.engines.map { it.label }))
                            putStringArrayList("voiceNames", ArrayList(catalog.voices.map { it.name }))
                            putStringArrayList("voiceLocales", ArrayList(catalog.voices.map { it.localeTag }))
                            putIntArray("voiceQualities", catalog.voices.map { it.quality }.toIntArray())
                            putIntArray("voiceLatencies", catalog.voices.map { it.latency }.toIntArray())
                        }
                    }
                    LOAD -> {
                        val id = args.getString("bookId") ?: return badValue()
                        replaceWork { loadBook(id, args.getString("chapterId")) }
                    }
                    REORDER -> {
                        val ids = args.getStringArrayList("chapters")?.distinct()?.takeIf { it.isNotEmpty() } ?: return badValue()
                        replaceWork {
                            stopRendering()
                            val entries = ids.mapIndexed { index, id -> requireNotNull(app.dao.chapter(id)); QueueEntry(index, id) }
                            app.dao.replaceQueue(entries); buildQueue(null, true)
                        }
                    }
                    SKIP_CHAPTER -> {
                        if (webPage != null) { replaceWork { beginWebNavigation(force = true) }; return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
                        val chapter = player.currentMediaItem?.mediaMetadata?.extras?.getString("chapterId")
                        val start = activeChapters.indexOfFirst { it.id == chapter } + 1
                        if (chapter != null && start in activeChapters.indices) replaceWork {
                            val remaining = activeChapters.drop(start)
                            stopRendering()
                            app.dao.replaceQueue(remaining.mapIndexed { index, entry -> QueueEntry(index, entry.id) })
                            buildQueue(null, true)
                        }
                    }
                    PREVIEW -> {
                        val text = args.getString("text").orEmpty().take(240).ifBlank { "Welcome to Roadleaf. This is your offline narrator, ready for the next chapter." }
                        val voice = args.getString("voice")
                        val pitchScale = args.getFloat("pitchScale", 1f)
                        if (!pitchScale.isFinite() || pitchScale !in 0.85f..1.15f) return badValue()
                        replaceWork { previewVoice(text, voice, pitchScale) }
                    }
                    APPLY_CAST -> {
                        val key = args.getString("book").orEmpty()
                        if (key == activeBookKey()) return futureResult(playbackWork = true) { queueMutex.withLock {
                            clearPlayback()
                            app.playbackStatus.value = "Character voices saved • continue this book from Library"
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        } }
                    }
                    RESET_LEARNING -> {
                        val key = args.getString("book")?.takeIf(String::isNotBlank) ?: return badValue()
                        val selected = key == activeBookKey()
                        return futureResult(playbackWork = selected) { queueMutex.withLock {
                            if (selected) clearPlayback()
                            learnedBooks.remove(key); app.learnedCasts.clear(key)
                            if (selected) app.playbackStatus.value = "Character learning reset • continue this book from Library"
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        } }
                    }
                    APPLY_SETTINGS -> {
                        val book = snapshot()?.bookId
                        val continuePlaying = player.playWhenReady
                        replaceWork {
                            if (book != null) loadBook(book, null, continuePlaying)
                            else { sessionSettings = app.listening.settings.value; player.setPlaybackSpeed(sessionSettings.speed); app.playbackStatus.value = "Voice settings saved" }
                        }
                    }
                    WEB_PAGE -> {
                        val url = LiveWebPages.canonical(args.getString("url").orEmpty()) ?: return badValue()
                        val text = args.getString("text").orEmpty()
                        if (text.length !in 40..LiveWebPages.MAX_TEXT) return badValue()
                        val token = args.getLong("request")
                        val navigation = app.webNavigation.value
                        // An automatic next-chapter load may follow the same site's redirect, never another site.
                        val redirected = navigation != null && args.getString("requested") == navigation.url && LiveWebPages.sameSite(url, navigation.url)
                        if (token != 0L && (navigation == null || navigation.id != token || (navigation.url != url && !redirected))) return badValue()
                        if (token == 0L && (webSelection == 0L || args.getLong("selection") != webSelection || targetPreferences.getString("kind", null) != "web" || targetPreferences.getString("url", null) != url)) return badValue()
                        val page = LiveWebPage(url, args.getString("title").orEmpty().take(240), args.getString("bookTitle").orEmpty().take(240), text,
                            args.getString("next")?.let { LiveWebPages.nextUrl(url, it) })
                        // The next chapter, read while the current one still plays: prepare it, but leave playback alone.
                        if (token != 0L && navigation?.early == true) {
                            stageWebPrefetch(page, token, args.getBoolean("autoNext", true))
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        if (token != 0L) webPreparing = true
                        webViaTab = args.getString("via") == "tab"
                        val startAt = args.getInt("startAt", -1).takeIf { token == 0L && it in text.indices }
                        replaceWork { loadWebPage(page, token, args.getBoolean("autoNext", true), startAt = startAt) }
                    }
                    WEB_FAILED -> {
                        val navigation = app.webNavigation.value
                        // An early read that failed is simply loaded again, the usual way, when the current chapter ends.
                        if (navigation?.early == true && args.getLong("request") == navigation.id) { trace { "early read failed: ${args.getString("message")}" }; dropPrefetch() }
                        else if (args.getLong("request") == navigation?.id) {
                            stopWebLoading(args.getString("message")?.take(600)
                                ?: "Webpage needs attention. Check the page and choose Read loaded chapter.")
                        }
                    }
                    WEB_OPTIONS -> {
                        webAutoNext = args.getBoolean("autoNext", true)
                        // With auto-next off, an early read of the next chapter is dropped; the current chapter keeps playing.
                        if (!webAutoNext) dropPrefetch()
                        if (!webAutoNext && lateNavigation() != null) {
                            work?.cancel()
                            webPreparing = false
                            cancelWebNavigation()
                            player.pause()
                            app.playbackStatus.value = "Auto-next off • choose Read this chapter to continue"
                        }
                    }
                    else -> return super.onCustomCommand(session, controller, command, args)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).setSessionActivity(activity).build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (loading) return
                scope.launch { persist() }; fillAhead()
                app.playbackStatus.value = item?.mediaMetadata?.title?.toString() ?: "Choose a book"
                updateActiveTitle()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && !canStartNarration()) return
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) app.audioOutput.recordEvent("Android media focus loss paused narration.")
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) app.audioOutput.recordEvent("Audio output disconnect paused narration.")
                if (loading) return
                if (!playWhenReady && !resettingPlayback) browserPauseEpoch++
                if (playWhenReady && webWaitingForRead) {
                    player.pause(); app.playbackStatus.value = "Open the web reader and choose Read chapter to continue"
                    return
                }
                // Pausing mid-chapter keeps an early read of the next chapter; only a pending page load waits for Read chapter.
                if (!playWhenReady && (!resettingPlayback || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) && (lateNavigation() != null || webPreparing || player.currentMediaItem?.mediaId == "web-loading")) {
                    webWaitingForRead = true
                    webPreparing = false; work?.cancel(); cancelWebNavigation()
                    app.playbackStatus.value = "Web reading paused • choose Read this chapter to continue"
                }
                if (playWhenReady) fillAhead() else refill?.cancel()
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) app.playbackStatus.value = "Paused for other audio • press Play to resume"
            }
            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                val wasInterrupted = temporaryAudioInterruption.value
                val interrupted = reason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                temporaryAudioInterruption.value = interrupted
                // Notifications can duck without suppression. Exclusive transient/call focus
                // suppresses playback but preserves Play intent until Android returns focus.
                if (interrupted) {
                    scope.launch { persist() }
                    app.playbackStatus.value = "Paused for a call or temporary audio • resumes when audio is available"
                } else if (wasInterrupted && player.playWhenReady) {
                    app.playbackStatus.value = if (webPreparing) "Preparing local narrator…"
                        else player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Playing"
                    fillAhead()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                outputs.playingChanged(isPlaying)
                checkpoint?.cancel()
                if (loading) return
                scope.launch { persist() }
                if (isPlaying && !preview) checkpoint = scope.launch {
                    while (isActive) { delay(sessionSettings.resourceMode.checkpointMs); if (player.isPlaying) persist() }
                }
            }
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                if (loading) return
                val saved = app.listening.settings.value
                if (saved.speed != parameters.speed) app.listening.save(saved.copy(speed = parameters.speed))
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (loading || state != Player.STATE_ENDED) return
                if (player.currentMediaItem?.mediaId == "web-loading") {
                    stopWebLoading("Page loading timed out. Choose Read loaded chapter to retry.")
                }
                else if (preview) { player.pause(); app.playbackStatus.value = "Preview finished. Resume your book from Library." }
                else if (queue?.exhausted == true) {
                    if (browserFeed != null) scheduleBrowserTail()
                    else if (webPage != null) scope.launch { beginWebNavigation() }
                    else if (!scheduleExpeditionTail()) { completed = true; scope.launch { persist() }; app.playbackStatus.value = "Book complete" }
                } else fillAhead()
            }
            override fun onPlayerError(error: PlaybackException) { app.playbackStatus.value = "Playback stopped: ${error.localizedMessage}. Choose Listen to retry." }
        })
        // Roadleaf or a car/headset control connected: get the narrator ready before a story is chosen.
        scope.launch { delay(1_500); warmUpNarrator() }
    }
    private fun badValue() = Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
    private fun blockedCar(controller: MediaSession.ControllerInfo) = HeadphoneMusicPolicy.blocksCarController(headphoneMusic.enabled, controller.packageName)
    private fun updateCarControls() {
        if (!::session.isInitialized) return
        for (controller in session.connectedControllers) carCommands[controller]?.let { (sessionCommands, playerCommands) ->
            session.setAvailableCommands(controller, if (headphoneMusic.enabled) SessionCommands.EMPTY else sessionCommands,
                if (headphoneMusic.enabled) Player.Commands.EMPTY else playerCommands)
        }
    }
    private fun controllerRequest(action: String): Boolean {
        val controller = if (::session.isInitialized) session.controllerForCurrentRequest else null
        val blocked = controller?.let(::blockedCar) == true
        val name = when (controller?.packageName) { packageName -> "Roadleaf/system notification"; "com.google.android.projection.gearhead" -> "Android Auto"; "com.google.android.gms" -> "Google projection/system service"; null -> "Roadleaf internal control"; else -> "external media/headset control" }
        app.audioOutput.recordEvent("$action from $name${if (blocked) " ignored in headphone music mode" else ""}.")
        if (!blocked && browserFeed?.waiting != null) {
            if (action.equals("Pause", true)) browserPauseEpoch++
            if (action.equals("Play", true)) {
                if (browserFeed!!.pending > browserFeed!!.loaded) scheduleBrowserTail(resumeRequested = true)
                else app.playbackStatus.value = "Waiting for new completed paragraphs in the visible NovelAI story."
                return false
            }
            if (action.startsWith("Seek", true)) return false
        }
        // A chapter read from your own browser has ended: Play reads the page your browser shows now, once you have opened
        // the next one there. Roadleaf never turns the browser's pages itself.
        // It never replays the finished chapter's last passage.
        if (!blocked && action.equals("Play", true) && webViaTab && webPage != null && app.webChapterEnded.value == webPage?.url) {
            if (app.browserStatus.value.connected) app.browserPlayRequest.value = app.browserPlayRequest.value + 1
            else app.playbackStatus.value = "Chapter finished • open the next chapter in your browser, then tap Read page on the Roadleaf bubble"
            return false
        }
        return !blocked
    }
    private fun canStartNarration() = outputs.allowPlayback() && headphoneMusic.allowPlayback()
    private fun pauseForOutput(message: String) {
        player.pause(); work?.cancel(); refill?.cancel(); webPreparing = false
        if (lateNavigation() != null) { webWaitingForRead = true; cancelWebNavigation() }
        scope.launch { persist() }
        app.playbackStatus.value = message
    }
    private fun playNarration() {
        check(outputs.allowPlayback()) { app.outputState.value.message }
        check(headphoneMusic.allowPlayback()) { app.headphoneMusicState.value.message }
        player.play()
    }
    private fun <T> futureResult(playbackWork: Boolean = false, action: suspend () -> T): ListenableFuture<T> {
        if (playbackWork) work?.cancel()
        val future = SettableFuture.create<T>()
        val job = scope.launch {
            try {
                val result = action()
                if (playbackWork) loading = false
                future.set(result)
            }
            catch (e: CancellationException) { future.cancel(false) }
            catch (e: Exception) { if (playbackWork) { player.pause(); app.playbackStatus.value = e.message ?: "Cannot prepare book" }; future.setException(e) }
            finally { if (playbackWork) loading = false }
        }
        if (playbackWork) work = job
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return future
    }
    private fun asyncResult(playbackWork: Boolean = false, action: suspend () -> Bundle): ListenableFuture<SessionResult> {
        if (playbackWork) work?.cancel()
        val future = SettableFuture.create<SessionResult>()
        val job = scope.launch {
            try { future.set(SessionResult(SessionResult.RESULT_SUCCESS, action())) }
            catch (e: CancellationException) { future.cancel(false) }
            catch (e: Exception) {
                if (playbackWork) { player.pause(); app.playbackStatus.value = e.message ?: "Cannot prepare story" }
                future.set(SessionResult(SessionError.ERROR_UNKNOWN, Bundle().apply { putString("error", e.message) }))
            }
            finally { if (playbackWork) loading = false }
        }
        if (playbackWork) work = job
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return future
    }
    private fun replaceWork(action: suspend () -> Unit) {
        work?.cancel()
        work = scope.launch { queueMutex.withLock { safely(action) } }
    }
    private suspend fun safely(action: suspend () -> Unit) {
        try { action() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { player.pause(); app.playbackStatus.value = e.message ?: "Narration failed" }
        finally { loading = false }
    }
    private suspend fun stopRendering() {
        val wasResetting = resettingPlayback
        resettingPlayback = true
        try {
            webPreparing = false; dropPrefetch(); prefetchedFrom = null; cancelWebNavigation()
            loading = true; player.pause(); loading = false
            checkpoint?.cancel(); refill?.cancelAndJoin(); browserAdvance?.cancelAndJoin(); persist()
            browserFeed = null
            withContext(Dispatchers.IO) { queue?.close() }; queue = null
            webPage = null; webWaitingForRead = false
        } finally { resettingPlayback = wasResetting }
    }
    private suspend fun clearPlayback() = withContext(NonCancellable) {
        resettingPlayback = true
        try {
            stopRendering()
            loading = true
            try { player.stop(); player.clearMediaItems() } finally { loading = false }
            activeChapters = emptyList(); completed = false; preview = false; lastProgress = null
            chapterItems = emptyList(); chapterClock.reset(); castState = DialogueState(); castChapter = ""
            app.dao.clearQueue()
            webSelection++
            targetPreferences.edit { clear(); putString("kind", "none") }
            app.activeNarration.value = "No book loaded"
        } finally { resettingPlayback = false }
    }
    private fun updateActiveTitle() {
        val metadata = player.currentMediaItem?.mediaMetadata
        app.activeNarration.value = if (metadata == null) "No book loaded" else
            listOfNotNull(metadata.artist?.toString(), metadata.title?.toString()).filter { it.isNotBlank() }.joinToString(" • ")
    }
    private fun chapterItem(id: String, title: String, bookTitle: String, extras: Bundle) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(bookTitle).setExtras(extras)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).setIsPlayable(true).setIsBrowsable(false).build()).build()
    private fun selectPublicChapter(id: String) {
        if (id.startsWith("web:")) {
            val page = webPage ?: return
            val url = id.removePrefix("web:")
            if (url == page.nextUrl) replaceWork { beginWebNavigation(force = true) }
            else if (url == page.url) {
                val automatic = webAutoNext
                replaceWork { stopRendering(); webProgress.clear(url); loadWebPage(page, 0, automatic) }
            }
        } else if (id.startsWith("chapter:")) {
            val chapter = activeChapters.firstOrNull { it.id == id.removePrefix("chapter:") } ?: return
            replaceWork { loadBook(chapter.bookId, chapter.id) }
        }
    }
    /** Start one chapter at an exact text offset, keeping the arranged queue from that chapter on. */
    private suspend fun loadBookAt(bookId: String, chapterId: String, offset: Int, autoplay: Boolean = true) {
        val liveLink = browserFeed?.takeIf { it.bookId == bookId && app.browserSession.value?.token == it.token }
        stopRendering()
        val chapters = app.browserStories.chapters(bookId)
        val chapter = requireNotNull(chapters.firstOrNull { it.id == chapterId }) { "That chapter is no longer in Library." }
        val length = withContext(Dispatchers.IO) { File(chapter.textPath).readText().length }
        require(offset in 0 until length) { "Choose a word inside this chapter." }
        temporaryQueue = chapters.takeIf { app.browserStories.isTemporary(bookId) }
        if (temporaryQueue == null) {
            val previous = app.dao.queue()
            val ids = chapters.map { it.id }.toSet()
            val keep = previous.any { it.chapterId == chapterId } && previous.all { it.chapterId in ids }
            val chosen = if (keep) previous.dropWhile { it.chapterId != chapterId }.map { it.chapterId } else chapters.dropWhile { it.id != chapterId }.map { it.id }
            app.dao.replaceQueue(chosen.mapIndexed { index, id -> QueueEntry(index, id) })
        }
        buildQueue(Progress(bookId, chapterId, offset, 0), autoplay, fromParagraph = true)
        if (liveLink != null) activeChapters.singleOrNull { it.bookId == bookId }?.let { active ->
            val loaded = withContext(Dispatchers.IO) { File(active.textPath).readText().length }
            browserFeed = liveLink.copy(chapterId = active.id, loaded = loaded, pending = loaded, waiting = null)
        }
    }
    private suspend fun loadBook(bookId: String, chapterId: String?, autoplay: Boolean = true, fromParagraph: Boolean = false) {
        val liveLink = browserFeed?.takeIf { it.bookId == bookId && app.browserSession.value?.token == it.token }
        val selected = selectBook(bookId, chapterId)
        val original = selected ?: if (fromParagraph && chapterId == null) app.browserStories.progress(bookId) else null
        val browserEnd = if (!fromParagraph && original != null && app.browserStories.book(bookId)?.sourceId in setOf(BrowserStory.SOURCE, "browser-free")) withContext(Dispatchers.IO) {
            app.browserStories.chapter(original.chapterId)?.let { original.textOffset >= File(it.textPath).readText().length } == true
        } else false
        val saved = if ((!fromParagraph && !browserEnd) || original == null) original else {
            val chapter = requireNotNull(app.browserStories.chapter(original.chapterId))
            original.copy(textOffset = withContext(Dispatchers.IO) { File(chapter.textPath).bufferedReader().use { ParagraphResume.start(it, original.textOffset) } }, audioMs = 0)
        }
        buildQueue(saved, autoplay, fromParagraph || browserEnd)
        if (liveLink != null) activeChapters.singleOrNull { it.bookId == bookId }?.let { chapter ->
            val length = withContext(Dispatchers.IO) { File(chapter.textPath).readText().length }
            browserFeed = liveLink.copy(chapterId = chapter.id, loaded = length, pending = length, waiting = null)
        }
    }
    private suspend fun selectBook(bookId: String, chapterId: String?): Progress? {
        stopRendering()
        val book = app.browserStories.book(bookId)
        // A disconnected Dropbox provider must not block chapters already saved offline.
        if (book?.sourceId == ExpeditionJson.SOURCE_ID) runCatching { app.importer.refreshExpedition() }
        val chapters = app.browserStories.chapters(bookId)
        require(chapters.isNotEmpty()) { "No chapters found." }
        val stored = if (chapterId == null) app.browserStories.progress(bookId) else null
        val saved = stored?.takeUnless { it.textOffset == Int.MAX_VALUE }
        val nextAfterCompleted = if (book?.sourceId == ExpeditionJson.SOURCE_ID && stored?.textOffset == Int.MAX_VALUE)
            chapters.firstOrNull { it.ordinal > (chapters.firstOrNull { chapter -> chapter.id == stored.chapterId }?.ordinal ?: -1) } else null
        if (book?.sourceId == ExpeditionJson.SOURCE_ID && chapterId == null && stored?.textOffset == Int.MAX_VALUE && nextAfterCompleted == null)
            error("Waiting for the next validated Expedition chapter. Choose Chapters to replay an earlier one.")
        temporaryQueue = chapters.takeIf { app.browserStories.isTemporary(bookId) }
        if (temporaryQueue != null) return saved
        val start = chapters.indexOfFirst { it.id == (chapterId ?: nextAfterCompleted?.id ?: saved?.chapterId) }.coerceAtLeast(0)
        val previous = app.dao.queue()
        val chapterIds = chapters.map { it.id }.toSet()
        val resumeQueue = saved != null && previous.any { it.chapterId == saved.chapterId } && previous.all { it.chapterId in chapterIds }
        val chosen = if (resumeQueue) previous.dropWhile { it.chapterId != saved?.chapterId }.map { it.chapterId } else chapters.drop(start).map { it.id }
        app.dao.replaceQueue(chosen.mapIndexed { index, id -> QueueEntry(index, id) })
        return saved
    }
    private suspend fun buildQueue(saved: Progress?, autoplay: Boolean, fromParagraph: Boolean = false) {
        val first = prepareQueue(saved, fromParagraph)
        player.setMediaItem(first, if (fromParagraph) 0 else resumeAudioMs(saved, first)); player.prepare()
        loading = false
        if (autoplay) playNarration()
        app.playbackStatus.value = first.mediaMetadata.title?.toString() ?: "Ready"
        updateActiveTitle()
        fillAhead()
    }
    private suspend fun prepareCarSelection(selection: CarLibrary.Selection): MediaSession.MediaItemsWithStartPosition {
        val saved = selectBook(selection.bookId, selection.chapterId)
        val first = prepareQueue(saved)
        app.playbackStatus.value = first.mediaMetadata.title?.toString() ?: "Ready"
        return MediaSession.MediaItemsWithStartPosition(listOf(first), 0, resumeAudioMs(saved, first))
    }
    private fun resumeAudioMs(saved: Progress?, first: MediaItem): Long =
        if (restoredAudioKey.isNotBlank() && restoredAudioKey == first.mediaMetadata.extras?.getString("audioKey")) saved?.audioMs ?: 0 else 0
    private suspend fun prepareQueue(saved: Progress?, fromParagraph: Boolean = false): MediaItem {
        loading = true; completed = false; preview = false
        app.playbackStatus.value = "Preparing your narrator…"
        player.clearMediaItems()
        sessionSettings = app.listening.settings.value
        player.setPlaybackSpeed(sessionSettings.speed)
        pronounce = SpeechText.pronouncer(app.dao.pronunciationSnapshot().map { it.written to it.spoken })
        activeChapters = temporaryQueue ?: app.dao.queuedChapters()
        val books = activeChapters.map { it.bookId }.distinct().associateWith { requireNotNull(app.browserStories.book(it)) }
        expeditionBookId = books.values.singleOrNull()?.takeIf { it.sourceId == ExpeditionJson.SOURCE_ID }?.id
        chapterItems = activeChapters.map { chapter -> chapterItem("chapter:${chapter.id}", chapter.title, books[chapter.bookId]?.title.orEmpty(),
            Bundle().apply { putString("chapterId", chapter.id); putString("bookId", chapter.bookId) }) }
        val timingKey = saved?.bookId.orEmpty()
        val timingMatches = saved != null && chapterTiming.getString("$timingKey:chapter", null) == saved.chapterId &&
            chapterTiming.getInt("$timingKey:offset", -1) == saved.textOffset && (fromParagraph || chapterTiming.getLong("$timingKey:audioMs", -1) == saved.audioMs)
        restoredAudioKey = if (timingMatches) chapterTiming.getString("$timingKey:audioKey", "").orEmpty() else ""
        sessionCast = browserCast(activeChapters.firstOrNull()?.bookId.orEmpty())
        libraryCasts = books.keys.associateWith(::browserCast)
        learnedBooks.clear(); books.keys.forEach { learnedBooks[it] = app.learnedCasts.get(it) }
        if (libraryCasts.values.any { it.enabled && it.adaptive }) prepareVoiceOptions(sessionSettings)
        castChapter = saved?.chapterId.orEmpty()
        castState = if (timingMatches) VoiceCastPreferences.state(chapterTiming.getString("$timingKey:castContext", "")) else DialogueState()
        if (saved != null && sessionCast.sceneOnly) {
            val chapter = activeChapters.firstOrNull { it.id == saved.chapterId }
            if (chapter != null) {
                val presence = withContext(Dispatchers.IO) { SceneCast.before(sessionCast, File(chapter.textPath).bufferedReader(), saved.textOffset) }
                castState = castState.copy(sceneNames = presence.sceneNames, sceneExplicit = presence.sceneExplicit)
            }
        }
        chapterClock.reset(saved?.let { "chapter:${it.chapterId}" }, if (timingMatches) chapterTiming.getLong("$timingKey:startMs", 0) else 0)
        queue = ChapterNarrationQueue(activeChapters, books, saved, resumeChars = if (timingMatches && !fromParagraph && !sessionCast.sceneOnly) chapterTiming.getInt("$timingKey:segmentChars", 0).takeIf { it in 1..1800 } else null, paragraphChunks = true,
            // A fresh start, a chosen word or a paragraph start speaks sooner with a short opening passage; an exact resume keeps its saved passage.
            quickOpening = saved == null || fromParagraph)
        targetPreferences.edit { clear(); putString("kind", if (temporaryQueue == null) "library" else "temporary") }
        return requireNotNull(nextMediaItem()) { "No readable speech in queue." }
    }
    private fun fillAhead() {
        trace { "refill requested loading=$loading preview=$preview web=$webPreparing interruption=${temporaryAudioInterruption.value} playing=${player.playWhenReady} active=${refill?.isActive} items=${player.mediaItemCount - player.currentMediaItemIndex}" }
        if (loading || preview || webPreparing || webWaitingForRead || temporaryAudioInterruption.value || lateNavigation() != null || !player.playWhenReady || queue == null || refill?.isActive == true || browserAdvance?.isActive == true) return
        refill = scope.launch { safely {
            if (player.currentMediaItemIndex > 1) player.removeMediaItems(0, player.currentMediaItemIndex - 1)
            // Casting has extra voice transitions. Keep one additional bounded WAV ready in
            // Battery saver, without enlarging its cache or synthesizing the whole chapter.
            val profile = if (webPage != null) sessionCast else libraryCasts.values.firstOrNull() ?: sessionCast
            val target = NarrationBuffer.parts(sessionSettings.resourceMode, profile, adaptiveVoices.male + adaptiveVoices.female)
            while (player.playWhenReady && player.mediaItemCount - player.currentMediaItemIndex < target) {
                if (!appendPart()) break
            }
            maybePrefetchWeb()
            // The next chapter's prepared opening is kept too, until it plays.
            val protected = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri?.path }.toSet() +
                listOfNotNull(prefetch?.prepared?.rendered?.file?.absolutePath)
            speech.trimCache(protected, sessionSettings.resourceMode)
            if (player.playbackState == Player.STATE_ENDED && queue?.exhausted == true) {
                if (browserFeed != null) scheduleBrowserTail()
                else if (webPage != null) beginWebNavigation()
                else if (!scheduleExpeditionTail()) { completed = true; persist(); app.playbackStatus.value = "Book complete" }
            }
        } }
    }
    /** At EOF, fetch only newly validated chapter files, then continue from the next number. */
    private fun scheduleExpeditionTail(): Boolean {
        val chapterId = player.currentMediaItem?.mediaMetadata?.extras?.getString("chapterId") ?: return false
        val bookId = player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId") ?: return false
        if (expeditionBookId != bookId || activeChapters.lastOrNull()?.id != chapterId) return false
        if (expeditionAdvance?.isActive == true) return true
        expeditionAdvance = scope.launch {
            try { queueMutex.withLock { safely {
                val book = app.dao.book(bookId) ?: return@safely
                if (book.sourceId != ExpeditionJson.SOURCE_ID || player.playbackState != Player.STATE_ENDED || queue?.exhausted != true) return@safely
                completed = true; persist()
                app.playbackStatus.value = "Checking for the next validated Expedition chapter…"
                val current = app.dao.chapter(chapterId) ?: return@safely
                val refreshed = runCatching { app.importer.refreshExpedition() }.getOrElse {
                    app.playbackStatus.value = "Chapter complete • saved chapters remain offline; check the Expedition folder later"
                    return@safely
                }
                val next = app.dao.chapters(bookId).firstOrNull { it.ordinal == current.ordinal + 1 }
                if (next == null) {
                    app.playbackStatus.value = "Chapter complete • waiting for the next validated Expedition chapter"
                    return@safely
                }
                if (refreshed.book?.id != bookId || !player.playWhenReady) {
                    app.playbackStatus.value = "Next Expedition chapter ready • press Play to continue"
                    return@safely
                }
                val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                if (audio.isMusicActive || audio.mode in setOf(android.media.AudioManager.MODE_IN_CALL,
                        android.media.AudioManager.MODE_IN_COMMUNICATION, android.media.AudioManager.MODE_RINGTONE)) {
                    player.pause()
                    app.playbackStatus.value = "Next Expedition chapter ready • paused for other audio"
                    return@safely
                }
                loadBook(bookId, next.id)
            } } } finally { expeditionAdvance = null }
        }
        return true
    }
    /** Catch up only after the old snapshot finished; never reset speech already in progress. */
    private fun scheduleBrowserTail(resumeRequested: Boolean = false) {
        if (browserAdvance?.isActive == true) return
        browserAdvance = scope.launch {
            var appended = false
            try { queueMutex.withLock { safely {
                val feed = browserFeed ?: return@safely
                if (app.browserSession.value?.token != feed.token || player.playbackState != Player.STATE_ENDED || queue?.exhausted != true) return@safely
                val resume = player.playWhenReady || resumeRequested
                val pauseEpoch = browserPauseEpoch
                browserFeed = feed.copy(waiting = feed.loaded)
                completed = false; persist()
                app.playbackStatus.value = "Waiting for new completed paragraphs • open the NovelAI story to capture more"
                if (!resume || feed.pending <= feed.loaded) return@safely
                // ExoPlayer can release focus at EOF. An arriving paragraph must not seize it
                // from music started during that gap. Explicit Play remains a user choice.
                if (!resumeRequested && !headphoneMusic.enabled) {
                    delay(250)
                    val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    if (audio.isMusicActive || audio.mode in setOf(android.media.AudioManager.MODE_IN_CALL, android.media.AudioManager.MODE_IN_COMMUNICATION, android.media.AudioManager.MODE_RINGTONE)) {
                        player.pause(); app.playbackStatus.value = "New paragraphs saved • paused for other audio; press Play to continue"; return@safely
                    }
                }
                val chapters = app.browserStories.chapters(feed.bookId)
                val chapter = chapters.singleOrNull { it.id == feed.chapterId } ?: return@safely
                val book = app.browserStories.book(feed.bookId) ?: return@safely
                queue?.close()
                queue = ChapterNarrationQueue(listOf(chapter), mapOf(book.id to book), Progress(book.id, chapter.id, feed.loaded, 0), paragraphChunks = true)
                activeChapters = chapters
                browserFeed = feed.copy(loaded = feed.pending)
                val item = nextMediaItem()
                if (item == null) { browserFeed = browserFeed?.copy(waiting = feed.pending); persist(); return@safely }
                val index = player.mediaItemCount
                player.addMediaItem(item); player.seekTo(index, 0); player.prepare()
                if (resumeRequested && pauseEpoch == browserPauseEpoch) playNarration()
                app.playbackStatus.value = if (player.playWhenReady) "Continuing new NovelAI paragraphs" else "New paragraphs ready • playback stays paused"
                appended = true
            } } } finally { browserAdvance = null; if (appended) fillAhead() }
        }
    }
    private suspend fun appendPart(): Boolean {
        val item = nextMediaItem() ?: return false
        val ended = player.playbackState == Player.STATE_ENDED
        val index = player.mediaItemCount
        player.addMediaItem(item)
        if (ended && player.playWhenReady) { player.seekTo(index, 0); player.prepare() }
        return true
    }
    private suspend fun nextMediaItem(): MediaItem? {
        val current = queue ?: return null
        val part = withContext(Dispatchers.IO) { current.peek() } ?: return null
        val started = SystemClock.elapsedRealtime()
        trace { "render begin offset=${part.offset} chars=${part.text.length}" }
        val context = if (castChapter == part.chapter.id) castState else DialogueState()
        val profile = if (part.book.sourceId == "live-web") sessionCast else libraryCasts[part.book.id] ?: BookVoiceCast()
        val bookKey = learningKey(part)
        val prior = learnedBooks.getOrPut(bookKey) { app.learnedCasts.get(bookKey) }
        val adaptation = AdaptiveCast.prepare(profile, prior, adaptiveVoices, part.chapter.id, part.offset, part.text, context)
        val next = withContext(Dispatchers.IO) { current.peekFollowing() }
        val rendered = CastSpeechRenderer(speech, cacheDir).render(cleaned(part), adaptation.cast, context, sessionSettings, pronounce,
            lookahead = next?.takeIf { it.chapter.id == part.chapter.id }?.text.orEmpty())
        val file = rendered.file
        val duration = withContext(Dispatchers.IO) { WaveAudio.durationMs(file) }
        trace { "render ready offset=${part.offset} elapsed=${SystemClock.elapsedRealtime() - started}ms duration=${duration}ms" }
        awaitTemporaryAudio()
        currentCoroutineContext().ensureActive()
        // No suspension between consuming the source span and returning it for player insertion.
        current.consume()
        commitLearning(bookKey, adaptation.next)
        castState = rendered.next; castChapter = part.chapter.id
        val key = if (part.book.sourceId == "live-web") "web:${part.chapter.id}" else "chapter:${part.chapter.id}"
        val span = chapterClock.append(key, duration, next?.chapter?.id != part.chapter.id)
        val extras = Bundle().apply {
            putString("chapterId", part.chapter.id); putString("bookId", part.book.id); putInt("offset", part.offset)
            putInt("segmentChars", part.text.length)
            putLong(ChapterSessionPlayer.START_MS, span.startMs); putLong(ChapterSessionPlayer.DURATION_MS, span.durationMs)
            putString("audioKey", file.name); putString("castContext", VoiceCastPreferences.encode(context))
            putInt("learnedWindows", prior.windows)
            webPage?.let { putString("webUrl", it.url) }
        }
        return MediaItem.Builder().setMediaId("${part.chapter.id}:${part.offset}").setUri(file.toURI().toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(part.chapter.title).setArtist(part.book.title).setExtras(extras).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).build()).build()
    }
    private fun cleaned(part: NarrationPart) = (part.book.sourceId == "live-web").let { web ->
        NarrationText.part(part.text, part.chapter.title, part.offset == 0, web, site = if (web) part.chapter.id else null)
    }
    private fun learningKey(part: NarrationPart) = if (part.book.sourceId == "live-web") WebBookLinks.resolve(part.chapter.id)?.bookUrl ?: part.book.id else part.book.id
    private fun activeBookKey(): String? = webPage?.url?.let { WebBookLinks.resolve(it)?.bookUrl } ?: (
        if (targetPreferences.getString("kind", null) == "web") WebBookLinks.resolve(targetPreferences.getString("url", "").orEmpty())?.bookUrl
        else snapshot()?.bookId ?: activeChapters.firstOrNull()?.bookId)
    private fun optionsKey(settings: ListeningSettings) = "${settings.enginePackage}|${settings.localeTag}|${settings.voiceName}"
    private suspend fun prepareVoiceOptions(settings: ListeningSettings) {
        if (voiceOptionsKey == optionsKey(settings)) return
        adaptiveVoices = AdaptiveVoices.from(speech.catalog(settings.enginePackage), settings)
        voiceOptionsKey = optionsKey(settings)
    }
    private fun commitLearning(book: String, value: LearnedCast) {
        if (learnedBooks[book] != value) { learnedBooks[book] = value; if (!app.browserStories.isTemporary(book)) app.learnedCasts.save(book, value) }
    }
    private fun browserCast(book: String): BookVoiceCast {
        val linked = app.browserSession.value?.takeIf { it.bookId == book }
        if (linked?.useProfiles == false || app.browserStories.isTemporary(book) && linked?.profileBookId == null)
            return BookVoiceCast(enabled = false, adaptive = false)
        return app.casts.get(app.browserStories.profileBook(book))
    }
    private suspend fun previewVoice(text: String, voice: String? = null, pitchScale: Float = 1f) {
        stopRendering(); loading = true; completed = false; preview = true; activeChapters = emptyList()
        player.clearMediaItems(); sessionSettings = app.listening.settings.value
        app.playbackStatus.value = "Preparing voice preview…"
        val file = speech.render(text, sessionSettings.copy(voiceName = voice ?: sessionSettings.voiceName, pitch = (sessionSettings.pitch * pitchScale).coerceIn(0.7f, 1.3f)))
        awaitTemporaryAudio()
        player.setPlaybackSpeed(sessionSettings.speed)
        player.setMediaItem(MediaItem.Builder().setMediaId("preview").setUri(file.toURI().toString()).setMediaMetadata(MediaMetadata.Builder().setTitle("Voice preview").setArtist("Roadleaf").build()).build())
        player.prepare(); loading = false; playNarration(); app.playbackStatus.value = "Voice preview"
        updateActiveTitle()
    }
    private fun snapshot(): Progress? {
        if (loading || preview) return null
        val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return null
        if (extras.containsKey("webUrl")) return null
        browserFeed?.takeIf { it.bookId == extras.getString("bookId") }?.waiting?.let { end -> return Progress(requireNotNull(extras.getString("bookId")), requireNotNull(extras.getString("chapterId")), end, 0) }
        return Progress(extras.getString("bookId") ?: return null, extras.getString("chapterId") ?: return null,
            if (completed) Int.MAX_VALUE else extras.getInt("offset"), if (completed) 0 else player.currentPosition.coerceAtLeast(0))
    }
    private fun persistWeb(): Boolean {
        val webExtras = player.currentMediaItem?.mediaMetadata?.extras
        if (!loading && !preview && webExtras?.containsKey("webUrl") == true && player.currentMediaItem?.mediaId != "web-loading") {
            webExtras.getString("webUrl")?.let { webProgress.save(it, webExtras.getInt("offset"), player.currentPosition, webExtras.getInt("segmentChars"), webExtras.getLong(ChapterSessionPlayer.START_MS),
                audioKey = webExtras.getString("audioKey").orEmpty(), castContext = webExtras.getString("castContext").orEmpty()) }
            return true
        }
        return false
    }
    private suspend fun persist() {
        if (persistWeb()) return
        val value = snapshot() ?: return
        val timing = player.currentMediaItem?.mediaMetadata?.extras
        progressMutex.withLock {
            if (lastProgress?.copy(updatedAt = 0) != value.copy(updatedAt = 0)) {
                app.browserStories.saveProgress(value); lastProgress = value
                saveTiming(value, timing)
            }
        }
    }
    private fun saveTiming(value: Progress, extras: Bundle?) {
        if (app.browserStories.isTemporary(value.bookId)) return
        chapterTiming.edit {
            putString("${value.bookId}:chapter", value.chapterId); putInt("${value.bookId}:offset", value.textOffset)
            putLong("${value.bookId}:audioMs", value.audioMs); putLong("${value.bookId}:startMs", extras?.getLong(ChapterSessionPlayer.START_MS) ?: 0)
            putInt("${value.bookId}:version", NarrationText.VERSION)
            putInt("${value.bookId}:segmentChars", extras?.getInt("segmentChars") ?: 0)
            putString("${value.bookId}:audioKey", extras?.getString("audioKey"))
            putString("${value.bookId}:castContext", extras?.getString("castContext"))
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() {
        headphoneMusic.close()
        outputs.close()
        persistWeb()
        val value = snapshot()
        if (value != null) saveTiming(value, player.currentMediaItem?.mediaMetadata?.extras)
        app.webNavigation.value = null
        scope.cancel(); queue?.close(); speech.close(); session.release(); chapterPlayer.release()
        if (value != null) CoroutineScope(Dispatchers.IO).launch { app.browserStories.saveProgress(value) }
        super.onDestroy()
    }

    private fun cancelWebNavigation() {
        webRequest++; webTimeout?.cancel(); webTimeout = null; app.webNavigation.value = null
    }

    private suspend fun awaitTemporaryAudio() {
        // Keep prepared text/audio pending without seeking, restarting or requesting focus over
        // the alert. Manual Pause/permanent loss still cancel the owning work/refill job.
        if (temporaryAudioInterruption.value) temporaryAudioInterruption.first { !it }
    }

    private fun stopWebLoading(message: String) {
        work?.cancel(); webPreparing = false; webWaitingForRead = true
        loading = true
        try { player.pause(); player.stop(); player.clearMediaItems() } finally { loading = false }
        app.activeNarration.value = "Webpage selected • no chapter audio loaded"
        // Publish the result before canceling the Activity's scan, including the watchdog path.
        app.playbackStatus.value = message
        cancelWebNavigation()
    }

    private suspend fun beginWebNavigation(force: Boolean = false) {
        val page = webPage ?: return
        if (lateNavigation() != null || webPreparing || (!force && !player.playWhenReady)) return
        val next = page.nextUrl
        if (next == null || (!force && !webAutoNext)) {
            dropPrefetch()
            player.pause()
            app.playbackStatus.value = when {
                next == null -> "End of available chapter links"
                webViaTab -> if (app.browserReader.followPages) "Chapter finished • open the next chapter in your browser and Roadleaf continues"
                    else "Chapter finished • open the next chapter in your browser, then press Play"
                else -> "Chapter finished • Auto-next is off"
            }
            app.webChapterEnded.value = page.url
            return
        }
        // Claim the transition before suspending: both ENDED and the refill job can arrive here.
        webPreparing = true
        try {
            val caller = currentCoroutineContext()[Job]
            if (refill != caller) refill?.cancelAndJoin()
            persist()
            // A pause during file preparation cancels the handoff rather than restarting playback.
            if (!force && !player.playWhenReady) return
            // The next chapter was read while this one played: continue at once.
            val ahead = prefetch?.takeIf { it.from == page.url && it.page != null }
            if (ahead != null) {
                if (ahead.prepared == null && ahead.job?.isActive == true) {
                    app.playbackStatus.value = "Preparing the next chapter…"
                    ahead.job?.join()
                }
                val prepared = ahead.prepared
                if (prepared != null && prefetch === ahead) {
                    prefetch = null
                    trace { "next chapter starts from its early read" }
                    // Reached from the buffer-filling job itself: the new chapter fills its buffer with a fresh job.
                    if (refill == caller) refill = null
                    if (installWebPage(prepared, ahead.autoNext, requirePlaying = !force, replacing = page.url))
                        runCatching { app.websites.remember(prepared.page.url, prepared.page.bookTitle, prepared.page.title) }
                    // Otherwise paused or another book was chosen meanwhile: leave playback as it is.
                    return
                }
            }
            // Not read early, or still loading: load it now, the usual way.
            dropPrefetch()
            startWebLoading(next, page.bookTitle)
        } finally { webPreparing = false }
    }

    private suspend fun startWebLoading(url: String, title: String) {
        warmUpNarrator()
        val file = withContext(Dispatchers.IO) { LoadingAudio.file(cacheDir) }
        awaitTemporaryAudio()
        val token = ++webRequest
        webWaitingForRead = false; loading = true
        player.setMediaItem(MediaItem.Builder().setMediaId("web-loading").setUri(file.toURI().toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Loading the chapter page…").setArtist(title)
                .setExtras(Bundle().apply { putString("webUrl", url) }).build()).build())
        player.setPlaybackSpeed(1f); player.prepare(); playNarration(); loading = false
        targetPreferences.edit { putString("kind", "web"); putString("url", url) }
        app.playbackStatus.value = "Loading the chapter page • reading starts once it loads (30-second maximum)"
        // Scanning starts at once; a page that has loaded and settled is read without waiting ten seconds.
        app.webNavigation.value = WebNavigation(token, url, SystemClock.elapsedRealtime())
        updateActiveTitle()
        webTimeout = scope.launch {
            delay(LiveWebPages.MAX_LOAD_MS)
            if (app.webNavigation.value?.id == token && !webPreparing) {
                stopWebLoading("Page was not readable within 30 seconds. Choose Read loaded chapter to retry.")
                return@launch
            }
            delay(90_000)
            if (app.webNavigation.value?.id == token) stopWebLoading("Local voice preparation timed out. Choose Read loaded chapter to retry.")
        }
    }

    private suspend fun loadWebPage(page: LiveWebPage, token: Long, autoNext: Boolean, fromParagraph: Boolean = false, startAt: Int? = null) {
        if (token != 0L && (app.webNavigation.value?.id != token || !player.playWhenReady)) return
        app.webChapterEnded.value = null
        app.websites.remember(page.url, page.bookTitle, page.title)
        if (token == 0L) {
            stopRendering()
            loading = true
            webPage = page
            val silence = withContext(Dispatchers.IO) { LoadingAudio.file(cacheDir) }
            awaitTemporaryAudio()
            player.setMediaItem(MediaItem.Builder().setMediaId("web-loading").setUri(silence.toURI().toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Preparing web narrator…").setArtist(page.bookTitle)
                    .setExtras(Bundle().apply { putString("webUrl", page.url) }).build()).build())
            player.setPlaybackSpeed(1f); player.prepare(); playNarration(); loading = false
        }
        webPreparing = true
        try { installWebPage(prepareWebPage(page, fromParagraph, startAt), autoNext, token) }
        finally { webPreparing = false }
    }

    /** Renders a webpage chapter's opening passage. Nothing in playback changes, so this can run while another chapter plays. */
    private suspend fun prepareWebPage(page: LiveWebPage, fromParagraph: Boolean, startAt: Int?): PreparedWebPage {
        val preferences = app.listening.settings.value
        val transform = SpeechText.pronouncer(app.dao.pronunciationSnapshot().map { it.written to it.spoken })
        val profile = app.casts.get(WebBookLinks.resolve(page.url)?.bookUrl.orEmpty())
        val chapter = Chapter(page.url, "live-web", 0, page.title, "")
        val originalCheckpoint = webProgress.read(page.url)
        val checkpoint = if (startAt != null) WebListeningProgress.Checkpoint(startAt.coerceIn(0, page.text.lastIndex), 0, 0)
        else if (!fromParagraph || originalCheckpoint == null) originalCheckpoint else {
            val at = ParagraphResume.start(page.text, originalCheckpoint.offset)
            originalCheckpoint.copy(offset = at, audioMs = 0, segmentChars = 0, audioKey = "", chapterStartMs = if (at == originalCheckpoint.offset) originalCheckpoint.chapterStartMs else 0,
                castContext = originalCheckpoint.castContext.takeIf { at == originalCheckpoint.offset }.orEmpty())
        }
        val saved = checkpoint?.let { Progress("live-web", page.url, it.offset.coerceIn(0, page.text.lastIndex), it.audioMs) }
        val resumeChars = checkpoint?.segmentChars?.takeIf { it in 1..1800 && !profile.sceneOnly }
        val candidate = ChapterNarrationQueue(listOf(chapter), mapOf("live-web" to Book("live-web", page.bookTitle, "Web reading", "live-web", page.url)),
            saved, fastStart = true, resumeChars = resumeChars, paragraphChunks = true) { StringReader(page.text) }
        try {
            val part = requireNotNull(candidate.peek()) { "No readable chapter text" }
            var context = VoiceCastPreferences.state(checkpoint?.castContext)
            if (profile.sceneOnly && part.offset > 0) {
                val presence = withContext(Dispatchers.Default) { SceneCast.before(profile, StringReader(page.text), part.offset) }
                context = context.copy(sceneNames = presence.sceneNames, sceneExplicit = presence.sceneExplicit)
            }
            if (profile.enabled && profile.adaptive) prepareVoiceOptions(preferences)
            val adaptation = AdaptiveCast.prepare(profile, app.learnedCasts.get(learningKey(part)), adaptiveVoices, part.chapter.id, part.offset, part.text, context)
            val next = withContext(Dispatchers.IO) { candidate.peekFollowing() }
            val rendered = CastSpeechRenderer(speech, cacheDir).render(cleaned(part), adaptation.cast, context, preferences, transform,
                lookahead = next?.takeIf { it.chapter.id == part.chapter.id }?.text.orEmpty())
            val duration = withContext(Dispatchers.IO) { WaveAudio.durationMs(rendered.file) }
            return PreparedWebPage(page, candidate, part, next, checkpoint, saved, profile, context, adaptation.cast, rendered, duration, preferences, transform)
        } catch (error: Throwable) { candidate.close(); throw error }
    }

    /**
     * Starts a prepared webpage chapter. Learning is read again, and the opening re-rendered only if its voices changed
     * since it was prepared. [replacing] is the page that must still be current, for a chapter read early.
     */
    private suspend fun installWebPage(prepared: PreparedWebPage, autoNext: Boolean, token: Long = 0L, requirePlaying: Boolean = true, replacing: String? = null): Boolean {
        var installed = false
        try {
            val page = prepared.page
            val part = prepared.part
            val bookKey = learningKey(part)
            val prior = app.learnedCasts.get(bookKey).also { learnedBooks[bookKey] = it }
            val adaptation = AdaptiveCast.prepare(prepared.profile, prior, adaptiveVoices, part.chapter.id, part.offset, part.text, prepared.context)
            // Settings changed while it waited (speed, or even the voice) apply now; only a different voice needs a new rendering.
            val settings = app.listening.settings.value
            val sameVoice = settings.enginePackage == prepared.settings.enginePackage && settings.voiceName == prepared.settings.voiceName &&
                settings.localeTag == prepared.settings.localeTag && settings.pitch == prepared.settings.pitch
            val rendered = if (adaptation.cast == prepared.cast && sameVoice) prepared.rendered
                else CastSpeechRenderer(speech, cacheDir).render(cleaned(part), adaptation.cast, prepared.context, settings, prepared.pronounce,
                    lookahead = prepared.following?.takeIf { it.chapter.id == part.chapter.id }?.text.orEmpty())
            val duration = if (rendered === prepared.rendered) prepared.duration else withContext(Dispatchers.IO) { WaveAudio.durationMs(rendered.file) }
            val file = rendered.file
            awaitTemporaryAudio()
            if ((requirePlaying && !player.playWhenReady) || (token != 0L && app.webNavigation.value?.id != token) || (replacing != null && webPage?.url != replacing)) return false
            currentCoroutineContext().ensureActive()
            prepared.queue.consume()
            commitLearning(bookKey, adaptation.next)
            castState = rendered.next; castChapter = part.chapter.id; sessionCast = prepared.profile
            chapterClock.reset("web:${page.url}", prepared.checkpoint?.chapterStartMs ?: 0)
            val span = chapterClock.append("web:${page.url}", duration, prepared.following == null)
            loading = true
            queue?.close(); queue = prepared.queue; installed = true
            activeChapters = emptyList(); webPage = page; webAutoNext = autoNext; sessionSettings = settings; pronounce = prepared.pronounce
            preview = false; completed = false
            chapterItems = buildList {
                add(chapterItem("web:${page.url}", page.title, page.bookTitle, Bundle().apply { putString("webUrl", page.url) }))
                page.nextUrl?.let { nextUrl -> add(chapterItem("web:$nextUrl", "Next chapter", page.bookTitle, Bundle().apply { putString("webUrl", nextUrl) })) }
            }
            cancelWebNavigation()
            app.webChapterEnded.value = null
            targetPreferences.edit { putString("kind", "web"); putString("url", page.url) }
            val extras = Bundle().apply {
                putString("webUrl", page.url); putInt("offset", part.offset); putInt("segmentChars", part.text.length)
                putLong(ChapterSessionPlayer.START_MS, span.startMs); putLong(ChapterSessionPlayer.DURATION_MS, span.durationMs)
                putString("audioKey", file.name); putString("castContext", VoiceCastPreferences.encode(prepared.context))
                putInt("learnedWindows", prior.windows)
            }
            player.setMediaItem(MediaItem.Builder().setMediaId("web:${page.url}:${part.offset}").setUri(file.toURI().toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(page.title).setArtist(page.bookTitle).setExtras(extras).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).build()).build(),
                if (prepared.checkpoint?.audioKey == file.name) prepared.saved?.audioMs ?: 0 else 0)
            player.setPlaybackSpeed(settings.speed); player.prepare(); playNarration()
            webPreparing = false; loading = false
            app.playbackStatus.value = page.title
            updateActiveTitle()
            fillAhead()
            return true
        } finally { if (!installed) prepared.queue.close() }
    }

    /**
     * Near the end of a webpage chapter, asks the reader for the next chapter's page while this one still plays, so the
     * next chapter can start the moment this one ends instead of after a page load.
     */
    private fun maybePrefetchWeb() {
        val page = webPage ?: return
        val next = page.nextUrl ?: return
        if (!webAutoNext || webViaTab || webPreparing || webWaitingForRead || app.webNavigation.value != null || prefetch != null || prefetchedFrom == page.url) return
        val last = (player.mediaItemCount - 1 downTo 0).firstNotNullOfOrNull { index ->
            player.getMediaItemAt(index).mediaMetadata.extras?.takeIf { it.getString("webUrl") == page.url && it.containsKey("segmentChars") }
        } ?: return
        val preparedEnd = last.getInt("offset") + last.getInt("segmentChars")
        if (!LiveWebPages.prefetchDue(page.text.length, preparedEnd, queue?.exhausted == true)) return
        prefetchedFrom = page.url
        val token = ++webRequest
        prefetch = WebPrefetch(token, next, page.url)
        app.webNavigation.value = WebNavigation(token, next, SystemClock.elapsedRealtime(), early = true)
        trace { "early read of the next chapter, ${page.text.length - preparedEnd} characters before this one's end" }
    }

    /** Keeps the early-read next chapter and renders its opening while the current chapter finishes; playback is untouched. */
    private fun stageWebPrefetch(page: LiveWebPage, token: Long, autoNext: Boolean) {
        val ahead = prefetch?.takeIf { it.token == token } ?: return
        // The page is read; the reader is free again.
        cancelWebNavigation()
        ahead.page = page; ahead.autoNext = autoNext
        ahead.job = scope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                val prepared = prepareWebPage(page, fromParagraph = false, startAt = null)
                if (prefetch === ahead) ahead.prepared = prepared else prepared.queue.close()
                trace { "next chapter ready in ${SystemClock.elapsedRealtime() - started}ms: ${page.url}" }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { trace { "next chapter could not be prepared early: ${error.message}" } }
        }
    }

    /** Forgets an early read of the next chapter; the usual page load then runs when the current chapter ends. */
    private fun dropPrefetch() {
        val ahead = prefetch ?: return
        prefetch = null
        ahead.job?.cancel()
        ahead.prepared?.queue?.close()
        if (app.webNavigation.value?.id == ahead.token) cancelWebNavigation()
    }
}
