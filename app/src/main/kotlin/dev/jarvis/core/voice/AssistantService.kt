package dev.jarvis.core.voice

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.MainActivity
import dev.jarvis.R
import dev.jarvis.assistant.parser.*
import dev.jarvis.assistant.response.SpeechResponder
import dev.jarvis.assistant.response.LocalAIProvider
import dev.jarvis.assistant.response.LocalAiLowMemory
import dev.jarvis.assistant.memory.MemoryRepository
import dev.jarvis.assistant.speech.AndroidSpeechEngine
import dev.jarvis.assistant.speech.SpeechResult
import dev.jarvis.assistant.wakeword.*
import dev.jarvis.core.common.*
import dev.jarvis.core.database.*
import dev.jarvis.core.device.ActionLauncher
import dev.jarvis.core.permissions.PermissionStatus
import dev.jarvis.skills.SkillRegistry
import dev.jarvis.device.applications.ApplicationRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@AndroidEntryPoint
class AssistantService : Service() {
    @Inject lateinit var session: AssistantSession
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var wake: WakeWordEngine
    @Inject lateinit var models: VoskModelStore
    @Inject lateinit var speech: AndroidSpeechEngine
    @Inject lateinit var responder: SpeechResponder
    @Inject lateinit var parser: CommandParser
    @Inject lateinit var skills: SkillRegistry
    @Inject lateinit var dao: JarvisDao
    @Inject lateinit var launcher: ActionLauncher
    @Inject lateinit var applications: ApplicationRepository
    @Inject lateinit var feedback: ListeningFeedback
    @Inject lateinit var localAI: LocalAIProvider
    @Inject lateinit var memory: MemoryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Commands belong to this service lifetime. The latest request replaces any
    // request queued while the previous microphone/native operation is stopping.
    private val events = Channel<AssistantEvent>(Channel.CONFLATED)
    private var listening: Job? = null
    private var lock: PowerManager.WakeLock? = null
    private var renewal: Job? = null
    private var pending: Intent? = null
    private var pendingUntil = 0L
    private var expiry: Job? = null
    private var foreground = false
    private var manuallyPaused = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { clearConfirmation() }
    }
    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        scope.launch {
            for (received in events) {
                listening?.cancelAndJoin()
                var event = received
                while (true) event = events.tryReceive().getOrNull() ?: break
                val request = event
                listening = scope.launch {
                    when (request) {
                        AssistantEvent.Wake -> runAssistant()
                        AssistantEvent.Pause -> pause()
                        AssistantEvent.Listen -> runAssistant(listenFirst = true)
                        is AssistantEvent.Text -> runAssistant(text = request.text)
                    }
                }
            }
        }
        scope.launch { session.state.map { it.phase to it.detail }.distinctUntilChanged().collect { (_, detail) -> if (foreground) notifyStatus(detail) } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { events.trySend(AssistantEvent.Pause); return START_NOT_STICKY }
        manuallyPaused = false
        if (!PermissionStatus.microphone(this)) { session.state.value = AssistantState(AssistantPhase.ERROR, "Allow Microphone in Permissions"); stopSelf(); return START_NOT_STICKY }
        try {
            createChannel()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Starting assistant…"),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            foreground = true
        } catch (e: Exception) {
            session.log("Android refused microphone service: ${e.javaClass.simpleName}")
            session.state.value = AssistantState(AssistantPhase.ERROR, "Open JARVIS and start listening again")
            stopSelf(); return START_NOT_STICKY
        }
        if (renewal == null) renewal = scope.launch {
            lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS:listener")
            while (isActive) { lock?.acquire(10 * 60 * 1000L); delay(5 * 60 * 1000L); if (lock?.isHeld == true) lock?.release() }
        }
        when (intent?.action) {
            ACTION_LISTEN -> events.trySend(AssistantEvent.Listen)
            ACTION_TEXT -> events.trySend(AssistantEvent.Text(intent.getStringExtra(EXTRA_TEXT).orEmpty()))
            else -> if (listening?.isActive != true) events.trySend(AssistantEvent.Wake)
        }
        return START_STICKY
    }
    private suspend fun runAssistant(listenFirst: Boolean = false, text: String? = null) {
        try {
            var failures = 0
            localAI.awaitIdle()
            if (text != null) {
                val chat = execute(text)
                if (pending != null) listenForConfirmation()
                if (chat) followUpConversation()
            } else if (listenFirst && listenAndExecute()) followUpConversation()
            while (currentCoroutineContext().isActive && !manuallyPaused) {
                val config = settings.settings.first()
                if (!config.wakeWord || (!config.background && !session.visible)) { pause(); return }
                state(AssistantPhase.STARTING, "Preparing offline wake recognition…")
                models.get()
                state(AssistantPhase.WAKE_WORD, "Say Jarvis")
                try {
                    val turn = currentCoroutineContext().job
                    val labels = appLabels()
                    val activation = wake.awaitWakeWord(labels,
                        onDetected = { session.state.update { current ->
                            if (turn.isActive) current.copy(phase = AssistantPhase.LISTENING,
                                detail = "Listening… keep speaking", running = true, transcript = "",
                                recognitionSource = "Offline English · device commands") else current
                        } },
                        onPartial = { partial -> session.state.update { if (turn.isActive) it.copy(transcript = partial) else it } })
                    val heard = SpeechSelection(parser).choose(activation.alternatives, config.name)
                    val chat = if (stripAssistantPrefix(heard, config.name).isBlank()) listenAndExecute()
                    else if (config.networkSpeech && parser.parse(heard, config.name).type in setOf(
                        IntentType.UNKNOWN, IntentType.RECALL, IntentType.CALL, IntentType.MESSAGE,
                        IntentType.REMEMBER, IntentType.CONFIRM, IntentType.CANCEL)) {
                        say("Please repeat your command after the tone.")
                        listenAndExecute()
                    } else {
                        session.state.update { it.copy(recognitionSource = activation.source) }
                        val conversation = execute(heard)
                        if (pending != null) listenForConfirmation()
                        conversation
                    }
                    if (chat) followUpConversation()
                    failures = 0
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    failures++
                    session.log("Listener retry $failures: ${error.javaClass.simpleName}")
                    state(AssistantPhase.ERROR, "Microphone unavailable. Retrying…")
                    if (!PermissionStatus.microphone(this@AssistantService) || failures >= 6) {
                        state(AssistantPhase.ERROR, "Listening stopped. Check Microphone permission, then tap the mic.")
                        stopSelf(); return
                    }
                    delay((1000L shl failures.coerceAtMost(5)).coerceAtMost(30000))
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            session.log("Assistant stopped: ${error.javaClass.simpleName}")
            state(AssistantPhase.ERROR, "Could not complete that request. Tap the mic to try again.")
            stopSelf()
        }
    }
    private suspend fun listenAndExecute(): Boolean {
        var result = ""
        for (attempt in 0..1) {
            state(AssistantPhase.STARTING, "Preparing microphone…")
            try {
                val config = settings.settings.first()
                val recognition = recognize(config)
                result = if (recognition.wakeOnly) "" else SpeechSelection(parser).choose(recognition.alternatives, config.name)
                if (result.isNotBlank()) break
            } catch (timeout: TimeoutCancellationException) { /* A bounded listening window; wake detection resumes. */ }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { session.log("Speech retry: ${error.javaClass.simpleName}"); delay(400) }
            if (attempt == 0) say("Please say your command after the tone.")
        }
        if (result.isBlank()) { clearConfirmation(); say("I did not catch that."); return false }
        val chat = execute(result)
        if (pending != null) listenForConfirmation()
        return chat
    }
    private suspend fun listenForConfirmation() {
        if (pending != null) {
            state(AssistantPhase.STARTING, "Preparing confirmation microphone…")
            val reply = try { recognize(settings.settings.first(), confirmation = true).text }
                catch (timeout: TimeoutCancellationException) { "cancel" }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { "cancel" }
            execute(reply)
        }
    }
    private suspend fun appLabels(): List<String> {
        if (applications.list().isEmpty()) applications.scan()
        return applications.list().map { it.label }
    }
    private suspend fun recognize(config: AssistantSettings, confirmation: Boolean = false, followUp: Boolean = false): SpeechResult {
        val turn = currentCoroutineContext().job
        return speech.recognize(config, appLabels(), confirmation,
            onReady = { source ->
                if (turn.isActive) {
                    session.state.update { if (turn.isActive) it.copy(phase = AssistantPhase.LISTENING,
                        detail = when { confirmation -> "Say confirm or cancel"; followUp -> "Listening for your follow-up…"; else -> "Listening… speak your command" },
                        transcript = "", recognitionSource = source, running = true) else it }
                    feedback.ready()
                }
            }, onPartial = { partial -> session.state.update { if (turn.isActive) it.copy(transcript = partial) else it } })
    }
    private suspend fun followUpConversation() {
        repeat(4) {
            val config = settings.settings.first()
            if (!config.conversation || manuallyPaused || (!config.background && !session.visible)) return
            state(AssistantPhase.STARTING, "Ready for a follow-up…")
            val recognition = try { withTimeoutOrNull(12000) { recognize(config, followUp = true) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { null } ?: return
            val text = SpeechSelection(parser).choose(recognition.alternatives, config.name)
            if (text.isBlank() || recognition.wakeOnly) return
            val ending = normalize(stripAssistantPrefix(text, config.name))
            if (ending in setOf("thanks", "thank you", "that's all", "that is all", "goodbye", "stop", "cancel", "salamat", "tama na")) {
                say(if (ending in setOf("thanks", "thank you", "salamat")) "You’re welcome." else "Okay.")
                return
            }
            val chat = execute(text)
            if (pending != null) listenForConfirmation()
            if (!chat) return
        }
    }
    private suspend fun localReply(text: String): String {
        state(AssistantPhase.PROCESSING, "Thinking on this phone…")
        models.release()
        return try { localAI.answer(text) }
        catch (cancel: CancellationException) { throw cancel }
        catch (low: LocalAiLowMemory) { "Your phone is low on free memory. Close another app and try again." }
        catch (link: LinkageError) { session.log("Local AI native runtime unavailable"); "Local AI could not start on this phone. Your phone commands still work." }
        catch (error: Exception) { session.log("Local AI reply failed: ${error.javaClass.simpleName}"); "I could not finish that thought. Try a shorter question." }
    }
    private suspend fun execute(text: String): Boolean {
        state(AssistantPhase.PROCESSING, "Processing…")
        session.state.update { it.copy(transcript = text) }
        val config = settings.settings.first()
        val parsed = parser.parse(text, config.name)
        val epoch = localAI.currentRevision()
        if (normalize(stripAssistantPrefix(text, config.name)) in setOf("stop listening", "pause listening", "go to sleep", "sleep now")) {
            clearConfirmation(); say("Listening paused."); pause(); return false
        }
        var conversational = false
        var usedAI = false
        var skillName = "Assistant"
        val response: String = when (parsed.type) {
            IntentType.CONFIRM -> {
                val action = pending
                val valid = SystemClock.elapsedRealtime() <= pendingUntil && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
                clearConfirmation()
                if (action == null || !valid) "There is no active confirmation. Please ask again with your phone unlocked."
                else launcher.launch(action) ?: "Opening it for you. Complete the action in your phone app."
            }
            IntentType.CANCEL -> { clearConfirmation(); "Cancelled." }
            IntentType.HELP -> { clearConfirmation(); "Try open Discord, turn on flashlight, battery percentage, set a timer for five minutes, or remember that I use Discord for school." }
            IntentType.UNKNOWN, IntentType.RECALL -> {
                clearConfirmation()
                val saved = if (config.memory && parsed.type == IntentType.RECALL) memory.recall(text) else null
                when {
                    saved != null -> { conversational = true; skillName = "Local memory"; "You told me: $saved." }
                    config.localAi -> { conversational = true; usedAI = true; skillName = "Local AI"; localReply(text) }
                    else -> "I heard “${text.take(80)}”. Local conversation is off. Say help for phone commands."
                }
            }
            else -> {
                clearConfirmation()
                val skill = skills.match(parsed)
                if (skill == null) {
                    if (parsed.type == IntentType.TIMER) "Please give a timer duration from one second to 24 hours."
                    else "I heard “${text.take(80)}”, but could not match a command. Try battery percentage or open Discord."
                } else {
                    skillName = skill.name
                    val result = try { skill.execute(parsed) } catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) { session.log("Skill failed: ${skill.name}, ${error.javaClass.simpleName}"); dev.jarvis.skills.SkillResult("I could not complete that action. Check its permission and try again.") }
                    if (result.confirmation != null && result.launch != null) {
                        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) "Unlock your phone and ask again to confirm this action."
                        else {
                            pending = result.launch; pendingUntil = SystemClock.elapsedRealtime() + 30000
                            expiry = scope.launch { delay(30000); clearConfirmation() }
                            session.state.update { it.copy(confirmation = result.confirmation) }
                            result.response
                        }
                    } else if (result.launch != null) launcher.launch(result.launch) ?: result.response
                    else result.response
                }
            }
        }
        val followUpQueries = setOf(IntentType.BATTERY_STATUS, IntentType.DEVICE_INFO, IntentType.SYSTEM_STATUS,
            IntentType.MEMORY_USAGE, IntentType.STORAGE, IntentType.MOST_USED, IntentType.RARELY_USED,
            IntentType.LIST_APPLICATIONS, IntentType.SEARCH_APPLICATIONS, IntentType.RECENT_APPLICATIONS, IntentType.REMEMBER)
        if (parsed.type in followUpQueries) conversational = true
        if (!usedAI && parsed.type !in setOf(IntentType.CALL, IntentType.MESSAGE, IntentType.CONFIRM, IntentType.CANCEL))
            localAI.recordTurn(text, response, epoch)
        if (config.history) withContext(Dispatchers.IO) {
            dao.addHistory(CommandHistoryEntity(command = SecretText(text), response = SecretText(response), skill = skillName)); dao.trimHistory()
        }
        say(response)
        return conversational
    }
    private fun clearConfirmation() { expiry?.cancel(); expiry = null; pending = null; pendingUntil = 0; session.state.update { it.copy(confirmation = null) } }
    private suspend fun say(text: String) {
        session.state.update { it.copy(lastResponse = text) }
        state(AssistantPhase.SPEAKING, text)
        try { responder.speak(text, settings.settings.first()) }
        catch (timeout: TimeoutCancellationException) { session.log("Speech output exceeded its time limit") }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { session.log("Speech output unavailable"); session.state.update { it.copy(detail = "$text · Install an offline TTS voice in Android settings if you cannot hear me.") } }
        delay(180) // Let the speaker drain before reacquiring the microphone.
    }
    private fun state(phase: AssistantPhase, detail: String) { session.state.update { it.copy(phase = phase, detail = detail, running = true) } }
    private fun pause() {
        manuallyPaused = true
        clearConfirmation()
        session.state.update { it.copy(phase = AssistantPhase.PAUSED, detail = "Paused · tap the mic to resume", confirmation = null, running = false) }
        stopSelf()
    }
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_service", "Assistant status", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) }) }
    private fun notification(detail: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "jarvis_service").setSmallIcon(R.drawable.ic_notification).setContentTitle("JARVIS is active")
            .setContentText(detail.take(100)).setVisibility(NotificationCompat.VISIBILITY_SECRET).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(0, "Pause", pause).addAction(0, "Open app", open).build()
    }
    private fun notifyStatus(detail: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(detail)) }
    override fun onDestroy() {
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        events.close()
        scope.cancel(); localAI.cancel(); renewal = null
        val stoppedVoiceModels = models
        CoroutineScope(Dispatchers.IO).launch { stoppedVoiceModels.release() }
        if (lock?.isHeld == true) lock?.release()
        unregisterReceiver(screenReceiver)
        clearConfirmation()
        responder.shutdown()
        // Active recognizers hold leases, so a rapid restart cannot close their model.
        session.state.update { it.copy(running = false, phase = if (it.phase == AssistantPhase.ERROR) it.phase else AssistantPhase.PAUSED) }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ACTION_LISTEN = "dev.jarvis.LISTEN"
        const val ACTION_TEXT = "dev.jarvis.TEXT"
        const val ACTION_STOP = "dev.jarvis.STOP"
        const val EXTRA_TEXT = "text"
        const val NOTIFICATION_ID = 10
    }
}

