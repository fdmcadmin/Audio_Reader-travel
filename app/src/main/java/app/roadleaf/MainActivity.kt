package app.roadleaf

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.app.SearchManager
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import app.roadleaf.playback.*
import app.roadleaf.sources.SourceAction
import app.roadleaf.sources.LiveWebPages
import app.roadleaf.sources.WebBookLinks
import app.roadleaf.data.Book
import app.roadleaf.importing.CastFiles
import app.roadleaf.browser.BrowserStory
import app.roadleaf.browser.BrowserApps
import app.roadleaf.browser.WebBookOpening
import app.roadleaf.importing.CharacterLorebook
import kotlinx.coroutines.flow.MutableStateFlow

@UnstableApi
class MainActivity : ComponentActivity() {
    private val pendingSearch = MutableStateFlow<String?>(null)
    private val pendingCast = MutableStateFlow<String?>(null)
    private fun acceptSearch(intent: Intent?) {
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) pendingSearch.value = intent.getStringExtra(SearchManager.QUERY).orEmpty().take(200)
        intent?.getStringExtra("voiceCastBook")?.let { pendingCast.value = it; intent.removeExtra("voiceCastBook") }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptSearch(intent) }
    // Opening Roadleaf brings back a browser bubble closed with ✕, like a driver app's.
    override fun onResume() { super.onResume(); (application as RoadleafApp).browserReader.bubbleClosed = false }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); acceptSearch(intent); setContent {
        MaterialTheme(colorScheme = darkColorScheme(
            primary = Color(0xFF9FD9B1), onPrimary = Color(0xFF0E351D), primaryContainer = Color(0xFF284D36),
            background = Color(0xFF101916), surface = Color(0xFF101916), surfaceContainer = Color(0xFF1C2A24),
            surfaceContainerHigh = Color(0xFF23342B), surfaceContainerHighest = Color(0xFF283C31), surfaceContainerLow = Color(0xFF17241E),
            surfaceVariant = Color(0xFF283C31), onSurface = Color(0xFFECF4EE), onSurfaceVariant = Color(0xFFBACCC0),
            secondary = Color(0xFFCFD5AD), outline = Color(0xFF596D60)
        )) { RoadleafScreen() }
    } }

    @Composable private fun RoadleafScreen(vm: LibraryViewModel = viewModel()) {
        var controller by remember { mutableStateOf<MediaController?>(null) }
        var playRequested by remember { mutableStateOf(false) }
        var hasMedia by remember { mutableStateOf(false) }
        var rate by remember { mutableFloatStateOf(1f) }
        var tab by remember { mutableIntStateOf(0) }
        var voiceSection by remember { mutableIntStateOf(0) }
        var castBook by remember { mutableStateOf("") }
        var link by remember { mutableStateOf("") }
        var query by remember { mutableStateOf("") }
        var written by remember { mutableStateOf("") }
        var spoken by remember { mutableStateOf("") }
        var showQueue by remember { mutableStateOf(false) }
        var startChoice by remember { mutableStateOf<StartChoice?>(null) }
        var driveMode by remember { mutableStateOf(false) }
        var showSourceHelp by remember { mutableStateOf(false) }
        var voiceCatalog by remember { mutableStateOf<SpeechCatalog?>(null) }
        var voicesBusy by remember { mutableStateOf(false) }
        var kokoroImportBusy by remember { mutableStateOf(false) }
        var voiceRequest by remember { mutableIntStateOf(0) }
        var browserAction by remember { mutableStateOf<SourceAction.Browser?>(null) }
        var bookToDelete by remember { mutableStateOf<Book?>(null) }
        var showAddWebBook by remember { mutableStateOf(false) }
        var newWebBookUrl by remember { mutableStateOf("") }
        var webMenu by remember { mutableStateOf<String?>(null) }
        var showAllWebBooks by remember { mutableStateOf(false) }
        var lorebookBook by rememberSaveable { mutableStateOf<String?>(null) }
        var lorebookDraft by remember { mutableStateOf<BookVoiceCast?>(null) }
        var lorebookReplace by rememberSaveable { mutableStateOf(false) }
        var showImportChoice by remember { mutableStateOf(false) }
        var showCreateStoryProfile by remember { mutableStateOf(false) }
        var newStoryProfileName by rememberSaveable { mutableStateOf("") }
        var exportBook by rememberSaveable { mutableStateOf<String?>(null) }
        var exportDraft by remember { mutableStateOf<BookVoiceCast?>(null) }
        var showVoiceSources by remember { mutableStateOf(false) }
        var updateBook by rememberSaveable { mutableStateOf<String?>(null) }
        val books by vm.books.collectAsStateWithLifecycle()
        val webBooks by vm.webBooks.collectAsStateWithLifecycle()
        val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
        val rules by vm.rules.collectAsStateWithLifecycle()
        val results by vm.results.collectAsStateWithLifecycle()
        val chapters by vm.chapters.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val busy by vm.busy.collectAsStateWithLifecycle()
        val playback by vm.app.playbackStatus.collectAsStateWithLifecycle()
        val activeTitle by vm.app.activeNarration.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        val outputState by vm.app.outputState.collectAsStateWithLifecycle()
        val headphoneMusic by vm.app.headphoneMusicState.collectAsStateWithLifecycle()
        val outputEvents by vm.app.audioOutput.recentEvents.collectAsStateWithLifecycle()
        val searchRequest by pendingSearch.collectAsStateWithLifecycle()
        val castRequest by pendingCast.collectAsStateWithLifecycle()
        val castRevision by vm.app.casts.changes.collectAsStateWithLifecycle()
        val learnedRevision by vm.app.learnedCasts.changes.collectAsStateWithLifecycle()
        val castBooks = books.map { CastBookChoice(it.id, if (it.sourceId == BrowserStory.SOURCE) "NovelAI story · ${it.title}" else it.title) } + webBooks.filter { WebBookLinks.resolve(it.chapterUrl)?.readable == true }.map { CastBookChoice(it.bookUrl, it.title, it.chapterUrl) }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
        val kokoroArchivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && !kokoroImportBusy) {
                kokoroImportBusy = true
                vm.message.value = "Installing Kokoro's offline voices from this archive. Keep Roadleaf open until the model finishes importing."
                vm.task {
                    try {
                        val result = KokoroModelImport.install(this@MainActivity, uri)
                        voiceCatalog = null
                        vm.message.value = result
                    } catch (error: Exception) {
                        vm.message.value = "Kokoro import failed: " + (error.message ?: "invalid or incomplete model archive")
                    } finally {
                        kokoroImportBusy = false
                    }
                }
            }
        }
        val expeditionFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::linkExpedition) }
        fun openBrowser(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())); vm.rememberWebsite(url) }.onFailure { vm.message.value = "No browser is available." } }
        fun openWebBook(url: String, resume: Boolean = false) {
            startActivity(Intent(this@MainActivity, WebReaderActivity::class.java).putExtra("url", url)
                .putExtra(if (resume) "resumeWebBook" else "loadNewBook", true))
        }
        val novelAiAdded by vm.app.browserReader.novelAi.collectAsStateWithLifecycle()
        // NovelAI is read in your own browser: adding it as a website shows how, and puts it in the Library.
        fun addNovelAi() {
            vm.app.browserReader.showNovelAi(true)
            vm.message.value = "NovelAI added • follow the steps to read your stories in your browser."
            startActivity(Intent(this@MainActivity, NovelAiBrowserActivity::class.java))
        }
        fun openNovelAi() { runCatching { startActivity(Intent(Intent.ACTION_VIEW, "https://novelai.net/stories".toUri())) }.onFailure { vm.message.value = "No browser is available." } }
        // Web books open where you chose: Roadleaf's reader, or your own browser, where the Roadleaf bubble reads the page.
        var openChoice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
        val installedBrowsers = remember { BrowserApps.installed(this@MainActivity) }
        fun openInBrowser(url: String, packageName: String, resume: Boolean) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(packageName)) }
                .onSuccess {
                    vm.rememberWebsite(url)
                    // The browser reader starts reading once the page shows (from your saved place when continuing).
                    if (vm.app.browserStatus.value.connected) vm.app.browserReader.readWhenShown(LiveWebPages.canonical(url) ?: url, fresh = !resume)
                    else vm.message.value = "Turn on Roadleaf's browser reader (Sources → Read in your own browser), then tap the Roadleaf bubble on the page."
                }
                .onFailure { vm.message.value = "That browser isn't available. Choose where web books open in Sources → Read in your own browser." }
        }
        fun openChosen(url: String, resume: Boolean) {
            when (val target = WebBookOpening.target(vm.app.browserReader.webBooksIn, installedBrowsers.map { it.packageName })) {
                WebBookOpening.Target.Ask -> openChoice = url to resume
                WebBookOpening.Target.Reader -> openWebBook(url, resume)
                is WebBookOpening.Target.Browser -> openInBrowser(url, target.packageName, resume)
            }
        }
        fun load(bookId: String, chapterId: String? = null) {
            val active = controller
            if (active == null) { vm.message.value = "Playback service is connecting. Try again shortly."; return }
            active.sendCustomCommand(SessionCommand(NarrationService.LOAD, Bundle.EMPTY), Bundle().apply { putString("bookId", bookId); putString("chapterId", chapterId) })
        }
        fun send(action: String, args: Bundle = Bundle.EMPTY) { controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
        // Start from a word: show the chapter that is playing now, opened at the paragraph being read.
        fun pickStartWord() {
            val extras = controller?.currentMediaItem?.mediaMetadata?.extras
            val url = extras?.getString("webUrl")
            val bookId = extras?.getString("bookId"); val chapterId = extras?.getString("chapterId")
            vm.task {
                startChoice = when {
                    url != null -> {
                        val page = vm.app.readingPage.value?.takeIf { it.url == url } ?: error("Wait for the webpage chapter to start playing, then try again.")
                        val current = WebListeningProgress(getSharedPreferences("web-listening", MODE_PRIVATE)).read(page.url)?.offset ?: 0
                        StartChoice(page.title, page.text, 0, current, url = page.url)
                    }
                    bookId != null && chapterId != null -> {
                        val chapter = vm.app.browserStories.chapter(chapterId) ?: error("That chapter is no longer in Library.")
                        val current = vm.app.browserStories.progress(bookId)?.takeIf { it.chapterId == chapterId }?.textOffset ?: 0
                        val full = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { java.io.File(chapter.textPath).readText() }
                        val window = StartPoint.window(full.length, current)
                        StartChoice(chapter.title, full.substring(window.first, window.last + 1), window.first, current, bookId = bookId, chapterId = chapterId)
                    }
                    else -> error("Start a book or webpage first.")
                }
            }
        }
        val lorebookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val book = lorebookBook
            if (uri != null && book != null) vm.task {
                val source = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= CastFiles.MAX_BYTES) { "Choose a cast or lorebook file under 4 MB." }; output.write(buffer, 0, count) }
                        output.toString("UTF-8")
                    } ?: error("Cannot read this file.")
                }
                // An import always targets one named Library book. Replace starts with a fresh cast
                // while retaining only that book's narrator choice and scene mode.
                val current = lorebookDraft ?: vm.app.casts.get(book)
                val base = if (lorebookReplace) BookVoiceCast(narratorVoice = current.narratorVoice, sceneOnly = current.sceneOnly) else current
                val imported = CastFiles.read(source, base)
                // Delegate installed voices from the selected speech engine and accent.
                val catalog = voiceCatalog?.takeIf { settings.enginePackage.isBlank() || it.enginePackage == settings.enginePackage }
                val installed = catalog?.voices?.filter {
                    it.localeTag.equals(settings.localeTag, true) ||
                        (catalog.enginePackage == NativeKokoro.ENGINE_ID && it.localeTag.startsWith("en-", ignoreCase = true))
                }?.map { it.name }.orEmpty()
                val delegated = VoiceDelegation.assign(imported.cast, installed, imported.cast.narratorVoice.ifBlank { settings.voiceName })
                vm.app.casts.save(book, delegated.cast)
                send(NarrationService.APPLY_CAST, Bundle().apply { putString("book", book) })
                vm.message.value = buildString {
                    append("Imported ${imported.characters} character${if (imported.characters == 1) "" else "s"} from a ${imported.kind.label}")
                    if (imported.found > imported.characters) append(" (the first ${imported.characters} of ${imported.found})")
                    append(". ")
                    if (imported.kind in setOf(CastFiles.Kind.NOVELAI, CastFiles.Kind.LOREBOOK, CastFiles.Kind.CARD))
                        append("Only names, nicknames and pronoun hints were kept, not the lorebook text. ")
                    if (catalog == null || installed.isEmpty()) append("Tap Refresh installed voices, then import again to give each character a voice.")
                    else {
                        append("${delegated.assigned} got an installed voice")
                        if (delegated.shared.isNotEmpty()) append("; ${delegated.shared.size} share a voice, so more voices would help")
                        append(". ")
                        if (delegated.missing.isNotEmpty()) append("${delegated.missing.size} voice${if (delegated.missing.size == 1) "" else "s"} from the file aren't installed here and were replaced. ")
                        if (delegated.unlabelled) append("This speech app doesn't label voices female or male, so check each character with Hear.")
                    }
                }.trim()
                lorebookBook = null; lorebookDraft = null; lorebookReplace = false
                if (catalog != null && delegated.needsMoreVoices) showVoiceSources = true
            }
        }
        val castExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val book = exportBook; val draft = exportDraft
            exportBook = null; exportDraft = null
            if (uri != null && book != null && draft != null) vm.task {
                val title = books.firstOrNull { it.id == book }?.title ?: webBooks.firstOrNull { it.bookUrl == book }?.title ?: "Book"
                val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().substringBefore('-')
                val json = CastFiles.export(draft.validated(), vm.app.learnedCasts.get(book), title, settings.enginePackage, settings.localeTag, version)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("Cannot write this file.")
                }
                vm.message.value = "Saved the cast of $title. Import it into any book to use the same characters and voices."
            }
        }
        val storyUpdatePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val book = updateBook
            if (uri != null && book != null) controller?.let { active -> vm.task {
                val result = active.awaitCommand(NarrationService.UPDATE_TEXT, Bundle().apply { putString("bookId", book); putString("uri", uri.toString()) })
                vm.message.value = result.extras.getString("message") ?: "Story text updated."
            } }
            updateBook = null
        }
        val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) send(NarrationService.MUSIC_MODE, Bundle().apply { putBoolean("enabled", true) })
            else vm.message.value = "Headphone music mode stayed off. Phone permission is needed to pause for calls."
        }
        fun refreshVoices(engine: String) {
            val active = controller ?: return
            val request = ++voiceRequest
            voicesBusy = true
            val future = active.sendCustomCommand(SessionCommand(NarrationService.VOICES, Bundle.EMPTY), Bundle().apply { putString("engine", engine) })
            future.addListener({
                if (request == voiceRequest) {
                    voicesBusy = false
                    runCatching {
                        val result = future.get()
                        check(result.resultCode == SessionResult.RESULT_SUCCESS) { result.extras.getString("error") ?: "Voice list unavailable" }
                        val data = result.extras
                        val labels = data.getStringArrayList("engineLabels").orEmpty()
                        val locales = data.getStringArrayList("voiceLocales").orEmpty()
                        val qualities = data.getIntArray("voiceQualities") ?: intArrayOf()
                        val latencies = data.getIntArray("voiceLatencies") ?: intArrayOf()
                        voiceCatalog = SpeechCatalog(data.getStringArrayList("engineIds").orEmpty().mapIndexed { i, id -> SpeechEngineOption(id, labels.getOrElse(i) { id }) },
                            data.getStringArrayList("voiceNames").orEmpty().mapIndexed { i, name -> OfflineVoiceOption(name, locales.getOrElse(i) { "en-US" }, qualities.getOrElse(i) { 0 }, latencies.getOrElse(i) { 0 }) }, data.getString("engine").orEmpty())
                    }.onFailure { vm.message.value = it.message ?: "Could not load voices" }
                }
            }, ContextCompat.getMainExecutor(this@MainActivity))
        }
        fun togglePlayback() {
            controller?.let { active ->
                if (active.playWhenReady) active.pause() else {
                    if (active.playbackState == Player.STATE_ENDED) {
                        val webUrl = active.currentMediaItem?.mediaMetadata?.extras?.getString("webUrl")
                        if (webUrl != null) { startActivity(Intent(this@MainActivity, WebReaderActivity::class.java).putExtra("url", webUrl)); return }
                        val book = active.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")
                        if (book != null) { load(book); return }
                        active.seekToDefaultPosition()
                    }
                    active.play()
                }
            }
        }
        fun voiceSettings() { runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }.onFailure { vm.message.value = "Open Android Settings → Text-to-speech to install offline voice data." } }
        // Voice data for the selected speech app, or Android's text-to-speech settings when it has no installer.
        fun engineVoices() { runCatching { startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply { if (settings.enginePackage.isNotBlank()) setPackage(settings.enginePackage) }) }.onFailure { voiceSettings() } }
        fun openLink(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }.onFailure { vm.message.value = "No browser is available." } }
        fun resolve(url: String) { runCatching { vm.app.sources.resolve(url) }.onSuccess { action -> when (action) {
            is SourceAction.Download -> vm.download(action)
            is SourceAction.Browser -> browserAction = action
            SourceAction.ImportFile -> importer.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream"))
        } }.onFailure { vm.message.value = it.message ?: "Invalid link" } }
        DisposableEffect(Unit) {
            val future = MediaController.Builder(this@MainActivity, SessionToken(this@MainActivity, ComponentName(this@MainActivity, NarrationService::class.java))).buildAsync()
            future.addListener({ runCatching { controller = future.get() }.onFailure { vm.message.value = "Could not connect to playback: ${it.message}" } }, ContextCompat.getMainExecutor(this@MainActivity))
            onDispose { MediaController.releaseFuture(future); controller = null }
        }
        DisposableEffect(controller) {
            val active = controller
            fun update() { playRequested = active?.playWhenReady == true; hasMedia = (active?.mediaItemCount ?: 0) > 0; rate = active?.playbackParameters?.speed ?: settings.speed }
            val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { update() } }
            active?.addListener(listener); update()
            onDispose { active?.removeListener(listener) }
        }
        LaunchedEffect(tab, controller) { if (tab == 2 && controller != null && voiceCatalog == null) refreshVoices(settings.enginePackage) }
        LaunchedEffect(castRequest, castBooks) {
            val requested = castRequest
            if (requested != null) {
                tab = 2; voiceSection = 1
                if (castBooks.any { it.id == requested }) { castBook = requested; pendingCast.value = null }
            } else if (castBooks.none { it.id == castBook }) castBook = castBooks.firstOrNull()?.id.orEmpty()
        }
        LaunchedEffect(searchRequest, controller, busy) {
            val query = searchRequest
            if (query != null && controller != null && !busy) vm.task {
                val id = if (query.isBlank()) vm.app.dao.latestProgress()?.bookId ?: vm.app.dao.booksPage(1, 0).firstOrNull()?.id
                    else vm.app.dao.searchBooks("%$query%", 1, 0).firstOrNull()?.id
                if (id != null) load(id) else vm.message.value = "No matching offline book. Import one from Library."
                pendingSearch.value = null
            }
        }

        Scaffold(modifier = Modifier.fillMaxSize().systemBarsPadding(), bottomBar = {
            if (!driveMode && hasMedia) Surface(tonalElevation = 6.dp) { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(activeTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(playback, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller?.seekBack() }, enabled = hasMedia, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("−15s") }
                    Button(onClick = ::togglePlayback, enabled = hasMedia, modifier = Modifier.weight(1.5f).heightIn(min = 56.dp)) { Text(if (playRequested) "Pause" else "Play") }
                    OutlinedButton(onClick = { controller?.seekForward() }, enabled = hasMedia, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("+30s") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { send(NarrationService.SKIP_CHAPTER) }, enabled = hasMedia, modifier = Modifier.weight(1f, fill = false)) { Text("Next chapter", textAlign = TextAlign.Center) }
                    if (Features.START_HERE) TextButton(onClick = ::pickStartWord, enabled = hasMedia && !busy, modifier = Modifier.weight(1f, fill = false)) { Text("Start from a word", textAlign = TextAlign.Center) }
                    TextButton(onClick = { val speed = when { rate < 1.2f -> 1.25f; rate < 1.45f -> 1.5f; else -> 1f }; vm.saveSettings(settings.copy(speed = speed)); controller?.setPlaybackSpeed(speed) }, modifier = Modifier.weight(1f, fill = false)) { Text("${rate}× · Local voice", textAlign = TextAlign.Center) }
                }
            } }
        }) { padding -> if (driveMode) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Roadleaf", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(24.dp)); Text(playback, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))
                Button(onClick = ::togglePlayback, enabled = hasMedia, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)) { Text(if (playRequested) "Pause" else "Play", style = MaterialTheme.typography.headlineMedium) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { controller?.seekBack() }, enabled = hasMedia, modifier = Modifier.weight(1f).heightIn(min = 72.dp)) { Text("−15 seconds") }
                    OutlinedButton(onClick = { controller?.seekForward() }, enabled = hasMedia, modifier = Modifier.weight(1f).heightIn(min = 72.dp)) { Text("+30 seconds") }
                }
                OutlinedButton(onClick = { send(NarrationService.SKIP_CHAPTER) }, enabled = hasMedia, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Next chapter") }
                TextButton(onClick = { driveMode = false }) { Text("Back to library") }
                Text("Screen lock and Bluetooth controls continue to work.", style = MaterialTheme.typography.bodySmall)
            }
        } else Column(Modifier.padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(20.dp, 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Roadleaf", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Your stories, your pace · ${packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().substringBefore('-')}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { driveMode = true }) { Text("Drive view") }
            }
            TabRow(selectedTabIndex = tab) { listOf("Library", "Sources", "Voice").forEachIndexed { index, label -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) }) } }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { vm.message.value = "" }) { Text("Dismiss") }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> {
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { showAddWebBook = true }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Add website book") }
                                OutlinedButton(onClick = { importer.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Import file") }
                            }
                        }
                        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("The Meridian Hunt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Choose the Expedition folder in Dropbox once. Roadleaf saves its original Chapter 1 TXT and later validated chapter JSON for offline listening, then moves to the next numbered chapter automatically.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { expeditionFolderPicker.launch(null) }, enabled = !busy) { Text("Choose folder") }
                                if (vm.app.importer.expeditionFolder() != null) TextButton(onClick = vm::refreshExpedition, enabled = !busy) { Text("Check chapters") }
                            }
                            books.firstOrNull { it.sourceId == "expedition-json" }?.let { expedition ->
                                Button(onClick = { load(expedition.id) }, enabled = !busy && controller != null) { Text("Listen to The Meridian Hunt") }
                            }
                        } } }
                        item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Quick picks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("Website books save here automatically with the latest page you opened.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                        if (webBooks.isEmpty()) item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Pick up a webpage story", style = MaterialTheme.typography.titleMedium)
                            Text("Add a book link, or share a chapter from your browser to Roadleaf.", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { startActivity(Intent(this@MainActivity, WebReaderActivity::class.java)) }) { Text("Open web reader") }
                        } } }
                        items(if (showAllWebBooks) webBooks else webBooks.take(3), key = { "web:${it.bookUrl}" }) { book ->
                            val info = WebBookLinks.resolve(book.chapterUrl)
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(info?.site ?: "Website", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(book.chapterTitle.ifBlank { "Saved website link" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { if (info?.readable == true) openChosen(book.chapterUrl, resume = true) else openBrowser(book.chapterUrl) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(if (info?.readable == true) "Continue reading" else "Open website") }
                                    Box {
                                        TextButton(onClick = { webMenu = book.bookUrl }, modifier = Modifier.heightIn(min = 48.dp)) { Text("More") }
                                        DropdownMenu(expanded = webMenu == book.bookUrl, onDismissRequest = { webMenu = null }) {
                                            // Either way, whatever the usual choice is.
                                            if (info?.readable == true) DropdownMenuItem(text = { Text("Continue in Roadleaf's reader") }, onClick = { webMenu = null; openWebBook(book.chapterUrl, resume = true) })
                                            DropdownMenuItem(text = { Text("Open in my browser") }, onClick = {
                                                webMenu = null
                                                val chosen = vm.app.browserReader.webBooksIn?.takeIf { pkg -> installedBrowsers.any { it.packageName == pkg } }
                                                if (chosen != null && info?.readable == true) openInBrowser(book.chapterUrl, chosen, resume = true) else openBrowser(book.chapterUrl)
                                            })
                                            if (info?.readable == true) DropdownMenuItem(text = { Text("Character voices") }, onClick = { webMenu = null; castBook = book.bookUrl; tab = 2; voiceSection = 1 })
                                            DropdownMenuItem(text = { Text("Remove quick pick") }, onClick = { webMenu = null; vm.forgetWebsite(book.bookUrl) })
                                        }
                                    }
                                }
                            } }
                        }
                        if (webBooks.size > 3) item { TextButton(onClick = { showAllWebBooks = !showAllWebBooks }) { Text(if (showAllWebBooks) "Show recent books" else "All website books (${webBooks.size})") } }
                        // Shown only once NovelAI was added as a website, or a NovelAI story was read with the bubble.
                        if (novelAiAdded) item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("NovelAI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text("Your NovelAI stories, read in your browser", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("For separate story voices and bookmarks, first choose Save / link a specific story below. A general NovelAI website link cannot distinguish different manuscripts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { openNovelAi() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Open NovelAI") }
                                TextButton(onClick = { startActivity(Intent(this@MainActivity, NovelAiBrowserActivity::class.java)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("How to read it") }
                            }
                            OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, NovelAiBrowserActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("Save / link a specific NovelAI story") }
                            TextButton(onClick = { vm.app.browserReader.showNovelAi(false) }) { Text("Remove NovelAI") }
                        } } }
                        item { Spacer(Modifier.height(4.dp)); Text("Offline library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text("Imported EPUB, TXT and Expedition chapters work without a connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (books.isEmpty()) item { Text("Your imported books will appear here.", style = MaterialTheme.typography.bodyMedium); TextButton(onClick = vm::sample, enabled = !busy) { Text("Try a short voice test") } }
                        items(books, key = { it.id }) { book -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                            Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(book.author, style = MaterialTheme.typography.bodySmall)
                            Text(if (book.sourceId == BrowserStory.SOURCE) "NovelAI manuscript · separate voice profile" else "Available offline", style = MaterialTheme.typography.labelMedium)
                            Row { Button(onClick = { load(book.id) }) { Text("Listen") }; TextButton(onClick = { vm.showChapters(book.id); showQueue = true }, enabled = !busy) { Text("Chapters") } }
                            TextButton(onClick = { castBook = book.id; tab = 2; voiceSection = 1 }) { Text("Character voices") }
                            if (book.sourceId == BrowserStory.SOURCE) TextButton(onClick = {
                                startActivity(Intent(this@MainActivity, NovelAiBrowserActivity::class.java)
                                    .putExtra("novelAiStoryId", book.id).putExtra("novelAiStoryTitle", book.title))
                            }) { Text("Link / read this story in NovelAI") }
                            TextButton(onClick = { send(NarrationService.RESUME_PARAGRAPH, Bundle().apply { putString("bookId", book.id) }) }, enabled = !busy && controller != null) { Text("Resume paragraph") }
                            if (book.sourceId == "author-file") TextButton(onClick = { updateBook = book.id; storyUpdatePicker.launch(arrayOf("text/plain", "application/octet-stream")) }, enabled = !busy && controller != null) { Text("Update story text (TXT)") }
                            TextButton(onClick = { bookToDelete = book }, enabled = !busy && controller != null) { Text("Delete from library") }
                        } } }
                    }
                    1 -> {
                        item { Text("Project Gutenberg catalog", style = MaterialTheme.typography.titleLarge); OutlinedTextField(query, { query = it }, label = { Text("Title or author") }, modifier = Modifier.fillMaxWidth()); Button(onClick = { vm.search(query) }, enabled = !busy && query.isNotBlank()) { Text("Search OPDS") } }
                        items(results) { result -> Card { Column(Modifier.padding(12.dp)) { Text(result.title); Text(result.author); Row { TextButton(onClick = { resolve(result.epubUrl ?: result.pageUrl) }, enabled = !busy) { Text(if (result.epubUrl != null) "Download EPUB" else "Open book") }; TextButton(onClick = { openBrowser(result.pageUrl) }) { Text("Browser") } } } } }
                        item { HorizontalDivider(); Text("Attach a story link", style = MaterialTheme.typography.titleLarge); OutlinedTextField(link, { link = it }, label = { Text("HTTPS story or official EPUB download link") }, modifier = Modifier.fillMaxWidth()); Row { Button(onClick = { if (BrowserStory.novelAiAddress(link.trim())) addNovelAi() else resolve(link.trim()) }, enabled = !busy && link.isNotBlank()) { Text("Open / import") }; TextButton(onClick = { vm.bookmark(link) }, enabled = !busy && link.isNotBlank()) { Text("Bookmark") } } }
                        item { OutlinedButton(onClick = { if (BrowserStory.novelAiAddress(link.trim())) addNovelAi() else openChosen(link.trim(), resume = false) }, enabled = LiveWebPages.address(link.trim()) != null || BrowserStory.novelAiAddress(link.trim())) { Text("Read webpage") }; Text("Live reading works with most secure (https) story pages, and you can share a chapter link from any browser to Roadleaf. If a site has accounts, sign in on the site first; locked or paid chapters are never read. New pages need a connection; permitted EPUB/TXT imports remain offline.", style = MaterialTheme.typography.bodySmall) }
                        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Read in your own browser", style = MaterialTheme.typography.titleMedium)
                            Text("Roadleaf can read the page your browser shows, with your browser's sign-ins: tap the Roadleaf bubble, then Read page. When a chapter ends, open the next one in your browser and press Play.")
                            if (novelAiAdded) Text("NovelAI stories are read the same way: tap inside the story box, then From top or Resume on the bubble.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { startActivity(Intent(this@MainActivity, NovelAiBrowserActivity::class.java)) }) { Text("Set up browser reading") }
                        } } }
                        item { TextButton(onClick = { showSourceHelp = !showSourceHelp }) { Text(if (showSourceHelp) "Hide source details" else "Supported sources & imports") }; if (showSourceHelp) Text("Any site: Read webpage reads the chapter text a page shows, then follows its Next link. Sign in on sites that have accounts; Roadleaf reads only what your account can see.\n\nAO3: Download → EPUB in your browser for offline copies. Official authorized EPUB links also work.\n\nAuthor-direct: download an authorized EPUB/TXT, then import.") }
                        items(bookmarks, key = { it.url }) { bookmark -> TextButton(onClick = { resolve(bookmark.url) }) { Text(bookmark.title, maxLines = 2) } }
                    }
                    2 -> {
                        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Narrator", "Characters", "Word fixes").forEachIndexed { index, title -> FilterChip(selected = voiceSection == index, onClick = { voiceSection = index }, label = { Text(title) }) }
                        } }
                        if (voiceSection == 0) item { AudioOutputPanel(outputState, controller != null,
                            onSelect = { key -> send(NarrationService.SET_OUTPUT, Bundle().apply { putString("key", key) }) },
                            onRefresh = { send(NarrationService.OUTPUTS) }, onTest = { send(NarrationService.TEST_OUTPUT) },
                            onShare = { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, outputState.report(packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() + " / Android " + android.os.Build.VERSION.RELEASE) + "\n" + headphoneMusic.report() + "Recent Roadleaf controls:\n" + outputEvents.joinToString("\n")), "Share Roadleaf output test")) },
                            music = headphoneMusic, lastEvent = outputEvents.lastOrNull().orEmpty(), onMusic = { enabled ->
                                if (enabled && ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                    callPermission.launch(android.Manifest.permission.READ_PHONE_STATE)
                                else send(NarrationService.MUSIC_MODE, Bundle().apply { putBoolean("enabled", enabled) })
                            }) }
                        if (voiceSection == 0) item { VoiceSettingsPanel(settings, voiceCatalog, voicesBusy, controller != null, ::refreshVoices,
                            onApply = { vm.saveSettings(it); send(NarrationService.APPLY_SETTINGS); vm.message.value = "Narrator settings saved" },
                            onPreview = { value, text -> vm.saveSettings(value); send(NarrationService.PREVIEW, Bundle().apply { putString("text", text) }) },
                            onAndroidSettings = ::voiceSettings, onMoreVoices = { showVoiceSources = true },
                            onImportKokoro = { kokoroArchivePicker.launch(arrayOf("*/*")) },
                            onDownloadKokoro = { openLink(NativeKokoro.MODEL_URL) },
                            kokoroInstalled = NativeKokoro.installed(this@MainActivity), kokoroImportBusy = kokoroImportBusy) }
                        if (voiceSection == 1) item {
                            val cast = remember(castBook, castRevision) { vm.app.casts.get(castBook) }
                            val learned = remember(castBook, learnedRevision) { vm.app.learnedCasts.get(castBook) }
                            VoiceCastPanel(castBooks, castBook, { castBook = it; pendingCast.value = null }, cast, learned, settings, voiceCatalog, voicesBusy,
                                onSave = { key, value ->
                                    vm.app.casts.save(key, value)
                                    send(NarrationService.APPLY_CAST, Bundle().apply { putString("book", key) })
                                    vm.message.value = "Cast saved for this book. Continue reading from Library."
                                }, onPreview = { voice, text, pitchScale -> send(NarrationService.PREVIEW, Bundle().apply { putString("voice", voice); putString("text", text); putFloat("pitchScale", pitchScale) }) },
                                onRefresh = { refreshVoices(settings.enginePackage) }, onReset = { key ->
                                    controller?.let { active -> vm.task {
                                        active.awaitCommand(NarrationService.RESET_LEARNING, Bundle().apply { putString("book", key) })
                                        vm.message.value = "Learning reset. Continue this book from Library."
                                    } }
                                }, onImportLorebook = { key, draft -> lorebookBook = key; lorebookDraft = draft; showImportChoice = true },
                                onCreateStoryProfile = { newStoryProfileName = ""; showCreateStoryProfile = true },
                                onExport = { key, draft -> exportBook = key; exportDraft = draft; castExporter.launch((castBooks.firstOrNull { it.id == key }?.title ?: "Book").replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Book" } + " cast.json") },
                                onMoreVoices = { showVoiceSources = true })
                        }
                        if (voiceSection == 2) {
                            item { Text("Word fixes", style = MaterialTheme.typography.titleLarge); Text("Correct a name or phrase without changing the surrounding words.", style = MaterialTheme.typography.bodyMedium) }
                            item { Text("Whole names and phrases, case insensitive. Reload your book to apply new rules."); OutlinedTextField(written, { written = it }, label = { Text("Written name") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(spoken, { spoken = it }, label = { Text("Say it like…") }, modifier = Modifier.fillMaxWidth()); Button(onClick = { vm.names(written, spoken) }, enabled = !busy) { Text("Save pronunciation") } }
                            items(rules, key = { it.written }) { rule -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${rule.written} → ${rule.spoken}", Modifier.weight(1f)); TextButton(onClick = { vm.removeName(rule.written) }) { Text("Remove") } } }
                        }
                    }
                }
            }
        } }
        browserAction?.let { action -> AlertDialog(onDismissRequest = { browserAction = null }, title = { Text("Open source") }, text = { Text(action.guidance) }, confirmButton = { TextButton(onClick = { openBrowser(action.url); browserAction = null }) { Text("Open browser") } }, dismissButton = { TextButton(onClick = { browserAction = null }) { Text("Close") } }) }
        openChoice?.let { (url, resume) ->
            var keep by remember { mutableStateOf(true) }
            AlertDialog(onDismissRequest = { openChoice = null }, title = { Text("Open web books in") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Roadleaf's reader turns pages for you, even with the screen off. In your own browser, the Roadleaf bubble reads the page shown; you open the next chapter and press Play.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (keep) vm.app.browserReader.webBooksIn = WebBookOpening.READER; openChoice = null; openWebBook(url, resume) }) { Text("Roadleaf's reader") }
                    installedBrowsers.forEach { browser ->
                        TextButton(onClick = { if (keep) vm.app.browserReader.webBooksIn = browser.packageName; openChoice = null; openInBrowser(url, browser.packageName, resume) }) { Text(browser.label) }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(keep, { keep = it })
                        Text("Remember my choice (change it in Sources → Read in your own browser)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { openChoice = null }) { Text("Cancel") } })
        }
        if (showAddWebBook) AlertDialog(onDismissRequest = { showAddWebBook = false }, title = { Text("Add website book") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste a chapter link from any site. It opens in Roadleaf's reader and the book is saved to Quick picks. Sign in on the site there if it has accounts. A NovelAI link adds NovelAI and shows how to read your stories in your own browser.")
                OutlinedTextField(newWebBookUrl, { newWebBookUrl = it }, label = { Text("Chapter web address") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
            }
        }, confirmButton = { TextButton(onClick = {
            showAddWebBook = false
            val url = newWebBookUrl.trim()
            when {
                // NovelAI is read in your own browser, not Roadleaf's reader: show how.
                BrowserStory.novelAiAddress(url) -> addNovelAi()
                LiveWebPages.address(url) != null -> openChosen(url, resume = false)
                else -> resolve(url)
            }
            newWebBookUrl = ""
        }, enabled = WebBookLinks.resolve(newWebBookUrl.trim()) != null || BrowserStory.novelAiAddress(newWebBookUrl.trim())) { Text("Add book") } }, dismissButton = { TextButton(onClick = { showAddWebBook = false }) { Text("Cancel") } })
        bookToDelete?.let { book -> AlertDialog(onDismissRequest = { bookToDelete = null }, title = { Text("Delete ${book.title}?") }, text = {
            Text("Remove this book's local text, listening progress and queue entries. If it is queued or playing, playback stops. Your other books and voice settings stay saved.")
        }, confirmButton = { TextButton(onClick = {
            val active = controller
            bookToDelete = null
            if (active != null) vm.task {
                active.awaitCommand(NarrationService.DELETE_BOOK, Bundle().apply { putString("bookId", book.id) })
                vm.message.value = "Deleted ${book.title} from library"
            }
        }) { Text("Delete book") } }, dismissButton = { TextButton(onClick = { bookToDelete = null }) { Text("Keep book") } }) }
        if (showCreateStoryProfile) AlertDialog(
            onDismissRequest = { showCreateStoryProfile = false },
            title = { Text("Create a NovelAI story voice profile") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Each manuscript gets its own voice cast. Import its Lorebook now, then connect the NovelAI editor later. No story text or existing voices are overwritten.")
                OutlinedTextField(newStoryProfileName, { newStoryProfileName = it.take(160) },
                    label = { Text("Name of this NovelAI story") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            } },
            confirmButton = { Button(onClick = {
                val name = newStoryProfileName.trim()
                showCreateStoryProfile = false
                vm.task {
                    val id = java.util.UUID.randomUUID().toString()
                    vm.app.dao.insertBook(Book(id, name, "Personal NovelAI story", BrowserStory.SOURCE, "https://novelai.net/stories"))
                    vm.app.browserReader.showNovelAi(true)
                    castBook = id
                    pendingCast.value = null
                    tab = 2; voiceSection = 1
                    vm.message.value = "Created $name. Import its Lorebook here. When ready, link the named story in NovelAI; its voice cast will stay separate."
                }
            }, enabled = newStoryProfileName.isNotBlank()) { Text("Create profile") } },
            dismissButton = { TextButton(onClick = { showCreateStoryProfile = false }) { Text("Cancel") } }
        )
        if (showImportChoice) {
            val target = castBooks.firstOrNull { it.id == lorebookBook }?.title ?: "this book"
            AlertDialog(onDismissRequest = { showImportChoice = false; lorebookBook = null; lorebookDraft = null },
                title = { Text("Import voices into $target") },
                text = { Text("Choose whether to add characters to this story's saved cast or replace only this story's cast. Other stories are not changed. For NovelAI, save each manuscript as its own named story first.") },
                confirmButton = {
                    TextButton(onClick = {
                        lorebookReplace = false; showImportChoice = false; lorebookPicker.launch(arrayOf("*/*"))
                    }) { Text("Add to this cast") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            lorebookReplace = true; showImportChoice = false; lorebookPicker.launch(arrayOf("*/*"))
                        }) { Text("Replace this cast") }
                        TextButton(onClick = { showImportChoice = false; lorebookBook = null; lorebookDraft = null }) { Text("Cancel") }
                    }
                })
        }
        if (showVoiceSources) VoiceSourcesDialog(voiceCatalog?.let { c -> c.engines.firstOrNull { it.packageName == c.enginePackage }?.label } ?: "your speech app", onEngineVoices = ::engineVoices, onOpen = ::openLink) { showVoiceSources = false }
        startChoice?.let { choice -> StartFromWordDialog(choice, onDismiss = { startChoice = null }) { at ->
            send(NarrationService.START_AT, Bundle().apply {
                putInt("offset", at)
                if (choice.url != null) putString("url", choice.url) else { putString("bookId", choice.bookId); putString("chapterId", choice.chapterId) }
            })
            startChoice = null
        } }
        if (showQueue) AlertDialog(onDismissRequest = { showQueue = false }, title = { Text("Chapter queue") }, text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) { itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter -> Column {
                Text(chapter.title)
                Row { TextButton(onClick = { load(chapter.bookId, chapter.id); showQueue = false }) { Text("Start here") }; TextButton(onClick = { vm.moveChapter(index, -1) }, enabled = index > 0) { Text("Up") }; TextButton(onClick = { vm.moveChapter(index, 1) }, enabled = index < chapters.lastIndex) { Text("Down") } }
            } } }
        }, confirmButton = { TextButton(onClick = { controller?.sendCustomCommand(SessionCommand(NarrationService.REORDER, Bundle.EMPTY), Bundle().apply { putStringArrayList("chapters", ArrayList(chapters.map { it.id })) }); showQueue = false }, enabled = !busy && chapters.isNotEmpty() && controller != null) { Text("Play arranged queue") } }, dismissButton = { TextButton(onClick = { showQueue = false }) { Text("Close") } })
    }
}
