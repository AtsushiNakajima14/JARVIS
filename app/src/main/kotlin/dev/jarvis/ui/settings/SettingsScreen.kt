package dev.jarvis.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.ui.AssistantViewModel
import dev.jarvis.ui.components.*

@Composable fun SettingsScreen(vm: AssistantViewModel, stop: () -> Unit, navigate: (String) -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    val voices by vm.voices.collectAsStateWithLifecycle()
    var name by remember(config.name) { mutableStateOf(config.name) }
    var destructive by remember { mutableStateOf("") }
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadVoices() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        SectionLabel("Voice")
        SettingSwitch("Wake word", "Say “Jarvis” while listening is active", config.wakeWord) { vm.boolean("wake", it); if (!it) stop() }
        OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, label = { Text("Assistant display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { vm.text("name", name.ifBlank { "Jarvis" }) }) { Text("Save name") }
        Text("The bundled wake model always listens for “Jarvis”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("Speech language")
        ChoiceDropdown(config.language, listOf("system" to "Device language", "en-US" to "English", "fil-PH" to "Filipino / Taglish", "ceb-PH" to "Cebuano (provider dependent)")) { vm.text("language", it) }
        Text("Offline fallback recognizes English. Other languages need an installed Android speech pack or the optional network service. Taglish accuracy depends on the recognizer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        SectionLabel("Speaking voice")
        ChoiceDropdown(config.voice, listOf("" to "Automatic offline voice") + voices) { vm.text("voice", it) }
        TextButton(onClick = { runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } }) { Text("Install or manage voices in Android") }
        Text("Speech speed · ${"%.1f".format(config.speed)}×")
        Slider(config.speed, { vm.number("speed", it) }, valueRange = 0.5f..2f, steps = 14)
        Text("Speech pitch · ${"%.1f".format(config.pitch)}×")
        Slider(config.pitch, { vm.number("pitch", it) }, valueRange = 0.5f..2f, steps = 14)
        SettingSwitch("Allow network speech", "Your Android speech provider may process audio online. Off by default.", config.networkSpeech) { vm.boolean("network", it) }
        SectionLabel("Assistant")
        SettingSwitch("Background listening", "Keep the microphone service active with the screen off", config.background) { vm.boolean("background", it); if (!it) stop() }
        SettingSwitch("Local memory", "Remember and recall preferences you explicitly save", config.memory) { vm.boolean("memory", it) }
        SettingSwitch("Command history", "Save up to 200 encrypted conversations", config.history) { vm.boolean("history", it) }
        SettingSwitch("Notification metadata", "With access enabled, save app names and timestamps only", config.notifications) { vm.boolean("notifications", it) }
        SettingsLink("Saved memory & selected data", "View and delete individual items") { navigate("memory") }
        SettingsLink("Permissions", "Manage microphone and optional access") { navigate("permissions") }
        SectionLabel("Developer")
        SettingsLink("Device information", "Hardware, network, storage and battery") { navigate("device") }
        SettingsLink("Installed applications", "Search the local launcher index") { navigate("apps") }
        SettingsLink("System logs", "Operational events; no commands or audio") { navigate("logs") }
        SectionLabel("Privacy")
        Text("Recordings are never saved. Sensitive database fields use AES-GCM with an Android Keystore key. Android backup and device transfer are disabled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { destructive = "Delete saved memory" }) { Text("Delete saved memory") }
        TextButton(onClick = { destructive = "Reset application index" }) { Text("Reset application index") }
        TextButton(onClick = { destructive = "Clear all local data" }) { Text("Clear all local data", color = MaterialTheme.colorScheme.error) }
        Text("JARVIS 1.0.0 · Android 8.0+", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 24.dp))
    }
    if (destructive.isNotEmpty()) AlertDialog(onDismissRequest = { destructive = "" }, title = { Text("$destructive?") },
        text = { Text("This removes the selected data from this device. This cannot be undone.") },
        confirmButton = { TextButton(onClick = {
            when (destructive) { "Delete saved memory" -> vm.clearMemory(); "Reset application index" -> vm.resetIndex(); else -> { stop(); vm.clearData() } }; destructive = ""
        }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { destructive = "" }) { Text("Cancel") } })
}
@Composable fun SettingsLink(title: String, detail: String, click: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(detail) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
        modifier = Modifier.clickable(onClick = click), colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background))
}
@Composable fun ChoiceDropdown(selected: String, options: List<Pair<String, String>>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(options.firstOrNull { it.first == selected }?.second ?: "Automatic") }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { change(value); expanded = false }) } }
    }
}
