package dev.jarvis.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
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

@Composable fun DeviceScreen(vm: AssistantViewModel) {
    val info by vm.information.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Your device", style = MaterialTheme.typography.headlineMedium) }
        item { OutlinedButton(onClick = vm::refreshInfo) { Text("Refresh information") } }
        items(info, key = { it.key }) { item -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { SectionLabel(item.key); Text(item.value.text) } } }
    }
}
@Composable fun AppsScreen(vm: AssistantViewModel) {
    val apps by vm.applications.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("${apps.size} installed apps", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Launcher-visible applications. Categories are local heuristics; icons load from Android.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(search, { search = it }, label = { Text("Search name or category") }, modifier = Modifier.fillMaxWidth()) }
        item { TextButton(onClick = vm::refresh) { Text("Refresh application index") } }
        items(apps.filter { it.label.contains(search, true) || it.category.contains(search, true) }, key = { it.packageName }) { app ->
            val icon = remember(app.packageName) { runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(48, 48).asImageBitmap() }.getOrNull() }
            ListItem(headlineContent = { Text(app.label) }, supportingContent = { Text("${app.category} · ${app.version}\n${app.packageName}\nInstalled ${java.text.DateFormat.getDateInstance().format(java.util.Date(app.installedAt))}") },
                leadingContent = { icon?.let { Image(it, null, Modifier.size(40.dp)) } })
        }
    }
}
@Composable fun MemoryScreen(vm: AssistantViewModel) {
    val memories by vm.memories.collectAsStateWithLifecycle()
    val visible = memories.filter { it.kind != "timer" }
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("On this device", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Explicit preferences, favorite app notes, calendar events, notification metadata and selected file metadata. Delete any item at any time.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (visible.isEmpty()) item { Text("Nothing saved yet. Try “Remember that I use Discord for school.”") }
        items(visible, key = { it.id }) { memory ->
            Card { ListItem(headlineContent = { Text(memory.content.text) }, overlineContent = { Text(memory.kind.uppercase()) }, trailingContent = {
                IconButton(onClick = { vm.forget(memory) }) { Icon(Icons.Outlined.DeleteOutline, "Delete saved item") }
            }) }
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
