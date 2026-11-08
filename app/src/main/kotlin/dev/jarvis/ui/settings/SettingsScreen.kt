package dev.jarvis.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
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
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import dev.jarvis.ui.AssistantViewModel
import dev.jarvis.assistant.response.AssistantPersona
import dev.jarvis.ui.components.*

@Composable fun SettingsScreen(vm: AssistantViewModel, stop: () -> Unit, navigate: (String) -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    val voices by vm.voices.collectAsStateWithLifecycle()
    val speechProvider by vm.speechProvider.collectAsStateWithLifecycle()
    val notificationAccess by vm.notificationAccess.collectAsStateWithLifecycle()
    var name by remember(config.name) { mutableStateOf(config.name) }
    var destructive by remember { mutableStateOf("") }
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadVoices() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshSpeechProvider() }
    fun openVoiceSettings() {
        val opened = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_INPUT_METHOD_SETTINGS, Settings.ACTION_SETTINGS)
            .any { action -> runCatching { context.startActivity(Intent(action)) }.isSuccess }
        if (!opened) vm.notice.value = "Open Android Settings and search for voice input."
    }
    fun installGoogleSpeech() {
        val packageId = "com.google.android.tts"
        val urls = listOf("market://details?id=$packageId", "https://play.google.com/store/apps/details?id=$packageId")
        if (!urls.any { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }.isSuccess })
            vm.notice.value = "Open Google Play and search for Google speech services."
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        SectionLabel("Voice")
        SettingSwitch("Wake word", "Say “Jarvis” while listening is active", config.wakeWord) { vm.boolean("wake", it); if (!it) stop() }
        OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, label = { Text("Assistant display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { vm.text("name", name.ifBlank { "Jarvis" }) }) { Text("Save name") }
        Text("The bundled wake model always listens for “Jarvis”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("Speech language")
        ChoiceDropdown(config.language, listOf("system" to "Device language", "en-US" to "English", "fil-PH" to "Filipino / Taglish", "ceb-PH" to "Cebuano (provider dependent)")) { vm.text("language", it) }
        Text("Google speech supports the languages available on your phone. Offline fallback recognizes English. Taglish and Cebuano accuracy depend on the installed speech service.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        SectionLabel("Speech recognition")
        SettingSwitch("Prefer Google speech", "Use your installed Google speech service for voice recognition. Audio may be processed online. No API key or Cloud billing setup.", config.networkSpeech) { vm.boolean("network", it) }
        Text(when {
            speechProvider == null -> "Checking Google speech availability…"
            speechProvider?.googleAvailable == true -> "Available: ${speechProvider?.googleName}. ${if (config.networkSpeech) "Google speech is preferred." else "Offline recognition is selected."}"
            else -> "Google speech is unavailable or disabled. JARVIS can still use offline English commands."
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = ::openVoiceSettings) { Text("Open Android voice settings") }
        if (speechProvider?.googleAvailable == false) OutlinedButton(onClick = ::installGoogleSpeech) { Text("Get Google speech services") }
        SectionLabel("Speaking voice")
        ChoiceDropdown(config.voice, listOf("" to "Automatic voice") + voices) { vm.text("voice", it) }
        TextButton(onClick = { runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) } }) { Text("Install or manage voices in Android") }
        Text("Speech speed · ${"%.1f".format(config.speed)}×")
        Slider(config.speed, { vm.number("speed", it) }, valueRange = 0.5f..2f, steps = 14)
        Text("Speech pitch · ${"%.1f".format(config.pitch)}×")
        Slider(config.pitch, { vm.number("pitch", it) }, valueRange = 0.5f..2f, steps = 14)
        SectionLabel("Assistant")
        SettingSwitch("On-phone AI", "Answer simple questions and chat using the included small English model", config.localAi) { vm.boolean("local_ai", it); if (!it) stop() }
        Text("A small AI model (about 92 MB) is included and runs on this phone. AI replies need no cloud AI account; Google speech may still process audio online when enabled. Reply speed depends on your phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingSwitch("Follow-up conversation", "Listen briefly after a reply so you can continue speaking. Say cancel to finish.", config.conversation) { vm.boolean("conversation", it) }
        SettingSwitch("Background listening", "Keep the microphone service active with the screen off", config.background) { vm.boolean("background", it); if (!it) stop() }
        SettingSwitch("Local memory", "Remember and recall preferences you explicitly save", config.memory) { vm.boolean("memory", it) }
        SettingSwitch("Command history", "Save up to 200 encrypted conversations", config.history) { vm.boolean("history", it) }
        SectionLabel("Notification awareness")
        Text(if (notificationAccess) "Android notification access is enabled." else "Allow Android Notification access to use these features.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("With Android Notification access, JARVIS can read notification previews and recall people who sent message notifications. It cannot read an app's private chat database or conversations it never received.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingSwitch("Read notification previews", "Save permitted titles, senders and message previews encrypted on this phone for up to seven days. Readouts require an unlocked phone. Turning this off deletes captured previews.", config.notificationReading) { vm.notificationReading(it) }
        SettingSwitch("Announce new notifications", "While JARVIS is listening, say the app name when a notification arrives. Message text stays private; alerts wait until a command finishes.", config.notificationAnnouncements) { vm.boolean("notification_announcements", it) }
        TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.onFailure { vm.notice.value = "Open Android Settings and search for Notification access." } }) { Text("Manage Android notification access") }
        TextButton(onClick = { destructive = "Delete captured notifications" }) { Text("Delete captured notifications") }
        SettingSwitch("Notification metadata", "With access enabled, save app names and timestamps only", config.notifications) { vm.boolean("notifications", it) }
        SettingsLink("Saved memory & selected data", "View and delete individual items") { navigate("memory") }
        SettingsLink("Permissions", "Manage microphone and optional access") { navigate("permissions") }
        SectionLabel("Developer")
        SettingsLink("Device information", "Hardware, network, storage and battery") { navigate("device") }
        SettingsLink("Installed applications", "Search the local launcher index") { navigate("apps") }
        SettingsLink("System logs", "Operational events; no commands or audio") { navigate("logs") }
        Text("Created by ${AssistantPersona.CREATOR_NAME} (${AssistantPersona.CREATOR_ALIAS}).", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        Text("Say “unlock my phone” to open Android authentication. JARVIS never stores or enters a screen-lock PIN.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("Privacy")
        Text("Recordings are never saved. Sensitive database fields use AES-GCM with an Android Keystore key. Android backup and device transfer are disabled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { destructive = "Delete saved memory" }) { Text("Delete saved memory") }
        TextButton(onClick = { destructive = "Reset application index" }) { Text("Rebuild application index") }
        TextButton(onClick = { destructive = "Clear all local data" }) { Text("Clear all local data", color = MaterialTheme.colorScheme.error) }
        Text("JARVIS ${dev.jarvis.BuildConfig.VERSION_NAME} · Android 8.0+", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 24.dp))
    }
    if (destructive.isNotEmpty()) AlertDialog(onDismissRequest = { destructive = "" },
        title = { Text(if (destructive == "Reset application index") "Rebuild application index?" else "$destructive?") },
        text = { Text(if (destructive == "Reset application index") "Scan installed apps again and replace the local index. New apps are indexed automatically."
            else "This deletes the selected saved data and clears the current AI conversation. Listening will pause until you resume it.") },
        confirmButton = { TextButton(onClick = {
            when (destructive) { "Delete saved memory" -> vm.clearMemory(); "Delete captured notifications" -> vm.clearNotifications(); "Reset application index" -> vm.resetIndex(); else -> { stop(); vm.clearData() } }; destructive = ""
        }) { Text(if (destructive == "Reset application index") "Rebuild" else "Delete") } },
        dismissButton = { TextButton(onClick = { destructive = "" }) { Text("Cancel") } })
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
