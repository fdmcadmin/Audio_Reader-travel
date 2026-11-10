package app.roadleaf

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.roadleaf.playback.StartPoint

/**
 * Chapter text to choose a start word from. [base] is where [text] begins inside the chapter, so a
 * window of a very long chapter keeps absolute offsets. Either [url] or [bookId]/[chapterId] is set.
 */
data class StartChoice(val title: String, val text: String, val base: Int, val current: Int,
    val url: String? = null, val bookId: String? = null, val chapterId: String? = null)

@Composable fun StartFromWordDialog(choice: StartChoice, onDismiss: () -> Unit, onStart: (Int) -> Unit) {
    val paragraphs = remember(choice) { StartPoint.paragraphs(choice.text) }
    var selected by remember(choice) { mutableStateOf<IntRange?>(null) }
    val list = rememberLazyListState(StartPoint.paragraphAt(paragraphs, choice.current - choice.base))
    val highlight = MaterialTheme.colorScheme.primary
    val onHighlight = MaterialTheme.colorScheme.onPrimary
    AlertDialog(onDismissRequest = onDismiss,
        title = { Column {
            Text("Start from a word")
            Text(choice.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tap the word to start from, then OK. Reading continues from there to the end of the chapter.", style = MaterialTheme.typography.bodySmall)
            if (paragraphs.isEmpty()) Text("This chapter has no text to choose from.")
            LazyColumn(state = list, modifier = Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(paragraphs, key = { _, item -> item.first }) { _, (start, line) ->
                    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    val range = selected
                    val shown = buildAnnotatedString {
                        append(line)
                        if (range != null && range.first >= start && range.last < start + line.length)
                            addStyle(SpanStyle(background = highlight, color = onHighlight, fontWeight = FontWeight.Bold), range.first - start, range.last + 1 - start)
                    }
                    Text(shown, Modifier.fillMaxWidth().padding(vertical = 6.dp).pointerInput(start) {
                        detectTapGestures { position -> layout?.let { selected = StartPoint.word(choice.text, start + it.getOffsetForPosition(position)) } }
                    }, style = MaterialTheme.typography.bodyMedium, onTextLayout = { layout = it })
                }
            }
            selected?.let { Text("Starts at: “${StartPoint.preview(choice.text, it.first)}…”", style = MaterialTheme.typography.labelLarge) }
        } },
        confirmButton = { TextButton(onClick = { selected?.let { onStart(choice.base + it.first) } }, enabled = selected != null) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
