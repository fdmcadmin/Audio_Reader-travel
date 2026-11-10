package app.roadleaf

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import app.roadleaf.playback.OutputState
import app.roadleaf.playback.HeadphoneMusicState
import app.roadleaf.playback.HeadphoneMusicPolicy

@Composable fun AudioOutputPanel(state: OutputState, enabled: Boolean, onSelect: (String) -> Unit,
    onRefresh: () -> Unit, onTest: () -> Unit, onShare: () -> Unit, music: HeadphoneMusicState,
    onMusic: (Boolean) -> Unit, lastEvent: String) {
    Text("Where narration plays", style = MaterialTheme.typography.titleLarge)
    val choices = listOf(Choice("", "System default / Android Auto")) + state.devices.map { Choice(it.key, it.name, it.kind) } +
        if (state.selected.key.isNotBlank() && state.devices.none { it.key == state.selected.key })
            listOf(Choice(state.selected.key, "${state.selected.name} · disconnected")) else emptyList()
    ChoicePicker("Narration output", state.selected.key, choices, enabled, onSelect)
    Text(state.message, style = MaterialTheme.typography.bodyMedium)
    if (state.reportedDevice.isNotBlank()) Text("Last reported by Android: ${state.reportedDevice}", style = MaterialTheme.typography.bodySmall)
    if (state.lastResult.isNotBlank()) Text(state.lastResult, style = MaterialTheme.typography.bodySmall)
    Text("Connect your headphones, then choose them by name. Only Roadleaf requests this output; other apps keep their own routing. Android Auto may override it. Test while parked before relying on the split.", style = MaterialTheme.typography.bodySmall)
    Row { TextButton(onClick = onRefresh, enabled = enabled) { Text("Refresh outputs") }; TextButton(onClick = onTest, enabled = enabled) { Text("Test output") } }
    TextButton(onClick = onShare) { Text("Share output test result") }
    Text("Choosing an output pauses narration. A missing or unconfirmed selected output pauses playback; reconnecting does not restart it. Android may briefly reroute before a change is reported.", style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Switch(checked = music.enabled, onCheckedChange = onMusic, enabled = enabled && (music.enabled ||
            HeadphoneMusicPolicy.headphones(state.selected, state.devices)))
        Text("Headphones with car music (experimental)")
    }
    Text(music.message, style = MaterialTheme.typography.bodySmall)
    Text("Select your headphones first. This mode lets YouTube Music keep playing and turns off Roadleaf's Android Auto/Google media controls. Use Roadleaf or your headset for the book. Phone permission detects calls without reading contacts or call history. Calls pause the book; press Play after the call. Test both outputs while parked.", style = MaterialTheme.typography.bodySmall)
    if (lastEvent.isNotBlank()) Text("Last control: $lastEvent", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
}
