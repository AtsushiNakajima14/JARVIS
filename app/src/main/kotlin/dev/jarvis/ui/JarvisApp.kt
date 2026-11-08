package dev.jarvis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.*
import dev.jarvis.ui.home.HomeScreen
import dev.jarvis.ui.history.HistoryScreen
import dev.jarvis.ui.permissions.PermissionsScreen
import dev.jarvis.ui.settings.*
import dev.jarvis.ui.theme.JarvisTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun JarvisApp(onListen: () -> Unit, onWake: () -> Unit, onCommand: (String) -> Unit, onStop: () -> Unit, vm: AssistantViewModel = hiltViewModel()) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshNotificationAccess() }
    JarvisTheme {
        val nav = rememberNavController()
        val back by nav.currentBackStackEntryAsState()
        val route = back?.destination?.route ?: "home"
        val destinations = listOf(Triple("home", "Assistant", Icons.Outlined.Mic), Triple("history", "History", Icons.Outlined.History), Triple("settings", "Settings", Icons.Outlined.Tune))
        val notice by vm.notice.collectAsStateWithLifecycle()
        val busy by vm.busy.collectAsStateWithLifecycle()
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(title = { Text(if (route == "home") "JARVIS" else route.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = { if (route !in destinations.map { it.first }) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                    actions = { IconButton(onClick = { nav.navigate("permissions") }) { Icon(Icons.Outlined.Shield, "Permissions and privacy") } })
            }, bottomBar = {
                NavigationBar { destinations.forEach { (destination, label, icon) ->
                    NavigationBarItem(selected = route == destination, onClick = {
                        nav.navigate(destination) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true }
                    }, icon = { Icon(icon, label) }, label = { Text(label) })
                } }
            }) { padding ->
            Column(Modifier.padding(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                NavHost(nav, "home", modifier = Modifier.weight(1f)) {
                    composable("home") { HomeScreen(vm, onListen, onWake, onCommand, onStop) { nav.navigate("history") } }
                    composable("history") { HistoryScreen(vm) }
                    composable("settings") { SettingsScreen(vm, onStop) { nav.navigate(it) } }
                    composable("permissions") { PermissionsScreen(vm) }
                    composable("device") { DeviceScreen(vm) }
                    composable("apps") { AppsScreen(vm) }
                    composable("memory") { MemoryScreen(vm) }
                    composable("logs") { LogsScreen(vm) }
                }
            }
        }
    }
}

