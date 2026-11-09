package dev.jarvis.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Check
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jarvis.ui.AssistantViewModel
import dev.jarvis.ui.components.SectionLabel
import dev.jarvis.assistant.memory.MemoryRepository

@Composable fun DeviceScreen(vm: AssistantViewModel) {
    val info by vm.information.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Your device", style = MaterialTheme.typography.headlineMedium) }
        item { Text("A snapshot of what Android makes available. Refresh for current readings; app usage requires your permission.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedButton(onClick = vm::refreshInfo) { Text("Refresh information") } }
        items(info, key = { it.key }) { item -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { SectionLabel(item.key); Text(item.value.text) } } }
    }
}
@Composable fun AppsScreen(vm: AssistantViewModel) {
    val apps by vm.applications.collectAsStateWithLifecycle()
    val phone by vm.deviceKnowledge.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("${apps.size} installed apps", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Apps Android makes visible to JARVIS. Their names and categories help your companion find the right app for you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(search, { search = it }, label = { Text("Search name or category") }, modifier = Modifier.fillMaxWidth()) }
        item { TextButton(onClick = vm::refresh) { Text("Refresh application index") } }
        items(apps.filter { it.label.contains(search, true) || it.category.contains(search, true) }, key = { it.packageName }) { app ->
            val icon = remember(app.packageName) { runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(48, 48).asImageBitmap() }.getOrNull() }
            val use = phone?.frequentApps?.firstOrNull { it.packageName == app.packageName }
            ListItem(headlineContent = { Text(app.label) }, supportingContent = { Text("${app.category} · ${app.version}\n${app.packageName}${use?.let { "\n${it.foregroundMillis / 60_000} minutes used in the last seven days" }.orEmpty()}") },
                leadingContent = { icon?.let { Image(it, null, Modifier.size(40.dp)) } })
        }
    }
}
@Composable fun MemoryScreen(vm: AssistantViewModel) {
    val memories by vm.memories.collectAsStateWithLifecycle()
    val files by vm.knownFiles.collectAsStateWithLifecycle()
    val visible = memories.filter { it.kind in setOf("memory", "calendar", "notification", "notification_message") }
    var selected by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::addFile) }
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Your local memory", style = MaterialTheme.typography.headlineMedium) }
        item { Text("You decide what JARVIS remembers. Saved facts and selected data stay on this phone and can be deleted individually.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            TabRow(selectedTabIndex = selected) {
                listOf("Memories", "Files", "Other data").forEachIndexed { index, title -> Tab(selected = selected == index, onClick = { selected = index }, text = { Text(title) }) }
            }
        }
        if (selected == 1) {
            item { OutlinedButton(onClick = { vm.beginFileSelection(); picker.launch(arrayOf("*/*")) }) { Text("Remember an important file") } }
            item { Text("Only names and access details for files you select are saved. File contents are not read.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { OutlinedTextField(search, { search = it.take(120) }, label = { Text("Find a selected file") }, modifier = Modifier.fillMaxWidth()) }
            if (files.isEmpty()) item { Text("Choose documents you want help finding again.") }
            items(files.filter { it.name.contains(search, true) }, key = { it.id }) { file ->
                Card { ListItem(headlineContent = { Text(file.name) }, supportingContent = { Text("${if (file.important) "Important file" else "Recent file"} · ${java.text.DateFormat.getDateInstance().format(java.util.Date(file.modifiedAt))}") }, trailingContent = {
                    IconButton(onClick = { vm.forgetFile(file.id) }) { Icon(Icons.Outlined.DeleteOutline, "Forget ${file.name}") }
                }) }
            }
        } else {
            val chosen = visible.filter { if (selected == 0) it.kind == "memory" else it.kind != "memory" }
            if (chosen.isEmpty()) item { Text(if (selected == 0) "Nothing saved yet. Tell me something you would like me to remember." else "Optional calendar events and notification previews appear here when access is enabled.") }
            items(chosen, key = { it.id }) { memory ->
                val label = when (memory.kind) {
                    "memory" -> MemoryRepository.category(memory.content.text).label
                    "calendar" -> "Calendar event"
                    "notification_message" -> "Notification preview"
                    else -> "Notification metadata"
                }
                Card { ListItem(headlineContent = { Text(memory.content.text) }, overlineContent = { Text(label) }, trailingContent = {
                    IconButton(onClick = { vm.forget(memory) }) { Icon(Icons.Outlined.DeleteOutline, "Delete saved item") }
                }) }
            }
        }
    }
}

@Composable fun ReminderScreen(vm: AssistantViewModel) {
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Reminders & tasks", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Tell JARVIS what you need to do and when. Android delivers reminders in the background; battery restrictions can delay them.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (reminders.isEmpty()) item { Text("No reminders yet. You can say, “Remind me to check my project tomorrow at 9 AM.”") }
        items(reminders.sortedWith(compareBy({ it.completed }, { it.dueAt })), key = { it.id }) { reminder ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(reminder.title, style = MaterialTheme.typography.titleMedium)
                    Text(if (reminder.completed) "Completed" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(reminder.dueAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (!reminder.completed) TextButton(onClick = { vm.completeReminder(reminder.id) }) { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("Done") }
                        IconButton(onClick = { vm.forgetReminder(reminder.id) }) { Icon(Icons.Outlined.DeleteOutline, "Delete reminder") }
                    }
                }
            }
        }
    }
}
@Composable fun LogsScreen(vm: AssistantViewModel) {
    val logs by vm.session.logs.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Operational logs", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Kept in memory for this process only. No transcripts, contact details or audio.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (logs.isEmpty()) item { Text("No events recorded.") }
        items(logs) { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
