package dev.jarvis.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.memory.MemoryRepository
import dev.jarvis.assistant.response.SpeechResponder
import dev.jarvis.assistant.response.LocalAIProvider
import dev.jarvis.assistant.speech.AndroidSpeechEngine
import dev.jarvis.assistant.speech.SpeechProviderAvailability
import dev.jarvis.core.common.*
import dev.jarvis.core.database.*
import dev.jarvis.core.voice.AssistantService
import dev.jarvis.device.applications.ApplicationRepository
import dev.jarvis.device.scanner.DeviceScanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class AssistantViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    val session: AssistantSession, val store: SettingsStore, val memory: MemoryRepository,
    private val dao: JarvisDao, private val apps: ApplicationRepository,
    private val scanner: DeviceScanner, private val responder: SpeechResponder,
    private val localAI: LocalAIProvider, private val speech: AndroidSpeechEngine
) : ViewModel() {
    val status = session.state.asStateFlow()
    val config = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssistantSettings())
    val notice = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val voices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val speechProvider = MutableStateFlow<SpeechProviderAvailability?>(null)
    private val deviceTasks = Mutex()
    private fun <T> Flow<List<T>>.observed() = catch { notice.value = "Local data could not be read. Clear app data if your device encryption key changed."; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = dao.history().observed()
    val applications = apps.applications.observed()
    val information = dao.deviceInfo().observed()
    val memories = memory.memories.observed()
    init { task("Scanning applications…") { if (apps.list().isEmpty()) apps.scan(); scanner.scanDevice(); "Device index ready" } }
    private fun task(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            // A scan started before a privacy clear must finish before the clear, not repopulate it afterward.
            deviceTasks.withLock {
                busy.value = true; notice.value = label
                try { notice.value = withContext(Dispatchers.IO) { block() } }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    notice.value = if (error is AssistantStopTimeout) "JARVIS is still stopping. Tap Pause, then try clearing again."
                        else "Could not finish. Check the permission and try again."
                    session.log("Device task failed: ${error.javaClass.simpleName}")
                }
                finally { busy.value = false }
            }
        }
    }
    fun refresh() = task("Scanning applications…") { val count = apps.scan(); scanner.scanDevice(); "$count launcher applications indexed" }
    fun contacts() = task("Scanning contacts…") { "${scanner.scanContacts()} contact numbers indexed locally" }
    fun calendar() = task("Scanning calendar…") { "${scanner.scanCalendar()} events indexed for the next seven days" }
    fun addFile(uri: Uri) = task("Indexing selected file…") { scanner.addFile(uri); "File metadata saved locally" }
    fun refreshInfo() = task("Refreshing device information…") { scanner.scanDevice(); "Device information refreshed" }
    fun loadVoices() { viewModelScope.launch { runCatching { voices.value = responder.voices() } } }
    fun refreshSpeechProvider() {
        viewModelScope.launch { speechProvider.value = withContext(Dispatchers.IO) { speech.availability() } }
    }
    fun boolean(key: String, value: Boolean) { viewModelScope.launch { store.boolean(key, value) } }
    fun text(key: String, value: String) { viewModelScope.launch { store.text(key, value) } }
    fun number(key: String, value: Float) { viewModelScope.launch { store.number(key, value) } }
    fun forget(item: UserPreferenceEntity) = task("Deleting…") {
        if (item.kind == "file") runCatching { context.contentResolver.releasePersistableUriPermission(item.content.text.substringAfterLast('\n').toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (item.kind == "memory") stopAndClearConversation()
        memory.forget(item.id); "Deleted"
    }
    private class AssistantStopTimeout : IllegalStateException()
    private suspend fun stopAndClearConversation() {
        context.stopService(Intent(context, AssistantService::class.java))
        if (withTimeoutOrNull(5000) { session.state.first { !it.running } } == null) throw AssistantStopTimeout()
        localAI.cancel()
        localAI.awaitIdle()
        localAI.clearConversation()
        session.state.value = AssistantState(AssistantPhase.PAUSED, "Paused · tap the mic to resume")
    }
    fun clearMemory() = task("Clearing saved memory…") {
        stopAndClearConversation(); memory.clear(); "Saved memory cleared. Listening is paused."
    }
    fun clearHistory() = task("Clearing history…") {
        stopAndClearConversation(); dao.clearHistory(); "Command history cleared. Listening is paused."
    }
    fun resetIndex() = task("Rebuilding application index…") {
        val count = apps.scan(); "$count applications indexed. New apps refresh automatically."
    }
    fun clearData() = task("Clearing local data…") {
        stopAndClearConversation()
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        val alarm = context.getSystemService(android.app.AlarmManager::class.java)
        dao.preferenceList("timer").forEach { timer ->
            val pending = android.app.PendingIntent.getBroadcast(context, timer.id.removePrefix("timer:").toInt(), Intent(context, dev.jarvis.skills.timer.TimerReceiver::class.java), android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarm.cancel(pending); pending.cancel() }
        }
        context.contentResolver.persistedUriPermissions.forEach { grant -> runCatching { context.contentResolver.releasePersistableUriPermission(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        dao.clearAll(); store.reset(); session.logs.value = emptyList(); "All local data cleared. Listening is paused."
    }
}

