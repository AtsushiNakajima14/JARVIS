package dev.jarvis.ui

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.KeyguardManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.memory.MemoryRepository
import dev.jarvis.assistant.response.SpeechResponder
import dev.jarvis.assistant.response.LocalAIProvider
import dev.jarvis.assistant.notifications.NotificationRepository
import dev.jarvis.assistant.speech.AndroidSpeechEngine
import dev.jarvis.assistant.speech.SpeechProviderAvailability
import dev.jarvis.core.common.*
import dev.jarvis.core.database.*
import dev.jarvis.core.voice.AssistantService
import dev.jarvis.device.applications.ApplicationRepository
import dev.jarvis.device.scanner.DeviceScanner
import dev.jarvis.device.knowledge.DeviceKnowledgeRepository
import dev.jarvis.assistant.abilities.ReminderRepository
import dev.jarvis.assistant.abilities.FileKnowledgeRepository
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
    private val localAI: LocalAIProvider, private val speech: AndroidSpeechEngine,
    private val notifications: NotificationRepository,
    private val knowledge: DeviceKnowledgeRepository,
    private val remindersRepository: ReminderRepository,
    private val filesRepository: FileKnowledgeRepository
) : ViewModel() {
    val status = session.state.asStateFlow()
    val config = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssistantSettings())
    val notice = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val voices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val speechProvider = MutableStateFlow<SpeechProviderAvailability?>(null)
    val notificationAccess = MutableStateFlow(false)
    private val unlocked = MutableStateFlow(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            unlocked.value = intent.action != Intent.ACTION_SCREEN_OFF && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            if (!unlocked.value) knowledge.clearSnapshot()
        }
    }
    private val deviceTasks = Mutex()
    private var fileSelectionRevision = filesRepository.currentRevision()
    private fun <T> Flow<List<T>>.observed() = catch { notice.value = "Local data could not be read. Clear app data if your device encryption key changed."; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = combine(dao.history(), unlocked) { items, allowed -> if (allowed) items else emptyList() }.observed()
    val conversation = combine(session.conversation, unlocked) { items, allowed -> if (allowed) items else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val deviceKnowledge = knowledge.snapshot
    val reminders = combine(remindersRepository.reminders, unlocked) { items, allowed -> if (allowed) items else emptyList() }.observed()
    val knownFiles = combine(filesRepository.knownFiles, unlocked) { items, allowed -> if (allowed) items else emptyList() }.observed()
    val applications = apps.applications.observed()
    val information = combine(dao.deviceInfo(), unlocked) { items, allowed -> if (allowed) items else emptyList() }.observed()
    val memories = combine(memory.memories, store.settings, notificationAccess, unlocked) { items, config, allowed, isUnlocked ->
        if (!isUnlocked) emptyList() else items.mapNotNull { item ->
            if (item.kind in setOf("companion_reminder", "companion_file", "timer", "file")) null
            else if (item.kind != "notification_message") item
            else if (!config.notificationReading || !allowed) null
            else notifications.savedPreviewLabel(item)?.let { item.copy(content = SecretText(it)) }
        }
    }.flowOn(Dispatchers.IO).observed()
    init {
        ContextCompat.registerReceiver(context, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        task("Getting to know your phone…") { filesRepository.files(); knowledge.refresh(); "Phone awareness refreshed" }
    }
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
    fun refresh() = task("Updating phone awareness…") { val count = apps.scan(); filesRepository.files(); knowledge.refresh(); "$count launcher applications indexed" }
    fun contacts() = task("Scanning contacts…") { "${scanner.scanContacts()} contact numbers indexed locally" }
    fun calendar() = task("Scanning calendar…") { "${scanner.scanCalendar()} events indexed for the next seven days" }
    fun beginFileSelection() { fileSelectionRevision = filesRepository.currentRevision() }
    fun addFile(uri: Uri) = task("Remembering selected file…") {
        if (filesRepository.add(uri, fileSelectionRevision)) "File metadata saved locally"
        else "This file could not be added. Choose a document available on this phone."
    }
    fun refreshInfo() = task("Refreshing device information…") { knowledge.refresh(); "Device information refreshed" }
    fun completeReminder(id: String) = task("Updating reminder…") { if (remindersRepository.completeId(id)) "Reminder completed" else "Reminder is no longer available" }
    fun forgetReminder(id: String) = task("Deleting reminder…") { stopAndClearConversation(); remindersRepository.forget(id); "Reminder deleted" }
    fun forgetFile(id: String) = task("Forgetting selected file…") { stopAndClearConversation(); filesRepository.forget(id); "File access and saved metadata removed" }
    fun loadVoices() { viewModelScope.launch { runCatching { voices.value = responder.voices() } } }
    fun refreshSpeechProvider() {
        viewModelScope.launch { speechProvider.value = withContext(Dispatchers.IO) { speech.availability() } }
    }
    fun refreshNotificationAccess() {
        viewModelScope.launch {
            unlocked.value = !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            val allowed = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            notificationAccess.value = allowed
            if (!allowed) withContext(Dispatchers.IO) { notifications.clear() }
            try {
                deviceTasks.withLock {
                    withContext(Dispatchers.IO) {
                        filesRepository.files()
                        // Refresh optional permissions after returning from Android settings.
                        knowledge.refresh()
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { session.log("Phone awareness refresh unavailable") }
        }
    }
    fun boolean(key: String, value: Boolean) { viewModelScope.launch { store.boolean(key, value) } }
    fun notificationReading(enabled: Boolean) = task(if (enabled) "Enabling notification awareness…" else "Deleting notification previews…") {
        if (!enabled) stopAndClearConversation()
        store.boolean("notification_reading", enabled)
        if (!enabled) notifications.clear()
        if (enabled) "Notification reading enabled. Allow Notification access in Android settings."
        else "Notification previews deleted. Listening is paused."
    }
    fun clearNotifications() = task("Deleting captured notifications…") {
        stopAndClearConversation()
        notifications.clear()
        "Captured notifications deleted. New notifications can be captured while reading is enabled."
    }
    fun text(key: String, value: String) { viewModelScope.launch { store.text(key, value) } }
    fun number(key: String, value: Float) { viewModelScope.launch { store.number(key, value) } }
    fun forget(item: UserPreferenceEntity) = task("Deleting…") {
        if (item.kind in setOf("memory", "notification_message")) stopAndClearConversation()
        if (item.kind == "notification_message") notifications.forget(item.id) else memory.forget(item.id)
        "Deleted"
    }
    private class AssistantStopTimeout : IllegalStateException()
    private suspend fun stopAndClearConversation() {
        context.stopService(Intent(context, AssistantService::class.java))
        if (withTimeoutOrNull(5000) { session.state.first { !it.running } } == null) throw AssistantStopTimeout()
        localAI.cancel()
        localAI.awaitIdle()
        localAI.clearConversation()
        session.clearConversation()
        session.state.value = AssistantState(AssistantPhase.PAUSED, "Paused · tap the mic to resume")
    }
    fun clearMemory() = task("Clearing saved memory…") {
        stopAndClearConversation(); memory.clear(); "Saved memory cleared. Listening is paused."
    }
    fun clearHistory() = task("Clearing history…") {
        stopAndClearConversation(); dao.clearHistory(); "Conversation history cleared. Listening is paused."
    }
    fun resetIndex() = task("Rebuilding application index…") {
        val count = apps.scan(); "$count applications indexed. New apps refresh automatically."
    }
    fun clearData() = task("Clearing local data…") {
        stopAndClearConversation()
        // Stop incoming capture before clearing the database, so a notification
        // arriving during this operation cannot restore deleted message previews.
        store.boolean("notification_reading", false)
        store.boolean("notification_announcements", false)
        store.boolean("notifications", false)
        notifications.clear()
        remindersRepository.clear()
        filesRepository.clear()
        knowledge.clearSnapshot()
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        val alarm = context.getSystemService(android.app.AlarmManager::class.java)
        dao.preferenceList("timer").forEach { timer ->
            val pending = android.app.PendingIntent.getBroadcast(context, timer.id.removePrefix("timer:").toInt(), Intent(context, dev.jarvis.skills.timer.TimerReceiver::class.java), android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarm.cancel(pending); pending.cancel() }
        }
        context.contentResolver.persistedUriPermissions.forEach { grant -> runCatching { context.contentResolver.releasePersistableUriPermission(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        dao.clearAll(); store.reset(); session.logs.value = emptyList(); "All local data cleared. Listening is paused."
    }
    override fun onCleared() {
        context.unregisterReceiver(screenReceiver)
        super.onCleared()
    }
}

