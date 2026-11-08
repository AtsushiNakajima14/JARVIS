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
import dev.jarvis.core.common.*
import dev.jarvis.core.database.*
import dev.jarvis.device.applications.ApplicationRepository
import dev.jarvis.device.scanner.DeviceScanner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class AssistantViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    val session: AssistantSession, val store: SettingsStore, val memory: MemoryRepository,
    private val dao: JarvisDao, private val apps: ApplicationRepository,
    private val scanner: DeviceScanner, private val responder: SpeechResponder
) : ViewModel() {
    val status = session.state.asStateFlow()
    val config = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssistantSettings())
    val notice = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val voices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private fun <T> Flow<List<T>>.observed() = catch { notice.value = "Local data could not be read. Clear app data if your device encryption key changed."; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = dao.history().observed()
    val applications = apps.applications.observed()
    val information = dao.deviceInfo().observed()
    val memories = memory.memories.observed()
    init { task("Scanning applications…") { if (apps.list().isEmpty()) apps.scan(); scanner.scanDevice(); "Device index ready" } }
    private fun task(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            busy.value = true; notice.value = label
            try { notice.value = withContext(Dispatchers.IO) { block() } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { notice.value = "Could not finish. Check the permission and try again."; session.log("Device task failed: ${error.javaClass.simpleName}") }
            finally { busy.value = false }
        }
    }
    fun refresh() = task("Scanning applications…") { val count = apps.scan(); scanner.scanDevice(); "$count launcher applications indexed" }
    fun contacts() = task("Scanning contacts…") { "${scanner.scanContacts()} contact numbers indexed locally" }
    fun calendar() = task("Scanning calendar…") { "${scanner.scanCalendar()} events indexed for the next seven days" }
    fun addFile(uri: Uri) = task("Indexing selected file…") { scanner.addFile(uri); "File metadata saved locally" }
    fun refreshInfo() = task("Refreshing device information…") { scanner.scanDevice(); "Device information refreshed" }
    fun loadVoices() { viewModelScope.launch { runCatching { voices.value = responder.voices() } } }
    fun boolean(key: String, value: Boolean) { viewModelScope.launch { store.boolean(key, value) } }
    fun text(key: String, value: String) { viewModelScope.launch { store.text(key, value) } }
    fun number(key: String, value: Float) { viewModelScope.launch { store.number(key, value) } }
    fun forget(item: UserPreferenceEntity) = task("Deleting…") {
        if (item.kind == "file") runCatching { context.contentResolver.releasePersistableUriPermission(item.content.text.substringAfterLast('\n').toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        memory.forget(item.id); "Deleted"
    }
    fun clearMemory() = task("Clearing saved memory…") { memory.clear(); "Saved memory cleared" }
    fun clearHistory() = task("Clearing history…") { dao.clearHistory(); "Command history cleared" }
    fun resetIndex() = task("Resetting index…") { dao.clearApps(); "Application index cleared. Use Refresh to scan again." }
    fun clearData() = task("Clearing local data…") {
        withTimeoutOrNull(3000) { session.state.first { !it.running } }
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        val alarm = context.getSystemService(android.app.AlarmManager::class.java)
        dao.preferenceList("timer").forEach { timer ->
            val pending = android.app.PendingIntent.getBroadcast(context, timer.id.removePrefix("timer:").toInt(), Intent(context, dev.jarvis.skills.timer.TimerReceiver::class.java), android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarm.cancel(pending); pending.cancel() }
        }
        context.contentResolver.persistedUriPermissions.forEach { grant -> runCatching { context.contentResolver.releasePersistableUriPermission(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        dao.clearAll(); store.reset(); session.logs.value = emptyList(); "All local data cleared"
    }
}

