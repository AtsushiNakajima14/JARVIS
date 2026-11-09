package dev.jarvis.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.assistant.response.AssistantPersona
import dev.jarvis.ui.AssistantViewModel
import dev.jarvis.ui.components.*

@Composable fun HomeScreen(vm: AssistantViewModel, listen: () -> Unit, wake: () -> Unit,
    command: (String) -> Unit, stop: () -> Unit, history: () -> Unit, navigate: (String) -> Unit = {}) {
    val state by vm.status.collectAsStateWithLifecycle()
    val recent by vm.history.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val phone by vm.deviceKnowledge.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Hi, ${AssistantPersona.FRIENDLY_NAME}.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text("What's on your mind?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.Lock, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Your private phone companion", style = MaterialTheme.typography.labelMedium)
                        }
                        AssistantStatus(state)
                        if (state.recognitionSource.isNotBlank()) Text(state.recognitionSource,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.transcript.isNotBlank()) {
                            Text(state.transcript, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = { input = state.transcript }) { Text("Edit what I heard") }
                        }
                        if (state.lastResponse.isNotBlank()) Text(state.lastResponse, style = MaterialTheme.typography.bodyLarge)
                        state.confirmation?.let { prompt ->
                            HorizontalDivider()
                            Text(prompt)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { command("confirm") }) { Text("Confirm") }
                                OutlinedButton(onClick = { command("cancel") }) { Text("Cancel") }
                            }
                        }
                    }
                }
            }
            item {
                Card(onClick = { navigate("device") }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Your phone", style = MaterialTheme.typography.titleSmall)
                        Text(phone?.summary() ?: "Ask me how your phone is doing.", style = MaterialTheme.typography.bodyMedium)
                        phone?.let { Text("${it.applicationCount} apps known · Updated ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it.measuredAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { navigate("memory") }) { Text("Memory") }
                    TextButton(onClick = { navigate("tasks") }) { Text("Reminders") }
                    TextButton(onClick = { navigate("apps") }) { Text("Apps") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Our conversation", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = history) { Text("History") }
                }
            }
            if (conversation.isNotEmpty()) {
                items(conversation.takeLast(12)) { turn -> ConversationTurn(turn.user, turn.assistant, turn.inputSource) }
            } else if (recent.isNotEmpty()) {
                items(recent.take(3).reversed(), key = { it.id }) { ConversationCard(it) }
            } else item {
                Text("Tell me what you need in your own words. I can help you contact someone, make a plan, remember something, or check your phone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = input, onValueChange = { input = it.take(2000) }, label = { Text("Talk to JARVIS") }, modifier = Modifier.weight(1f), maxLines = 3,
                        trailingIcon = { IconButton(onClick = { if (input.isNotBlank()) { command(input); input = "" } }, enabled = input.isNotBlank()) { Icon(Icons.Outlined.ArrowUpward, "Send message") } })
                    FilledIconButton(onClick = listen, shape = CircleShape, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.Mic, "Talk to JARVIS") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    if (state.running) TextButton(onClick = stop) { Icon(Icons.Outlined.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pause listening") }
                    else TextButton(onClick = wake) { Text("Listen for “Jarvis”") }
                }
            }
        }
    }
}

@Composable private fun ConversationTurn(user: String, assistant: String, inputSource: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.align(Alignment.End).fillMaxWidth(0.92f), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.padding(14.dp)) {
                Text(when { inputSource == "Typed" -> "You · Typed"; inputSource.isNotBlank() -> "You · Voice"; else -> "You" }, style = MaterialTheme.typography.labelSmall)
                Text(user, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Surface(Modifier.fillMaxWidth(0.95f), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(14.dp)) {
                Text("JARVIS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(assistant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
