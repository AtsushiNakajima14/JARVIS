package dev.jarvis.ui.history
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.ui.AssistantViewModel
import dev.jarvis.ui.components.ConversationCard
@Composable fun HistoryScreen(vm: AssistantViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    var clear by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Your conversations", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { clear = true }, enabled = history.isNotEmpty()) { Text("Clear") }
        } }
        item { Text("Up to 200 conversations, encrypted on this device. Private notification readouts are not saved here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (history.isEmpty()) item { Text("Your saved conversations will appear here.", modifier = Modifier.padding(top = 30.dp)) }
        items(history, key = { it.id }) { ConversationCard(it) }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear conversation history?") }, text = { Text("This deletes the saved conversations from this device.") },
        confirmButton = { TextButton(onClick = { vm.clearHistory(); clear = false }) { Text("Clear history") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
}

