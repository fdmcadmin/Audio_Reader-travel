package app.roadleaf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.roadleaf.playback.*

data class CastBookChoice(val id: String, val title: String, val chapterId: String = "")

@Composable fun VoiceCastPanel(
    books: List<CastBookChoice>, selectedBook: String, onBook: (String) -> Unit,
    value: BookVoiceCast, learned: LearnedCast, settings: ListeningSettings, catalog: SpeechCatalog?, busy: Boolean,
    onSave: (String, BookVoiceCast) -> Unit, onPreview: (String, String, Float) -> Unit, onRefresh: () -> Unit, onReset: (String) -> Unit,
    onImportLorebook: (String, BookVoiceCast) -> Unit, onCreateStoryProfile: () -> Unit, onExport: (String, BookVoiceCast) -> Unit = { _, _ -> }, onMoreVoices: () -> Unit = {}
) {
    var draft by remember(selectedBook, value) { mutableStateOf(value) }
    var editing by remember(selectedBook) { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf("") }
    var showLearned by remember(selectedBook) { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    val matches = catalog != null && (settings.enginePackage.isBlank() || catalog.enginePackage == settings.enginePackage)
    val voices = if (matches) catalog!!.voices.filter {
        it.localeTag == settings.localeTag ||
            (catalog.enginePackage == NativeKokoro.ENGINE_ID &&
                it.localeTag.startsWith("en-", ignoreCase = true) &&
                settings.localeTag.startsWith("en-", ignoreCase = true))
    } else emptyList()
    val choices = listOf(Choice("", if (draft.adaptive) "Choose as the reader learns" else "Use narrator")) + voices.map { Choice(it.name, voiceLabel(it.name, catalog?.enginePackage.orEmpty())) }
    val effective = remember(draft, learned, catalog, settings) {
        AdaptiveCast.prepare(draft, learned, catalog?.let { AdaptiveVoices.from(it, settings) } ?: AdaptiveVoices(), "preview", 0, "").cast
    }
    val available = if (matches) AdaptiveVoices.from(catalog!!, settings.copy(voiceName = draft.narratorVoice.ifBlank { settings.voiceName })) else AdaptiveVoices()
    val auditionMale = draft.maleVoice.ifBlank { available.maleDefault }
    val auditionFemale = draft.femaleVoice.ifBlank { available.femaleDefault }
    val auditionMain = draft.mainVoice.ifBlank { effective.mainVoice }
    val sample = "We should stay together. I know the way home."
    @Composable fun role(label: String, voice: String, sample: String, update: (String) -> Unit) {
        ChoicePicker(label, voice, choices, !busy && matches, update)
        val auto = when (label) { "Main character" -> auditionMain; "Female dialogue" -> auditionFemale; else -> auditionMale }
        val resolved = auto.ifBlank { available.narrator }
        Text("Preview: ${voiceLabel(resolved, catalog?.enginePackage.orEmpty()).ifBlank { "Narrator" }}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onPreview(resolved, sample, 1f) }, enabled = !busy && matches) { Text("Hear $label") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Character voices", style = MaterialTheme.typography.titleLarge)
        Text("Each story has its own narrator and character voices. Create a NovelAI story profile before importing its Lorebook, or choose a saved book.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onCreateStoryProfile, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Create NovelAI story voice profile") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onImportLorebook(selectedBook, draft) }, enabled = selectedBook.isNotBlank() && !busy) { Text("Import cast or lorebook") }
            OutlinedButton(onClick = { onExport(selectedBook, draft) }, enabled = selectedBook.isNotBlank() && !busy) { Text("Export cast") }
        }
        Text("Import a Roadleaf cast file, story voice profiles, a NovelAI lorebook or another lorebook or character card (JSON). From lorebooks Roadleaf keeps only character names, nicknames and pronoun hints, never the lorebook text, then gives each character an installed voice. Export saves this book's cast to a file you can import into any book.",
            style = MaterialTheme.typography.bodySmall)
        if (draft.profileSetId.isNotBlank()) TextButton(onClick = { draft = draft.copy(profileSetId = "", characters = draft.characters.map { it.copy(profileId = "") }) }) { Text("Unlink collection · keep voice choices") }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = draft.sceneOnly, onCheckedChange = { draft = draft.copy(sceneOnly = it) })
            Text("Activate profiles only in their scene")
        }
        Text("Scene breaks: ***, ---, ⁂, ###, or [Scene: names]. Names/aliases activate profiles within that scene; the next break clears them. Descriptions are never read aloud.", style = MaterialTheme.typography.bodySmall)
        if (books.isEmpty()) Text("No stories are registered in this installation yet. Tap Create NovelAI story voice profile above, then import the Lorebook without capturing text first.")
        else {
            ChoicePicker("Book", selectedBook, books.map { Choice(it.id, it.title) }, onSelect = onBook)
            ChoicePicker("Book narrator", draft.narratorVoice, listOf(Choice("", "Use default narrator")) + voices.map { Choice(it.name, voiceLabel(it.name, catalog?.enginePackage.orEmpty())) }, !busy && matches) {
                draft = draft.copy(narratorVoice = it)
            }
            Text("This book can have its own narrator. Other books keep their saved voices.", style = MaterialTheme.typography.bodySmall)
            val mode = if (!draft.enabled) "narrator" else if (draft.adaptive) "automatic" else "manual"
            ChoicePicker("Reading voices", mode, listOf(Choice("automatic", "Automatic · learn as I listen"), Choice("narrator", "Narrator only"), Choice("manual", "My assignments only"))) {
                draft = draft.copy(enabled = it != "narrator", adaptive = it == "automatic")
            }
            Text("Dialogue keeps each character’s assigned voice. Story narration can follow the chapter’s viewpoint, independently of the book’s main character. Uncertain speakers keep the narrator. Your assignments take priority.", style = MaterialTheme.typography.bodySmall)
            ChoicePicker("Story narration", if (draft.followViewpoint) "viewpoint" else "narrator", listOf(Choice("viewpoint", "Follow chapter viewpoint"), Choice("narrator", "Narrator by default"))) {
                draft = draft.copy(followViewpoint = it == "viewpoint")
            }
            val chapter = books.firstOrNull { it.id == selectedBook }?.chapterId.orEmpty()
            val perspectiveChoices = listOf(Choice("", "Learn this chapter’s viewpoint"), Choice("@narrator", "Use narrator")) +
                (listOf(draft.mainCharacter.substringBefore(',').trim()) + draft.characters.map { it.name }).filter(String::isNotBlank).distinct().map { Choice(it, it) }
            if (chapter.isNotBlank()) ChoicePicker("This webpage chapter · ${chapter.substringAfterLast('/')}", draft.chapterViewpoints[chapter].orEmpty(), perspectiveChoices) { name ->
                draft = draft.copy(chapterViewpoints = if (name.isBlank()) draft.chapterViewpoints - chapter else draft.chapterViewpoints + (chapter to name))
            }
            Text("A confirmed chapter choice starts immediately. Automatic viewpoint needs repeated clear evidence in that chapter; it does not assume the MC narrates every chapter.", style = MaterialTheme.typography.bodySmall)
            draft.chapterViewpoints.forEach { (id, name) -> Row {
                Text("${id.substringAfterLast('/')} · ${if (name == "@narrator") "Narrator" else name}", Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { draft = draft.copy(chapterViewpoints = draft.chapterViewpoints - id) }) { Text("Remove choice") }
            } }
            if (matches) {
                val engine = catalog!!.engines.firstOrNull { it.packageName == catalog.enginePackage }?.label ?: catalog.enginePackage
                Text("Using $engine · ${voices.size} compatible English/offline voices. Each saved book keeps its own assignments.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onPreview(available.narrator, sample, 1f) }, enabled = !busy) { Text("Hear narrator for comparison") }
                val selected = listOf(available.narrator, auditionMain, auditionFemale, auditionMale).filter(String::isNotBlank)
                if (selected.distinct().size < selected.size) Text("Some roles share the same voice. Choose different installed voices below for clearer distinction.", style = MaterialTheme.typography.bodySmall)
                Text("All previews use the same sentence. Female and male previews let you hear the available defaults even while the book is still learning.", style = MaterialTheme.typography.bodySmall)
            }
            if (voices.isEmpty()) TextButton(onClick = onRefresh, enabled = !busy) { Text(if (busy) "Loading voices…" else "Refresh installed voices") }
            if (matches && voices.size < 2) Text("This engine has fewer than two installed voices for ${settings.localeTag}. Add voices so characters can sound different.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onMoreVoices) { Text("Get more voices") }
            OutlinedTextField(draft.mainCharacter, { draft = draft.copy(mainCharacter = it.take(240)) }, label = { Text("Main character / aliases (optional)") },
                placeholder = { Text(learned.main.ifBlank { "Learn from the story" }) },
                supportingText = { Text("A name here fixes the MC. Separate aliases with commas. Leave blank to learn.") }, modifier = Modifier.fillMaxWidth())
            role("Main character", draft.mainVoice, sample) { draft = draft.copy(mainVoice = it) }
            ChoicePicker("Main character pronouns", draft.mainPronoun.name, CharacterPronoun.entries.map { Choice(it.name, it.label) }) { draft = draft.copy(mainPronoun = CharacterPronoun.valueOf(it)) }
            role("Female dialogue", draft.femaleVoice, sample) { draft = draft.copy(femaleVoice = it) }
            role("Male dialogue", draft.maleVoice, sample) { draft = draft.copy(maleVoice = it) }
            if (catalog?.enginePackage == "com.brahmadeo.supertonic.tts") TextButton(onClick = {
                draft = draft.copy(mainVoice = available.main(if (draft.mainPronoun == CharacterPronoun.SHE) LearnedGender.FEMALE else LearnedGender.MALE), femaleVoice = available.femaleDefault, maleVoice = available.maleDefault)
            }, enabled = voices.size >= 2) { Text("Suggest installed voices") }
            HorizontalDivider()
            Text("Recurring characters", style = MaterialTheme.typography.titleMedium)
            Text("Give a regular character a dedicated voice, or tell Roadleaf which dialogue voice to use. Add aliases for nicknames.", style = MaterialTheme.typography.bodySmall)
            draft.characters.forEachIndexed { index, character ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(character.name, style = MaterialTheme.typography.titleSmall)
                    Text(if (character.role == CastRole.CUSTOM) voiceLabel(character.voice, catalog?.enginePackage.orEmpty()).ifBlank { "Narrator" } else character.role.label,
                        style = MaterialTheme.typography.bodySmall)
                    Text(character.delivery.label, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = {
                            val voice = when (character.role) { CastRole.CUSTOM -> character.voice; CastRole.AUTO -> effective.characters.firstOrNull { it.name == character.name }?.voice.orEmpty(); CastRole.MALE -> auditionMale; CastRole.FEMALE -> auditionFemale; CastRole.NARRATOR -> available.narrator }
                            val lower = character.role != CastRole.NARRATOR && (character.delivery == CharacterDelivery.LOWER ||
                                (character.delivery == CharacterDelivery.FOLLOW_STORY && (effective.characters.firstOrNull { it.name == character.name }?.learnedLower ?: character.startsLower)))
                            onPreview(voice, sample, if (lower) DeliveryCues.LOWER_PITCH else 1f)
                        }, enabled = !busy && matches) { Text("Hear") }
                        TextButton(onClick = { editing = index }) { Text("Edit") }
                        TextButton(onClick = { draft = draft.copy(characters = draft.characters.filterIndexed { i, _ -> i != index }) }) { Text("Remove") }
                    }
                } }
            }
            OutlinedButton(onClick = { editing = draft.characters.size }, enabled = draft.characters.size < 64) { Text("Add character") }
            HorizontalDivider()
            Text("Learned from this book", style = MaterialTheme.typography.titleMedium)
            Text(if (learned.windows == 0) "Starting with the narrator. Nothing learned yet." else
                "${learned.people.size} names noticed · " + if (draft.mainCharacter.isNotBlank()) "MC fixed: ${draft.mainCharacter.substringBefore(',')}" else
                    learned.main.takeIf(String::isNotBlank)?.let { "Likely MC: $it" }.orEmpty().ifBlank { "Still learning the main character" }, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showLearned = !showLearned }, enabled = learned.people.isNotEmpty()) { Text(if (showLearned) "Hide learned names" else "Show learned names") }
            if (showLearned) learned.people.sortedByDescending { it.turns }.forEach { person ->
                val stage = when {
                    person.voice.isNotBlank() -> voiceLabel(person.voice, person.engine)
                    person.turns < 2 || person.windows < 2 -> "Learning · narrator"
                    person.gender == LearnedGender.FEMALE -> "Female dialogue"
                    person.gender == LearnedGender.MALE -> "Male dialogue"
                    else -> "Unclear voice · narrator"
                }
                Text("${person.name} · $stage", style = MaterialTheme.typography.bodySmall)
            }
            if (learned.windows > 0) TextButton(onClick = { reset = true }) { Text("Restart character learning") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                runCatching { draft.validated() }.onSuccess { onSave(selectedBook, it); error = "" }.onFailure { error = it.message.orEmpty() }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = selectedBook.isNotBlank()) { Text("Save this book’s cast") }
            Text("Learning and voices run locally through one engine. Automatic voice choices use installed, labelled voices; otherwise choose defaults above. A main character cannot always be identified reliably. You can correct it here. Previews pause the book; continue from Library after saving.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Restart character learning?") }, text = {
        Text("Start with the narrator again. Keeps your book, reading position, narrator and manual assignments. Continue the book from Library afterward.")
    }, confirmButton = { TextButton(onClick = { onReset(selectedBook); reset = false }) { Text("Restart learning") } }, dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } })
    editing?.let { index ->
        var character by remember(index) { mutableStateOf(draft.characters.getOrNull(index) ?: CastCharacter("")) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Character voice") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(character.name, { character = character.copy(name = it.take(80)) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(character.aliases, { character = character.copy(aliases = it.take(240)) }, label = { Text("Aliases, separated by commas") }, modifier = Modifier.fillMaxWidth())
                ChoicePicker("Voice assignment", character.role.name, CastRole.entries.map { Choice(it.name, it.label) }) { character = character.copy(role = CastRole.valueOf(it)) }
                if (character.role == CastRole.CUSTOM) ChoicePicker("Dedicated voice", character.voice, choices, !busy && matches) { character = character.copy(voice = it) }
                ChoicePicker("Pronouns", character.pronoun.name, CharacterPronoun.entries.map { Choice(it.name, it.label) }) { character = character.copy(pronoun = CharacterPronoun.valueOf(it)) }
                ChoicePicker("Delivery", character.delivery.name, CharacterDelivery.entries.map { Choice(it.name, it.label) }) { character = character.copy(delivery = CharacterDelivery.valueOf(it)) }
                if (character.delivery == CharacterDelivery.FOLLOW_STORY) {
                    Row { Switch(character.startsLower, { character = character.copy(startsLower = it) }); Text("Start with a lower tone", Modifier.padding(12.dp)) }
                    Text("Clear cues can change delivery while the voice stays fixed. Repeated actions toward the main character can update the saved tone. Unclear scenes keep it steady; you can override it here.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(onClick = {
            val characters = draft.characters.toMutableList()
            if (index < characters.size) characters[index] = character else characters.add(character)
            draft = draft.copy(characters = characters); editing = null
        }, enabled = character.name.isNotBlank()) { Text("Done") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}
