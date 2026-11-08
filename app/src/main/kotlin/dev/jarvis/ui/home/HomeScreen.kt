package dev.jarvis.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.ui.AssistantViewModel
import dev.jarvis.assistant.response.AssistantPersona
import dev.jarvis.ui.components.*

@Composable fun HomeScreen(vm: AssistantViewModel, listen: () -> Unit, wake: () -> Unit, command: (String) -> Unit, stop: () -> Unit, history: () -> Unit) {
    val state by vm.status.collectAsStateWithLifecycle()
    val recent by vm.history.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(6.dp))
        Text("Hi, ${AssistantPersona.FRIENDLY_NAME}.\nI'm here to help.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Your voice. Your assistant.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                AssistantStatus(state)
                if (!config.networkSpeech) TextButton(onClick = { vm.boolean("network", true) }) { Text("Use Google speech") }
                if (state.transcript.isNotBlank()) {
                    Text("I heard", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("“${state.transcript}”", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    if (state.recognitionSource.isNotBlank()) Text(state.recognitionSource, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { input = state.transcript }) { Text("Edit command") }
                }
                if (state.lastResponse.isNotBlank() && state.lastResponse != state.detail) Text(state.lastResponse, style = MaterialTheme.typography.bodyMedium)
                FilledIconButton(onClick = listen, shape = CircleShape, modifier = Modifier.size(88.dp).align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Outlined.Mic, "Listen for a command", Modifier.size(34.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    if (state.running) TextButton(onClick = stop) { Icon(Icons.Outlined.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pause listening") }
                    else TextButton(onClick = wake) { Text("Enable “Jarvis” wake word") }
                }
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
        OutlinedTextField(value = input, onValueChange = { input = it.take(2000) }, label = { Text("Or type a command") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
            trailingIcon = { IconButton(onClick = { if (input.isNotBlank()) { command(input); input = "" } }, enabled = input.isNotBlank()) { Icon(Icons.Outlined.ArrowUpward, "Run command") } })
        Text("TRY ASKING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestionChip(onClick = { command("battery percentage") }, label = { Text("Battery status") })
            SuggestionChip(onClick = { command("give me a briefing") }, label = { Text("Catch me up") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestionChip(onClick = { command("read my notifications") }, label = { Text("Notifications") })
            SuggestionChip(onClick = { command("who messaged me") }, label = { Text("Recent chats") })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Recent conversations", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = history) { Text("View all") }
        }
        if (recent.isEmpty()) Text("Say “Jarvis”, wait for the listening tone, then speak naturally. You can also tap the microphone or say “Jarvis, battery percentage” together.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        else recent.take(3).forEach { ConversationCard(it) }
        Spacer(Modifier.height(16.dp))
    }
}

