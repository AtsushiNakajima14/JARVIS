package dev.jarvis.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jarvis.core.common.*
import dev.jarvis.core.database.CommandHistoryEntity

@Composable fun SectionLabel(text: String) { Text(text.uppercase(), style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp), fontWeight = FontWeight.SemiBold) }

@Composable fun AssistantStatus(state: AssistantState, modifier: Modifier = Modifier) {
    val title = when (state.phase) {
        AssistantPhase.IDLE -> "Ready when you are"
        AssistantPhase.STARTING -> "Getting ready…"
        AssistantPhase.WAKE_WORD -> "Say Jarvis"
        AssistantPhase.LISTENING -> "Listening…"
        AssistantPhase.PROCESSING -> "Processing…"
        AssistantPhase.SPEAKING -> "Speaking…"
        AssistantPhase.PAUSED -> "Listening is paused"
        AssistantPhase.ERROR -> "Let’s try again"
    }
    Column(modifier.testTag("assistant_status"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        Text(state.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun ConversationCard(item: CommandHistoryEntity) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(item.command.text, fontWeight = FontWeight.Medium)
            Text(item.response.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(item.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
@Composable fun SettingSwitch(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(description) }, trailingContent = { Switch(checked, change) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background))
}
