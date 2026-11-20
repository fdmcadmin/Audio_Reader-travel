package app.roadleaf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.roadleaf.playback.*
import java.util.Locale

internal data class Choice(val id: String, val title: String, val detail: String = "", val pack: HayaiVoices.LibriTtsPack? = null)

@Composable internal fun ChoicePicker(label: String, selected: String, choices: List<Choice>, enabled: Boolean = true,
    onPreview: ((String) -> Unit)? = null, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var pack by remember { mutableStateOf<HayaiVoices.LibriTtsPack?>(null) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    OutlinedButton(onClick = { query = ""; pack = null; expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(choices.firstOrNull { it.id == selected }?.title ?: selected.ifBlank { "Automatic" }, Modifier.weight(1f)); Text("▾")
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(label) }, text = {
        Column {
            val packs = remember(choices) { choices.mapNotNull { it.pack }.distinct() }
            if (packs.isNotEmpty()) {
                FilterChip(selected = pack == null, onClick = { pack = null }, label = { Text("All voices") })
                packs.forEach { model ->
                    FilterChip(selected = pack == model, onClick = { pack = model }, label = { Text(model.label) })
                }
            }
            if (choices.size > 20 || packs.isNotEmpty()) OutlinedTextField(query, { query = it }, label = { Text("Find a voice") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val shown = remember(choices, pack, query) {
                val search = query.trim()
                choices.filter { choice ->
                    (pack == null || choice.pack == pack) && (search.isEmpty() ||
                        choice.title.contains(search, true) || choice.id.contains(search, true) || choice.detail.contains(search, true))
                }
            }
            if (shown.isEmpty()) Text("No voices match this search.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp)) {
                items(shown, key = { it.id }) { choice ->
                ListItem(headlineContent = { Text(choice.title) }, supportingContent = { if (choice.detail.isNotBlank()) Text(choice.detail) },
                    leadingContent = { RadioButton(selected = selected == choice.id, onClick = null) },
                    trailingContent = { if (onPreview != null && choice.id.isNotBlank()) TextButton(onClick = { onPreview(choice.id) }) { Text("Hear") } },
                    modifier = Modifier.clickable { onSelect(choice.id); expanded = false })
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("Close") } })
}

internal fun voiceChoice(voice: OfflineVoiceOption, engine: String, detail: String = ""): Choice {
    val model = HayaiVoices.libriTts(voice.name, engine)
    return Choice(voice.name, voiceLabel(voice.name, engine),
        if (model == null) detail else listOf(detail, voice.name).filter(String::isNotBlank).joinToString("\n"),
        model?.pack)
}

@Composable internal fun LibriTtsAvailability(voices: List<OfflineVoiceOption>, engine: String, locale: String) {
    if (engine != HayaiVoices.ENGINE) return
    val packs = remember(voices) {
        voices.mapNotNull { voice -> HayaiVoices.libriTts(voice.name, HayaiVoices.ENGINE)?.let { it to voice.name } }
            .groupBy { it.first.pack }
    }
    if (packs.isEmpty()) {
        Text("For Piper LibriTTS, install High or LibriTTS-R Medium in HayaiTTS, then refresh voices here.", style = MaterialTheme.typography.bodySmall)
        return
    }
    if (!locale.equals("en-US", true)) {
        Text("Set Language / accent to English (United States) to show LibriTTS speakers.", style = MaterialTheme.typography.bodySmall)
    }
    packs.forEach { (pack, entries) ->
        val speakers = entries.mapNotNull { it.first.number }.distinct()
        Text(if (speakers.isEmpty()) "${pack.label} · HayaiTTS reports the pack only"
            else "${pack.label} · ${speakers.size} speakers reported by HayaiTTS", style = MaterialTheme.typography.bodySmall)
        val sampleId = entries.minBy { it.first.number ?: Int.MAX_VALUE }.second
        SelectionContainer { Text("Voice ID: $sampleId", style = MaterialTheme.typography.bodySmall) }
        if (speakers.isEmpty()) Text("Update HayaiTTS and refresh to check for separate speaker choices.", style = MaterialTheme.typography.bodySmall)
    }
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
    onMoreVoices: () -> Unit = {},
    onImportKokoro: () -> Unit = {},
    onDownloadKokoro: () -> Unit = {},
    kokoroInstalled: Boolean = false,
    kokoroImportBusy: Boolean = false
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var previewText by remember { mutableStateOf("The road unfolds before us. A new chapter begins, with dragons beyond the horizon.") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your narrator", style = MaterialTheme.typography.titleLarge)
        Text("Choose from offline voices installed on this phone. Voice quality and accents depend on your speech engine.", style = MaterialTheme.typography.bodyMedium)
        val engines = listOf(Choice("", "Android default")) + catalog?.engines.orEmpty().map { Choice(it.packageName, it.label) }
        ChoicePicker("Speech engine", draft.enginePackage, engines, !busy) { id -> draft = draft.copy(enginePackage = id, voiceName = ""); onRefresh(id) }
        if (draft.enginePackage == NativeKokoro.ENGINE_ID) {
            Text(if (kokoroInstalled) "Kokoro fp32 ready • 28 English voices and 103 blended voices • works offline."
                 else "Kokoro fp32 is included with Roadleaf. Prepare it once on this phone before listening.",
                 style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onImportKokoro, enabled = !kokoroImportBusy, modifier = Modifier.fillMaxWidth()) {
                Text(if (kokoroImportBusy) "Preparing voices…" else if (kokoroInstalled) "Prepare Kokoro again" else "Prepare included Kokoro voices")
            }
        }
        val catalogMatches = catalog != null && (draft.enginePackage.isBlank() || catalog.enginePackage == draft.enginePackage)
        val voices = if (catalogMatches) catalog!!.voices else emptyList()
        val locales = (voices.map { it.localeTag } + draft.localeTag).distinct().sortedBy { Locale.forLanguageTag(it).displayName }
        ChoicePicker("Language / accent", draft.localeTag, locales.map { Choice(it, Locale.forLanguageTag(it).displayName) }, !busy) { draft = draft.copy(localeTag = it, voiceName = "") }
        val voiceChoices = listOf(Choice("", "Automatic offline voice", "Prefers a matching accent and quick preparation")) + voices.filter { it.localeTag == draft.localeTag || (draft.enginePackage == NativeKokoro.ENGINE_ID && it.localeTag.startsWith("en-", ignoreCase = true)) }.map {
            val quality = if (it.quality >= 400) "Higher quality" else if (it.quality <= 200) "Basic quality" else "Standard quality"
            voiceChoice(it, catalog?.enginePackage.orEmpty(), "$quality • ${Locale.forLanguageTag(it.localeTag).displayName}")
        }
        if (catalogMatches) LibriTtsAvailability(voices, catalog!!.enginePackage, draft.localeTag)
        ChoicePicker("Voice", draft.voiceName, voiceChoices, !busy && catalogMatches) { draft = draft.copy(voiceName = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onRefresh(draft.enginePackage) }, enabled = !busy && canControl) { Text(if (busy) "Loading voices…" else "Refresh voices") }
            if (draft.enginePackage != NativeKokoro.ENGINE_ID) TextButton(onClick = onAndroidSettings) { Text("Install / manage voices") }
        }
        TextButton(onClick = onMoreVoices) { Text("Where to get more voices") }
        if (catalogMatches && voices.isEmpty()) Text(if (draft.enginePackage == NativeKokoro.ENGINE_ID) "Prepare the included Kokoro model above, then Refresh voices." else "No installed offline voices found. Install voice data in Android settings, then refresh.")
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
            Button(onClick = { onApply(draft.normalized()) }, enabled = canControl && !busy && (draft.enginePackage != NativeKokoro.ENGINE_ID || kokoroInstalled), modifier = Modifier.weight(1f)) { Text("Save & apply") }
        }
        Text("Preview pauses your book. Resume it from Library. For web reading, choose Read chapter again after changing the voice or pronunciations.", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun voiceLabel(name: String, engine: String): String {
    HayaiVoices.libriTts(name, engine)?.let { voice ->
        return voice.number?.let { "${voice.pack.label} · speaker $it" } ?: voice.pack.label
    }
    return standardVoiceLabel(name, engine)
}

private fun standardVoiceLabel(name: String, engine: String): String = if (NativeVctk.speakerId(name) != null) {
    "VCTK supporting voice ${NativeVctk.speakerId(name)}"
} else if (engine == NativeKokoro.ENGINE_ID) {
    NativeKokoro.speakerName(name)?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: name
} else if (engine == "com.brahmadeo.supertonic.tts") when (name) {
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
} else if (engine == HayaiVoices.ENGINE && HayaiVoices.isEnglishKokoro(name)) {
    val speaker = HayaiVoices.speaker(name)
    "Kokoro ${speaker.substringAfter('_').replace('_', ' ').replaceFirstChar { it.titlecase() }} • ${if (speaker.startsWith('b', true)) "British" else "American"} ${if (HayaiVoices.gender(name) == VoiceDelegation.Gender.FEMALE) "female" else "male"}"
} else name
