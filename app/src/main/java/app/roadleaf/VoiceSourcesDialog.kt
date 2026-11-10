package app.roadleaf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Speech apps with offline voices that Roadleaf can use. Roadleaf links to them; it bundles no voices. */
data class VoiceSource(val name: String, val note: String, val url: String)

val voiceSources = listOf(
    VoiceSource("Speech Recognition & Synthesis (Google)", "Google's speech engine. After installing, download voices in its settings.",
        "https://play.google.com/store/apps/details?id=com.google.android.tts"),
    VoiceSource("Supertonic TTS", "Natural English voices labelled female and male, so Roadleaf can match them to characters automatically.",
        "https://play.google.com/store/apps/details?id=com.brahmadeo.supertonic.tts"),
    VoiceSource("Sherpa-ONNX speech engine", "Open-source neural voices, such as Piper voices. Each download holds one voice model, so files are larger.",
        "https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html"),
    VoiceSource("RHVoice", "Small, light voices.", "https://play.google.com/store/apps/details?id=com.github.olga_yakovleva.rhvoice.android"),
)

@Composable fun VoiceSourcesDialog(engineLabel: String, onEngineVoices: () -> Unit, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Get more voices") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Roadleaf reads with the offline voices of the speech apps on your phone. More installed voices let more characters have a voice of their own.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onEngineVoices, modifier = Modifier.fillMaxWidth()) { Text("Add voices to $engineLabel") }
            Text("Or install another speech app, then choose it under Voice → Narrator and tap Refresh voices:", style = MaterialTheme.typography.bodySmall)
            voiceSources.forEach { source ->
                Column {
                    TextButton(onClick = { onOpen(source.url) }) { Text(source.name) }
                    Text(source.note, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Each page shows the app's price, size and permissions. One speech app supplies a book's voices at a time.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
