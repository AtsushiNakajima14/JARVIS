package dev.jarvis.ui.permissions

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.jarvis.core.permissions.PermissionStatus
import dev.jarvis.ui.AssistantViewModel
import dev.jarvis.ui.components.SectionLabel

@Composable fun PermissionsScreen(vm: AssistantViewModel) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var requested by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        revision++
        if (granted && requested == Manifest.permission.READ_CONTACTS) vm.contacts()
        if (granted && requested == Manifest.permission.READ_CALENDAR) vm.calendar()
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; vm.addFile(uri) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    fun ask(value: String) { requested = value; permission.launch(value) }
    fun open(action: String, packageUri: Boolean = false) {
        runCatching { context.startActivity(Intent(action).apply { if (packageUri) data = "package:${context.packageName}".toUri() }) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("You’re in control.", style = MaterialTheme.typography.headlineMedium)
        Text("Only the microphone is needed for voice. Enable optional access when you want a skill to use it.", modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Revision is intentionally read to refresh settings after returning from Android permission pages.
        key(revision) {
            SectionLabel("Voice & alerts")
            PermissionRow("Microphone", "Wake word and spoken commands", PermissionStatus.microphone(context)) { ask(Manifest.permission.RECORD_AUDIO) }
            if (Build.VERSION.SDK_INT >= 33) PermissionRow("Notifications", "Listening status, background actions and timers", PermissionStatus.granted(context, Manifest.permission.POST_NOTIFICATIONS)) { ask(Manifest.permission.POST_NOTIFICATIONS) }
            PermissionRow("Camera", "Flashlight control", PermissionStatus.granted(context, Manifest.permission.CAMERA)) { ask(Manifest.permission.CAMERA) }
            SectionLabel("Optional device knowledge")
            PermissionRow("Contacts", "Encrypted names and phone numbers", PermissionStatus.granted(context, Manifest.permission.READ_CONTACTS)) {
                if (PermissionStatus.granted(context, Manifest.permission.READ_CONTACTS)) vm.contacts() else ask(Manifest.permission.READ_CONTACTS)
            }
            PermissionRow("Calendar", "Encrypted events for the next seven days", PermissionStatus.granted(context, Manifest.permission.READ_CALENDAR)) {
                if (PermissionStatus.granted(context, Manifest.permission.READ_CALENDAR)) vm.calendar() else ask(Manifest.permission.READ_CALENDAR)
            }
            PermissionRow("Usage access", "App usage and per-app storage statistics", PermissionStatus.usage(context)) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
            val notificationAccess = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            PermissionRow("Notification access", "Required for music controls; metadata storage is a separate setting", notificationAccess) { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            if (Build.VERSION.SDK_INT >= 31) PermissionRow("Bluetooth status", "Read whether Bluetooth is enabled", PermissionStatus.granted(context, Manifest.permission.BLUETOOTH_CONNECT)) { ask(Manifest.permission.BLUETOOTH_CONNECT) }
            OutlinedButton(onClick = { file.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) { Text("Select a file to index") }
            Text("Only its name, size and access URI are saved. File contents are never read.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionLabel("Background reliability")
            val unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            PermissionRow("Battery optimization", "Optional: allow longer background listening", unrestricted) { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
            if (Build.VERSION.SDK_INT >= 31) PermissionRow("Exact alarms", "Precise timer delivery while the device sleeps", context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) { open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) }
        }
        Text("Start listening while JARVIS is open. After a reboot, open the app to resume. Manufacturers may still stop background microphone services.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("Open Android app settings") }
    }
}

@Composable fun PermissionRow(title: String, description: String, granted: Boolean, click: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(description) },
        trailingContent = { TextButton(onClick = click, modifier = Modifier.testTag("permission_$title")) { Text(if (granted) "Manage" else "Enable") } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background))
}

