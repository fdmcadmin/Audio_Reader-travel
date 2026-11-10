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
        var selected by rememberSaveable { mutableStateOf(session?.takeUnless { it.temporary }?.bookId ?: app.browserReader.lastBook) }
        var title by rememberSaveable { mutableStateOf(session?.takeUnless { it.temporary }?.title ?: app.browserReader.lastTitle) }
        val installedBrowsers = remember { BrowserApps.installed(this) }
        var browser by rememberSaveable { mutableStateOf(session?.browserPackage ?: installedBrowsers.firstOrNull()?.packageName ?: "com.android.chrome") }
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
                Text("1. Turn on the browser reader above.\n2. Open NovelAI in your browser and open your story.\n3. Tap inside your story box, tap the Roadleaf bubble, then From top or Resume.\n4. New text is read as NovelAI writes it. When the reading catches up, generate more, then press Play or Resume.")
                OutlinedButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, "https://novelai.net/stories".toUri()).setPackage(browser)) } }, modifier = Modifier.fillMaxWidth()) { Text("Open NovelAI in browser") }
                Text("You can return to your already-open tab instead. Android may open another tab with the same browser sign-in.", style = MaterialTheme.typography.bodySmall)
            } } }
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("NovelAI controls", style = MaterialTheme.typography.titleMedium)
                Text("From top starts at the first paragraph. Resume continues where you stopped, including new text. Stories not saved to Library stay out of it. Check collection looks for Roadleaf voice profiles linked to the same saved text; it never opens or imports a full NovelAI lorebook.")
                Text("Use the optional setup below only to save a new story, replace earlier edits, or verify the installed web app.", style = MaterialTheme.typography.bodySmall)
            } } }
            item { Text("Optional · save to Library", style = MaterialTheme.typography.titleMedium)
                Box { OutlinedButton(onClick = { menu = true }) { Text(books.firstOrNull { it.id == selected }?.title ?: "New browser story") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("New browser story") }, onClick = { selected = ""; title = ""; replace = false; menu = false })
                        books.forEach { book -> DropdownMenuItem(text = { Text(book.title) }, onClick = { selected = book.id; title = book.title; replace = false; menu = false }) }
                    }
                }
                if (books.none { it.id == selected }) OutlinedTextField(title, { title = it.take(160) }, label = { Text("Your story title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            item { Text("Browser for NovelAI", style = MaterialTheme.typography.titleMedium)
                Box { OutlinedButton(onClick = { browserMenu = true }) { Text(installedBrowsers.firstOrNull { it.packageName == browser }?.label ?: BrowserApps.label(browser)) }
                    DropdownMenu(expanded = browserMenu, onDismissRequest = { browserMenu = false }) {
                        installedBrowsers.forEach { installed -> DropdownMenuItem(text = { Text(installed.label) }, onClick = { browser = installed.packageName; browserMenu = false }) }
                    }
                }
                Text("Chromium-based browsers (Chrome, Edge, Brave, Samsung Internet, Opera, Vivaldi) expose the story editor most reliably.", style = MaterialTheme.typography.bodySmall)
            }
            item { Row { Checkbox(follow, { follow = it; app.browserReader.follow = it }); Text("Follow new completed paragraphs", Modifier.padding(top = 12.dp)) }
                Text("Already captured text plays offline and in the background. New text is captured only while this story is visible. A partial final paragraph waits for its next line break; Read story includes the current paragraph.", style = MaterialTheme.typography.bodySmall)
            }
            if (selected.isNotBlank()) item { Row { Checkbox(replace, { replace = it }); Text("Allow one replacement of this saved story", Modifier.padding(top = 12.dp)) }
                if (replace) Text("Use only after confirming this is the same story with earlier edits. Its voice choices stay; the saved paragraph is relocated when possible.", style = MaterialTheme.typography.bodySmall)
            }
            item { Button(onClick = {
                selected = selected.ifBlank { UUID.randomUUID().toString() }
                app.browserSession.value = BrowserReadingSession(UUID.randomUUID().toString(), selected, title.trim(), browser, follow, replace).also { app.browserReader.remember(it) }
                replace = false
                app.browserStatus.value = status.copy(message = "Save choice ready for $title. Return to its writing box and tap From top. Use Resume only when you want the saved paragraph.")
            }, enabled = status.connected && title.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Save reading under this story") }
                webApps.forEach { installed -> OutlinedButton(onClick = {
                    app.browserSession.value = BrowserReadingSession(UUID.randomUUID().toString(), "free-${UUID.randomUUID()}", BrowserStory.UNSAVED_TITLE, "com.android.chrome", follow,
                        webAppPackage = installed.packageName, webAppLaunchMs = SystemClock.elapsedRealtime(), temporary = true, pinnedBook = false)
                    replace = false
                    // Let the enabled service bind the new session before the OS window event.
                    window.decorView.postDelayed({ startActivity(installed.launch) }, 400)
                }, enabled = status.connected, modifier = Modifier.fillMaxWidth()) { Text("Open installed NovelAI web app") } }
                if (webApps.isNotEmpty()) Text("For the installed web app, use this button each time you start a link. It verifies the installed NovelAI package and binds its window instead of relying on a page title.", style = MaterialTheme.typography.bodySmall)
                if (session != null) TextButton(onClick = { app.browserSession.value = null; app.browserStatus.value = status.copy(message = "Browser link stopped. Saved story remains in Library.") }) { Text("Stop browser link") }
            }
            }
            item { TextButton(onClick = { finish() }) { Text("Back to Roadleaf") } }
        }
    }
}
