package app.roadleaf

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import app.roadleaf.data.*
import app.roadleaf.sources.*
import app.roadleaf.playback.ListeningSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as RoadleafApp
    val books = app.dao.books().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bookmarks = app.dao.bookmarks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val webBooks = app.dao.webBooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val rules = app.dao.pronunciations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val results = MutableStateFlow<List<SourceBook>>(emptyList())
    val chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val settings = app.listening.settings
    init { viewModelScope.launch {
        // Bring the existing reader page into the new quick picks once, without fetching it.
        val prefs = app.getSharedPreferences("web-reader", 0)
        if (!prefs.getBoolean("quickPicksMigrated", false)) {
            prefs.getString("url", null)?.let { app.websites.remember(it) }
            prefs.edit { putBoolean("quickPicksMigrated", true) }
        }
    } }
    fun rememberWebsite(url: String) { viewModelScope.launch { runCatching { app.websites.remember(url) }.onFailure { message.value = "Could not save this website shortcut." } } }
    fun forgetWebsite(bookUrl: String) = task { app.websites.forget(bookUrl); message.value = "Quick pick removed" }
    fun saveSettings(settings: ListeningSettings) { app.listening.save(settings) }
    fun task(action: suspend () -> Unit) { viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { message.value = e.message ?: "Operation failed" } finally { busy.value = false }
    } }
    fun import(uri: Uri) = task { val book = app.importer.importUri(uri); message.value = "Imported ${book.title}" }
    fun sample() = task { app.importer.demo(); message.value = "Sample added. Choose Listen to test your offline voice." }
    fun search(query: String) = task { results.value = app.sources.sources.first { it.id == "gutenberg" }.search(query); message.value = "${results.value.size} catalog results" }
    fun download(action: SourceAction.Download) = task { val book = app.importer.download(action); message.value = "Downloaded ${book.title} for offline listening" }
    fun bookmark(url: String) = task { app.sources.resolve(url); app.dao.bookmark(Bookmark(url.trim(), app.sources.source(url)?.id ?: "author-file", url.trim())); message.value = "Bookmark saved" }
    fun names(written: String, spoken: String) = task {
        require(written.isNotBlank() && spoken.isNotBlank()) { "Enter a name and its pronunciation." }
        require(written.length <= 100 && spoken.length <= 200) { "Keep pronunciation rules short." }
        app.dao.savePronunciation(Pronunciation(written.trim(), spoken.trim())); message.value = "Pronunciation saved. Reload the book to apply it to audio already prepared."
    }
    fun removeName(written: String) = task { app.dao.deletePronunciation(written) }
    fun showChapters(bookId: String) {
        chapters.value = emptyList()
        task {
            val canonical = app.dao.chapters(bookId)
            val savedQueue = app.dao.queue()
            chapters.value = if (savedQueue.isNotEmpty() && savedQueue.all { entry -> canonical.any { it.id == entry.chapterId } }) {
                savedQueue.mapNotNull { entry -> canonical.firstOrNull { it.id == entry.chapterId } } + canonical.filter { chapter -> savedQueue.none { it.chapterId == chapter.id } }
            } else canonical
        }
    }
    fun moveChapter(index: Int, delta: Int) {
        val list = chapters.value.toMutableList(); val target = index + delta
        if (target in list.indices) { val chapter = list.removeAt(index); list.add(target, chapter); chapters.value = list }
    }
}
