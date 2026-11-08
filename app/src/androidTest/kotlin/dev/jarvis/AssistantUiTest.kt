package dev.jarvis
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.jarvis.core.common.*
import dev.jarvis.ui.components.AssistantStatus
import dev.jarvis.ui.permissions.PermissionRow
import dev.jarvis.ui.theme.JarvisTheme
import org.junit.Rule
import org.junit.Test
class AssistantUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun listeningStateIsVisible() {
        compose.setContent { JarvisTheme { AssistantStatus(AssistantState(AssistantPhase.LISTENING, "Speak now")) } }
        compose.onNodeWithText("Listening…").assertIsDisplayed()
        compose.onNodeWithText("Speak now").assertIsDisplayed()
    }
    @Test fun microphonePermissionCanBeRequested() {
        var requested = false
        compose.setContent { JarvisTheme { PermissionRow("Microphone", "Voice commands", false) { requested = true } } }
        compose.onNodeWithTag("permission_Microphone").performClick()
        org.junit.Assert.assertTrue(requested)
    }
    @Test fun deniedPermissionDoesNotPretendToBeGranted() {
        compose.setContent { JarvisTheme { PermissionRow("Microphone", "Voice commands", false) {} } }
        compose.onNodeWithText("Enable").assertIsDisplayed()
        compose.onNodeWithText("Manage").assertDoesNotExist()
    }
}

