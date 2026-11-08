package dev.jarvis

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.core.common.*
import dev.jarvis.core.permissions.PermissionStatus
import dev.jarvis.core.voice.AssistantService
import dev.jarvis.ui.JarvisApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var session: AssistantSession
    @Inject lateinit var settings: SettingsStore
    private var requestedAction: Pair<String, String?>? = null
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestedAction?.let { (action, text) -> startAssistant(action, text) }
        else session.state.value = AssistantState(AssistantPhase.ERROR, "Microphone permission is needed for voice. Enable it in Permissions.")
        requestedAction = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { JarvisApp(onListen = { requestAssistant(AssistantService.ACTION_LISTEN) },
            onWake = { requestAssistant("dev.jarvis.WAKE") }, onCommand = { requestAssistant(AssistantService.ACTION_TEXT, it) }, onStop = ::stopAssistant) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                for (intent in session.launches) {
                    try { startActivity(intent) } catch (error: Exception) { session.log("External activity unavailable"); session.state.value = session.state.value.copy(detail = "That application could not be opened.") }
                }
            }
        }
    }
    private fun requestAssistant(action: String, text: String? = null) {
        if (!PermissionStatus.microphone(this)) { requestedAction = action to text; microphone.launch(Manifest.permission.RECORD_AUDIO) }
        else startAssistant(action, text)
    }
    private fun startAssistant(action: String, text: String?) {
        try { ContextCompat.startForegroundService(this, Intent(this, AssistantService::class.java).setAction(action).putExtra(AssistantService.EXTRA_TEXT, text)) }
        catch (error: Exception) { session.state.value = AssistantState(AssistantPhase.ERROR, "Android could not start listening. Reopen JARVIS and try again.") }
    }
    private fun stopAssistant() { stopService(Intent(this, AssistantService::class.java)) }
    override fun onStart() { super.onStart(); session.visible = true }
    override fun onStop() {
        session.visible = false
        if (!isChangingConfigurations) lifecycleScope.launch { if (!settings.settings.first().background) stopAssistant() }
        super.onStop()
    }
}

