package app.roadleaf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.roadleaf.playback.*
import java.util.Locale

internal data class Choice(val id: String, val title: String, val detail: String = "")

@Composable internal fun ChoicePicker(label: String, selected: String, choices: List<Choice>, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(choices.firstOrNull { it.id == selected }?.title ?: selected.ifBlank { "Automatic" }, Modifier.weight(1f)); Text("▾")
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(label) }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(choices, key = { it.id }) { choice ->
                ListItem(headlineContent = { Text(choice.title) }, supportingContent = { if (choice.detail.isNotBlank()) Text(choice.detail) },
                    leadingContent = { RadioButton(selected = selected == choice.id, onClick = null) },
                    modifier = Modifier.clickable { onSelect(choice.id); expanded = false })
            }
        }
    }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("Close") } })
}

@Composable fun VoiceSettingsPanel(
    settings: ListeningSettings,
    catalog: SpeechCatalog?,
    busy: Boolean,
    canControl: Boolean,
    onRefresh: (String) -> Unit,
    onApply: (ListeningSettings) -> Unit,
    onPreview: (ListeningSettings, String) -> Unit,
    onAndroidSettings: () -> Unit,
    onMoreVoices: () -> Unit = {}
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var previewText by remember { mutableStateOf("The road unfolds before us. A new chapter begins, with dragons beyond the horizon.") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your narrator", style = MaterialTheme.typography.titleLarge)
        Text("Choose from offline voices installed on this phone. Voice quality and accents depend on your speech engine.", style = MaterialTheme.typography.bodyMedium)
        val engines = listOf(Choice("", "Android default")) + catalog?.engines.orEmpty().map { Choice(it.packageName, it.label) }
        ChoicePicker("Speech engine", draft.enginePackage, engines, !busy) { id -> draft = draft.copy(enginePackage = id, voiceName = ""); onRefresh(id) }
        val catalogMatches = catalog != null && (draft.enginePackage.isBlank() || catalog.enginePackage == draft.enginePackage)
        val voices = if (catalogMatches) catalog!!.voices else emptyList()
        val locales = (voices.map { it.localeTag } + draft.localeTag).distinct().sortedBy { Locale.forLanguageTag(it).displayName }
        ChoicePicker("Language / accent", draft.localeTag, locales.map { Choice(it, Locale.forLanguageTag(it).displayName) }, !busy) { draft = draft.copy(localeTag = it, voiceName = "") }
        val voiceChoices = listOf(Choice("", "Automatic offline voice", "Prefers a matching accent and quick preparation")) + voices.filter { it.localeTag == draft.localeTag }.map {
            val quality = if (it.quality >= 400) "Higher quality" else if (it.quality <= 200) "Basic quality" else "Standard quality"
            val friendly = voiceLabel(it.name, catalog?.enginePackage.orEmpty())
            Choice(it.name, friendly, "$quality • ${Locale.forLanguageTag(it.localeTag).displayName}")
        }
        ChoicePicker("Voice", draft.voiceName, voiceChoices, !busy && catalogMatches) { draft = draft.copy(voiceName = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onRefresh(draft.enginePackage) }, enabled = !busy && canControl) { Text(if (busy) "Loading voices…" else "Refresh voices") }
            TextButton(onClick = onAndroidSettings) { Text("Install / manage voices") }
        }
        TextButton(onClick = onMoreVoices) { Text("Where to get more voices") }
        if (catalogMatches && voices.isEmpty()) Text("No installed offline voices found. Install voice data in Android settings, then refresh.")
        Text("Speed ${String.format(Locale.ROOT, "%.2f", draft.speed)}×")
        Slider(value = draft.speed, onValueChange = { draft = draft.copy(speed = it) }, valueRange = 0.65f..2f, steps = 26)
        Text("Pitch ${String.format(Locale.ROOT, "%.2f", draft.pitch)} • lower ↔ higher")
        Slider(value = draft.pitch, onValueChange = { draft = draft.copy(pitch = it) }, valueRange = 0.7f..1.3f, steps = 11)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestionChip(onClick = { draft = draft.copy(pitch = 1f, speed = 1f) }, label = { Text("Natural") })
            SuggestionChip(onClick = { draft = draft.copy(pitch = 1f, speed = 1.25f) }, label = { Text("Brisk") })
            SuggestionChip(onClick = { draft = draft.copy(pitch = 0.85f, speed = 1f) }, label = { Text("Lower tone") })
        }
        ChoicePicker("Device usage", draft.resourceMode.name, ResourceMode.entries.map { Choice(it.name, it.label,
            if (it == ResourceMode.ECONOMY) "Less prepared audio, fewer progress writes, smaller audio cache" else "Prepares more audio ahead to cover slower voice engines") }) { draft = draft.copy(resourceMode = ResourceMode.valueOf(it)) }
        OutlinedTextField(previewText, { previewText = it.take(240) }, label = { Text("Preview text") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPreview(draft.normalized(), previewText) }, enabled = canControl && !busy, modifier = Modifier.weight(1f)) { Text("Preview") }
            Button(onClick = { onApply(draft.normalized()) }, enabled = canControl && !busy, modifier = Modifier.weight(1f)) { Text("Save & apply") }
        }
        Text("Preview pauses your book. Resume it from Library. For web reading, choose Read chapter again after changing the voice or pronunciations.", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun voiceLabel(name: String, engine: String): String = if (engine == "com.brahmadeo.supertonic.tts") when (name) {
    "en-supertonic-M2" -> "James • Deep, calm"
    "en-supertonic-M5" -> "Daniel • Warm, soothing"
    "en-supertonic-M1" -> "Alex • Lively, upbeat"
    "en-supertonic-M3" -> "Robert • Polished, authoritative"
    "en-supertonic-M4" -> "Sam • Soft, friendly"
    "en-supertonic-F1" -> "Sarah • Calm, composed"
    "en-supertonic-F2" -> "Lily • Bright, cheerful"
    "en-supertonic-F3" -> "Jessica • Professional, clear"
    "en-supertonic-F4" -> "Olivia • Crisp, confident"
    "en-supertonic-F5" -> "Emily • Kind, gentle"
    else -> Regex("en-supertonic-([MF])(\\d+)").matchEntire(name)?.let { "${if (it.groupValues[1] == "F") "Female" else "Male"} voice ${it.groupValues[2]}" } ?: name
} else name
