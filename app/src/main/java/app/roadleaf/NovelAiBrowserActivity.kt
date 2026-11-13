package app.roadleaf

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roadleaf.browser.*
import app.roadleaf.data.Book
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.UUID

class NovelAiBrowserActivity : ComponentActivity() {
    private val app get() = application as RoadleafApp
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { Setup() } } }
    }
    // Opening Roadleaf brings back a bubble closed with ✕.
    override fun onResume() { super.onResume(); app.browserReader.bubbleClosed = false }
    @Composable private fun Setup() {
        val status by app.browserStatus.collectAsStateWithLifecycle()
        val session by app.browserSession.collectAsStateWithLifecycle()
        val allBooks by remember { app.dao.books() }.collectAsStateWithLifecycle(emptyList())
        val books = allBooks.filter { it.sourceId == BrowserStory.SOURCE }
        // The Library can send a specific, pre-created manuscript profile here.
        val requestedStory = intent?.getStringExtra("novelAiStoryId").orEmpty()
        val requestedTitle = intent?.getStringExtra("novelAiStoryTitle").orEmpty()
        var selected by rememberSaveable { mutableStateOf(requestedStory.ifBlank { session?.takeUnless { it.temporary }?.bookId ?: app.browserReader.lastBook }) }
        var title by rememberSaveable { mutableStateOf(requestedTitle.ifBlank { session?.takeUnless { it.temporary }?.title ?: app.browserReader.lastTitle }) }
        var storyUrl by rememberSaveable { mutableStateOf("") }
        LaunchedEffect(selected, books) {
            if (storyUrl.isBlank()) storyUrl = books.firstOrNull { it.id == selected }?.sourceUrl
                ?.takeIf(BrowserStory::storyAddress).orEmpty()
        }
        // URL handlers such as remote-control apps are not real browsers.
        val installedBrowsers = remember { BrowserApps.installed(this).filter { it.packageName in BrowserApps.known } }
        var browser by rememberSaveable { mutableStateOf(
            session?.browserPackage?.takeIf { pkg -> installedBrowsers.any { it.packageName == pkg } }
                ?: installedBrowsers.firstOrNull { it.packageName == "com.android.chrome" }?.packageName
                ?: installedBrowsers.firstOrNull()?.packageName.orEmpty()) }
        var follow by rememberSaveable { mutableStateOf(app.browserReader.follow) }
        var anyPage by rememberSaveable { mutableStateOf(app.browserReader.anyPage) }
        var followPages by rememberSaveable { mutableStateOf(app.browserReader.followPages) }
        var webBooksIn by rememberSaveable { mutableStateOf(app.browserReader.webBooksIn) }
        var webBooksMenu by remember { mutableStateOf(false) }
        var replace by rememberSaveable { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var browserMenu by remember { mutableStateOf(false) }
        val webApps = remember { NovelAiWebApp.installed(this) }
        // NovelAI steps appear only once NovelAI has been added as a website, or a NovelAI story was read with the bubble.
        val novelAi by app.browserReader.novelAi.collectAsStateWithLifecycle()
        LazyColumn(Modifier.fillMaxSize().systemBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Read in your own browser", style = MaterialTheme.typography.headlineSmall) }
            item { Text("Roadleaf reads the page your browser shows, with your browser's sign-ins. It never turns pages or taps links: you move through the story, and Roadleaf reads what's there.") }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1 · Turn on the browser reader", style = MaterialTheme.typography.titleMedium)
                Text(status.message, style = MaterialTheme.typography.bodyMedium)
                Text("This optional accessibility connection reads, on your phone, only the page you choose in your browser${if (novelAi) " or your NovelAI story box" else ""}. It does not type, tap links, generate, collect passwords, copy cookies or upload text.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text(if (status.connected) "Accessibility settings" else "Enable browser reader") }
            } } }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("2 · Read a page", style = MaterialTheme.typography.titleMedium)
                Text("Open a chapter in your browser, tap Roadleaf's small bubble at the edge of the screen, then Read page. Resume continues a page you read before.")
                Text("When the chapter ends, open the next one in your browser. Roadleaf continues by itself, or press Play on the bubble, the notification or your headset.")
                Row { Checkbox(followPages, { followPages = it; app.browserReader.followPages = it }); Text("Continue by itself when I open the next chapter", Modifier.padding(top = 12.dp)) }
                Row { Checkbox(anyPage, { anyPage = it; app.browserReader.anyPage = it }); Text("Show the bubble on every site", Modifier.padding(top = 12.dp)) }
                Text("Sign in on the site in your browser first if it has accounts; locked or paid chapters are not read.", style = MaterialTheme.typography.bodySmall)
            } } }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("3 · Where web books open", style = MaterialTheme.typography.titleMedium)
                Text("Continue reading on your Quick picks, Add website book and Read webpage open here.")
                Box { OutlinedButton(onClick = { webBooksMenu = true }) { Text(when (webBooksIn) {
                    null -> "Ask each time"
                    WebBookOpening.READER -> "Roadleaf's reader"
                    else -> installedBrowsers.firstOrNull { it.packageName == webBooksIn }?.label ?: BrowserApps.label(webBooksIn)
                }) }
                    DropdownMenu(expanded = webBooksMenu, onDismissRequest = { webBooksMenu = false }) {
                        DropdownMenuItem(text = { Text("Ask each time") }, onClick = { webBooksIn = null; app.browserReader.webBooksIn = null; webBooksMenu = false })
                        DropdownMenuItem(text = { Text("Roadleaf's reader") }, onClick = { webBooksIn = WebBookOpening.READER; app.browserReader.webBooksIn = WebBookOpening.READER; webBooksMenu = false })
                        installedBrowsers.forEach { installed -> DropdownMenuItem(text = { Text(installed.label) }, onClick = { webBooksIn = installed.packageName; app.browserReader.webBooksIn = installed.packageName; webBooksMenu = false }) }
                    }
                }
                Text("In your browser, Roadleaf starts reading the page once it opens there. Roadleaf's reader also turns pages by itself, even with the screen off.", style = MaterialTheme.typography.bodySmall)
            } } }
            item { Text("The bubble never opens by itself. Drag it anywhere. To close it, drag it onto the ✕ at the bottom of the screen, or tap ✕ on its card. Reading carries on, and the bubble comes back the next time you open Roadleaf.", style = MaterialTheme.typography.bodySmall) }
            if (novelAi) {
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NovelAI · read your stories", style = MaterialTheme.typography.titleMedium)
                Text("1. Turn on the browser reader above.\n2. Save this story and its exact NovelAI address below.\n3. Open the story, highlight a passage or scroll to your start, then use the Roadleaf bubble.\n4. New text is read as NovelAI writes it.")
                val direct = storyUrl.trim().takeIf(BrowserStory::storyAddress)
                    ?: books.firstOrNull { it.id == selected }?.sourceUrl?.takeIf(BrowserStory::storyAddress)
                OutlinedButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, requireNotNull(direct).toUri()).setPackage(browser)) }.onFailure { app.browserStatus.value = app.browserStatus.value.copy(message = "Cannot open this story in the chosen browser. Check its exact NovelAI address.") } }, enabled = browser.isNotBlank() && direct != null, modifier = Modifier.fillMaxWidth()) { Text("Open this NovelAI story") }
                if (direct == null) Text("Paste this manuscript's own NovelAI address below to enable its direct shortcut.", style = MaterialTheme.typography.bodySmall)
                Text("You can return to your already-open tab instead. Android may open another tab with the same browser sign-in.", style = MaterialTheme.typography.bodySmall)
            } } }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("NovelAI controls", style = MaterialTheme.typography.titleMedium)
                Text("From top, Resume, Read selection, and Visible top start where you choose. Check collection looks for Roadleaf voice profiles linked to this saved text; it never imports the full NovelAI lorebook.")
                Text("Name a new manuscript below or choose a previously saved one. Each named manuscript has its own voice cast and reading position; NovelAI’s general /stories address cannot distinguish them.", style = MaterialTheme.typography.bodySmall)
            } } }
            item { Text("Name or select this NovelAI manuscript", style = MaterialTheme.typography.titleMedium)
                Box { OutlinedButton(onClick = { menu = true }) { Text(books.firstOrNull { it.id == selected }?.title ?: "New browser story") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("New browser story") }, onClick = { selected = ""; title = ""; storyUrl = ""; replace = false; menu = false })
                        books.forEach { book -> DropdownMenuItem(text = { Text(book.title) }, onClick = { selected = book.id; title = book.title; storyUrl = book.sourceUrl?.takeIf(BrowserStory::storyAddress).orEmpty(); replace = false; menu = false }) }
                    }
                }
                if (books.none { it.id == selected }) OutlinedTextField(title, { title = it.take(160) }, label = { Text("Your story title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(storyUrl, { storyUrl = it.take(2048) }, label = { Text("Exact NovelAI story address (optional until known)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    isError = storyUrl.isNotBlank() && !BrowserStory.storyAddress(storyUrl))
                if (storyUrl.isNotBlank() && !BrowserStory.storyAddress(storyUrl)) Text("Use this story's own NovelAI address with its id= value.", color = MaterialTheme.colorScheme.error)
            }
            item { Text("Browser for NovelAI", style = MaterialTheme.typography.titleMedium)
                Box { OutlinedButton(onClick = { browserMenu = true }, enabled = installedBrowsers.isNotEmpty()) { Text(installedBrowsers.firstOrNull { it.packageName == browser }?.label ?: "No supported browser found") }
                    DropdownMenu(expanded = browserMenu, onDismissRequest = { browserMenu = false }) {
                        installedBrowsers.forEach { installed -> DropdownMenuItem(text = { Text(installed.label) }, onClick = { browser = installed.packageName; browserMenu = false }) }
                    }
                }
                Text("Choose a real browser, preferably Chrome. URL-handling apps such as TV Remote cannot read NovelAI.", style = MaterialTheme.typography.bodySmall)
                if (installedBrowsers.isEmpty()) Text("No supported browser found. You can still save and import character voices, but browser playback needs a supported browser.", color = MaterialTheme.colorScheme.error)
            }
            item { Row { Checkbox(follow, { follow = it; app.browserReader.follow = it }); Text("Follow new completed paragraphs", Modifier.padding(top = 12.dp)) }
                Text("Already captured text plays offline and in the background. New text is captured only while this story is visible. A partial final paragraph waits for its next line break; Read story includes the current paragraph.", style = MaterialTheme.typography.bodySmall)
            }
            if (selected.isNotBlank()) item { Row { Checkbox(replace, { replace = it }); Text("Allow one replacement of this saved story", Modifier.padding(top = 12.dp)) }
                if (replace) Text("Use only after confirming this is the same story with earlier edits. Its voice choices stay; the saved paragraph is relocated when possible.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                // Save the named book record first. This must not depend on Accessibility.
                Button(onClick = {
                    val name = title.trim()
                    val existing = books.firstOrNull { it.id == selected }
                    val id = existing?.id ?: UUID.randomUUID().toString()
                    lifecycleScope.launch {
                        try {
                            if (existing == null) app.dao.insertBook(
                                Book(id, name, "Personal NovelAI story", BrowserStory.SOURCE, storyUrl.trim().ifBlank { "https://novelai.net/stories" }))
                            else if (storyUrl.isNotBlank()) app.dao.updateNovelAiStoryUrl(id, storyUrl.trim())
                            selected = id
                            title = name
                            if (browser.isNotBlank()) {
                                app.browserSession.value = BrowserReadingSession(
                                    UUID.randomUUID().toString(), id, name, browser, follow, replace)
                                    .also { app.browserReader.remember(it) }
                            }
                            app.browserReader.showNovelAi(true)
                            replace = false
                            val current = app.browserStatus.value
                            app.browserStatus.value = current.copy(message = when {
                                !current.connected -> "Saved '$name' separately. Import its Lorebook under Voice > Characters. To read from NovelAI, first enable Roadleaf Story Preview's browser reader in Android Accessibility."
                                browser.isBlank() -> "Saved '$name' separately. Choose a supported browser before reading NovelAI."
                                else -> "Saved '$name'. Open its direct shortcut if an exact address was provided, then use the Roadleaf bubble."
                            })
                        } catch (error: Exception) {
                            app.browserStatus.value = app.browserStatus.value.copy(
                                message = "Could not save the story: " + (error.message ?: "unknown error"))
                        }
                    }
                }, enabled = title.isNotBlank() && (storyUrl.isBlank() || BrowserStory.storyAddress(storyUrl)), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (books.any { it.id == selected }) "Select this saved story for reading" else "Save named NovelAI story")
                }
                if (!status.connected) Text("You can save its name and Lorebook now. To capture text, enable Roadleaf Story Preview's Accessibility browser reader.", style = MaterialTheme.typography.bodySmall)
                if (books.any { it.id == selected }) Text("Saved to Roadleaf Library as an independent manuscript with its own character voices.", style = MaterialTheme.typography.bodySmall)
                webApps.forEach { installed -> OutlinedButton(onClick = {
                    // Preserve the selected book ID and cast when opening the installed web app.
                    val saved = books.firstOrNull { it.id == selected }
                    val temporary = saved == null
                    val chosen = BrowserReadingSession(UUID.randomUUID().toString(),
                        saved?.id ?: ("free-" + UUID.randomUUID().toString()),
                        saved?.title ?: BrowserStory.UNSAVED_TITLE,
                        browser.ifBlank { "com.android.chrome" }, follow, replace && !temporary,
                        webAppPackage = installed.packageName, webAppLaunchMs = SystemClock.elapsedRealtime(),
                        temporary = temporary, pinnedBook = !temporary)
                    app.browserSession.value = chosen
                    if (!temporary) app.browserReader.remember(chosen)
                    replace = false
                    window.decorView.postDelayed({ startActivity(installed.launch) }, 400)
                }, enabled = status.connected, modifier = Modifier.fillMaxWidth()) {
                    Text(if (books.any { it.id == selected }) "Open installed NovelAI web app for $title"
                         else "Open installed NovelAI web app (unsaved reading)")
                } }
                if (webApps.isNotEmpty()) Text("For the installed web app, use this button each time you start a link. It verifies the installed NovelAI package and binds its window instead of relying on a page title.", style = MaterialTheme.typography.bodySmall)
                if (session != null) TextButton(onClick = { app.browserSession.value = null; app.browserStatus.value = status.copy(message = "Browser link stopped. Saved story remains in Library.") }) { Text("Stop browser link") }
            }
            }
            item { TextButton(onClick = { finish() }) { Text("Back to Roadleaf") } }
        }
    }
}
