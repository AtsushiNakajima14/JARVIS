package dev.jarvis.core.voice

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.MainActivity
import dev.jarvis.R
import dev.jarvis.assistant.parser.*
import dev.jarvis.assistant.response.SpeechResponder
import dev.jarvis.assistant.response.LocalAIProvider
import dev.jarvis.assistant.response.LocalAiLowMemory
import dev.jarvis.assistant.response.AndroidNativeProvider
import dev.jarvis.assistant.agent.JarvisAgent
import dev.jarvis.assistant.agent.AgentDecision
import dev.jarvis.assistant.memory.MemoryRepository
import dev.jarvis.assistant.notifications.NotificationAnnouncement
import dev.jarvis.assistant.notifications.NotificationRepository
import dev.jarvis.core.security.PrivateCommandPolicy
import dev.jarvis.assistant.speech.AndroidSpeechEngine
import dev.jarvis.assistant.speech.SpeechResult
import dev.jarvis.assistant.wakeword.*
import dev.jarvis.core.common.*
import dev.jarvis.core.database.*
import dev.jarvis.core.device.ActionLauncher
import dev.jarvis.core.permissions.PermissionStatus
import dev.jarvis.skills.AbilityRegistry
import dev.jarvis.device.applications.ApplicationRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong

@AndroidEntryPoint
class AssistantService : Service() {
    @Inject lateinit var session: AssistantSession
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var wake: WakeWordEngine
    @Inject lateinit var models: VoskModelStore
    @Inject lateinit var speech: AndroidSpeechEngine
    @Inject lateinit var responder: SpeechResponder
    @Inject lateinit var parser: CommandParser
    @Inject lateinit var abilities: AbilityRegistry
    @Inject lateinit var agent: JarvisAgent
    @Inject lateinit var nativeProvider: AndroidNativeProvider
    @Inject lateinit var dao: JarvisDao
    @Inject lateinit var launcher: ActionLauncher
    @Inject lateinit var applications: ApplicationRepository
    @Inject lateinit var feedback: ListeningFeedback
    @Inject lateinit var localAI: LocalAIProvider
    @Inject lateinit var memory: MemoryRepository
    @Inject lateinit var notifications: NotificationRepository
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
    private var voiceSession = false
    private var textTimeout: Job? = null
    private var privateTurn: Job? = null
    private var privateReplyVisible = false
    private var notificationReplyVisible = false
    private var notificationConversationRevision: Long? = null
    private val pendingAnnouncement = MutableStateFlow<NotificationAnnouncement?>(null)
    private val lastAudioActivity = AtomicLong()
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            clearConfirmation()
            agent.reset()
            session.clearConversation()
            localAI.clearConversation()
            notificationConversationRevision = null
            notificationReplyVisible = false
            if (privateReplyVisible) {
                privateReplyVisible = false
                session.state.update { it.copy(transcript = "", lastResponse = "", detail = "Phone locked · private readout stopped") }
            }
            // Screen lock can invalidate planning before a turn has been labelled private.
            // The event owner cancels and joins the old operation before reopening audio.
            if (!manuallyPaused && voiceSession) events.trySend(AssistantEvent.Wake)
            else if (!voiceSession) {
                listening?.cancel()
                session.state.update { it.copy(phase = AssistantPhase.IDLE, running = false) }
            }
        }
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
        // This collector never starts the service. Only an explicitly running
        // assistant may announce, and the latest alert waits for idle wake listening.
        scope.launch { notifications.announcements.collect { pendingAnnouncement.value = it } }
        scope.launch { notifications.invalidations.collect {
            pendingAnnouncement.value = null
            if (notificationConversationRevision == session.conversationRevision.value) {
                session.clearConversation()
                notificationConversationRevision = null
            }
            if (notificationReplyVisible) {
                clearPrivateDisplay()
                if (privateTurn?.isActive == true) {
                    privateTurn?.cancel()
                    if (!manuallyPaused && voiceSession) events.trySend(AssistantEvent.Wake)
                    else if (!voiceSession) session.state.update { it.copy(phase = AssistantPhase.IDLE, running = false) }
                }
            }
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { manuallyPaused = true; events.trySend(AssistantEvent.Pause); return START_NOT_STICKY }
        manuallyPaused = false
        val microphoneAllowed = PermissionStatus.microphone(this)
        val textRequest = intent?.action == ACTION_TEXT
        if (!textRequest && !microphoneAllowed) { session.state.value = AssistantState(AssistantPhase.ERROR, "Allow Microphone in Permissions"); stopSelf(); return START_NOT_STICKY }
        voiceSession = if (textRequest) voiceSession && microphoneAllowed else true
        if (!voiceSession) {
            renewal?.cancel()
            renewal = null
            if (lock?.isHeld == true) lock?.release()
        }
        textTimeout?.cancel()
        textTimeout = if (voiceSession) null else scope.launch { delay(120_000); stopSelf() }
        try {
            createChannel()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Starting assistant…"),
                when {
                    voiceSession && Build.VERSION.SDK_INT >= 30 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    !voiceSession && Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                    else -> 0
                })
            foreground = true
        } catch (e: Exception) {
            session.log("Android refused microphone service: ${e.javaClass.simpleName}")
            session.state.value = AssistantState(AssistantPhase.ERROR, "Open JARVIS and start listening again")
            stopSelf(); return START_NOT_STICKY
        }
        if (voiceSession && renewal == null) renewal = scope.launch {
            lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS:listener")
            while (isActive) { lock?.acquire(10 * 60 * 1000L); delay(5 * 60 * 1000L); if (lock?.isHeld == true) lock?.release() }
        }
        when (intent?.action) {
            ACTION_LISTEN -> events.trySend(AssistantEvent.Listen)
            ACTION_TEXT -> events.trySend(AssistantEvent.Text(intent.getStringExtra(EXTRA_TEXT).orEmpty()))
            else -> if (listening?.isActive != true) events.trySend(AssistantEvent.Wake)
        }
        return if (voiceSession) START_STICKY else START_NOT_STICKY
    }
    private suspend fun runAssistant(listenFirst: Boolean = false, text: String? = null) {
        try {
            var failures = 0
            localAI.awaitIdle()
            if (text != null) {
                session.state.update { it.copy(recognitionSource = "Typed") }
                val chat = execute(text)
                if (!voiceSession) {
                    session.state.update { it.copy(phase = AssistantPhase.IDLE, detail = "Ready for your reply", running = false) }
                    return
                }
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
                    val labels = appLabels()
                    val activation = when (val event = awaitWakeEvent(labels)) {
                        is WakeEvent.Command -> event.activation
                        is WakeEvent.Announcement -> {
                            pendingAnnouncement.compareAndSet(event.value, null)
                            if (canAnnounce(event.value)) { clearPrivateDisplay(); say(event.value.text) }
                            continue
                        }
                    }
                    val heard = if (agent.hasPendingReply()) activation.alternatives.firstOrNull().orEmpty()
                        else SpeechSelection(parser).choose(activation.alternatives, config.name)
                    val chat = if (stripAssistantPrefix(heard, config.name).isBlank()) listenAndExecute()
                    else {
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
    private sealed interface WakeEvent {
        data class Command(val activation: WakeActivation) : WakeEvent
        data class Announcement(val value: NotificationAnnouncement) : WakeEvent
    }
    private suspend fun canAnnounce(event: NotificationAnnouncement): Boolean {
        val config = settings.settings.first()
        val mode = getSystemService(AudioManager::class.java).mode
        val interruptions = getSystemService(NotificationManager::class.java).currentInterruptionFilter
        return config.notificationAnnouncements && !manuallyPaused &&
            (config.background || session.visible) && notifications.isCurrent(event) &&
            System.currentTimeMillis() - event.postedAt in 0L..60_000L &&
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName) &&
            interruptions in setOf(NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN) &&
            mode != AudioManager.MODE_IN_CALL && mode != AudioManager.MODE_IN_COMMUNICATION
    }
    private suspend fun awaitWakeEvent(labels: List<String>): WakeEvent = coroutineScope {
        lastAudioActivity.set(SystemClock.elapsedRealtime())
        val command = async {
            val turn = currentCoroutineContext().job
            wake.awaitWakeWord(labels,
                onDetected = { session.state.update { current ->
                    if (turn.isActive) current.copy(phase = AssistantPhase.LISTENING,
                        detail = "Listening… keep speaking", running = true, transcript = "",
                        recognitionSource = "Offline English · device commands") else current
                } },
                onPartial = { partial -> session.state.update {
                    if (turn.isActive) it.copy(transcript = PrivateCommandPolicy.transcript(partial)) else it
                } }, onAudioActivity = { lastAudioActivity.set(SystemClock.elapsedRealtime()) }, freeForm = agent.hasPendingReply())
        }
        val alert = async {
            combine(pendingAnnouncement, session.state) { candidate, state ->
                candidate?.takeIf { state.phase == AssistantPhase.WAKE_WORD }
            }.filterNotNull().first { candidate ->
                // Defer alerts until there has been a quiet microphone window,
                // including speech that has not yet produced a stable wake word.
                while (session.state.value.phase == AssistantPhase.WAKE_WORD &&
                    pendingAnnouncement.value == candidate && canAnnounce(candidate)) {
                    val quiet = SystemClock.elapsedRealtime() - lastAudioActivity.get()
                    if (quiet >= 1500) break
                    delay((1500 - quiet).coerceIn(100, 1500))
                }
                session.state.value.phase == AssistantPhase.WAKE_WORD && canAnnounce(candidate)
                    && pendingAnnouncement.value == candidate &&
                    SystemClock.elapsedRealtime() - lastAudioActivity.get() >= 1500
            }
        }
        try {
            select<WakeEvent> {
                command.onAwait { WakeEvent.Command(it) }
                alert.onAwait { WakeEvent.Announcement(it) }
            }
        } finally {
            // AudioRecord and its native recognizers must close before alert TTS.
            command.cancelAndJoin()
            alert.cancelAndJoin()
        }
    }
    private suspend fun listenAndExecute(): Boolean {
        var result = ""
        for (attempt in 0..1) {
            state(AssistantPhase.STARTING, "Preparing microphone…")
            try {
                val config = settings.settings.first()
                val recognition = recognize(config)
                result = if (recognition.wakeOnly) "" else selectTranscript(recognition, config)
                if (result.isNotBlank()) break
            } catch (timeout: TimeoutCancellationException) { /* A bounded listening window; wake detection resumes. */ }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { session.log("Speech retry: ${error.javaClass.simpleName}"); delay(400) }
            if (attempt == 0) say("I'm listening. Tell me what you need after the tone.")
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
                        detail = when { confirmation -> "Say confirm or cancel"; followUp -> "Listening for your follow-up…"; else -> "Listening… tell me what you need" },
                        transcript = "", recognitionSource = source, running = true) else it }
                    feedback.ready()
                }
            }, onPartial = { partial -> session.state.update { if (turn.isActive) it.copy(transcript = PrivateCommandPolicy.transcript(partial)) else it } },
            freeForm = followUp || agent.hasPendingReply())
    }
    private fun selectTranscript(recognition: SpeechResult, config: AssistantSettings): String =
        if (agent.hasPendingReply()) recognition.text else SpeechSelection(parser).choose(recognition.alternatives, config.name)
    private suspend fun followUpConversation() {
        repeat(6) {
            val config = settings.settings.first()
            if (!config.conversation || manuallyPaused || (!config.background && !session.visible)) return
            state(AssistantPhase.STARTING, "Ready for a follow-up…")
            val recognition = try { withTimeoutOrNull(12000) { recognize(config, followUp = true) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { null } ?: return
            val text = selectTranscript(recognition, config)
            if (text.isBlank() || recognition.wakeOnly) return
            val ending = normalize(stripAssistantPrefix(text, config.name))
            if (ending in setOf("thanks", "thank you", "that's all", "that is all", "goodbye", "stop", "cancel", "salamat", "tama na")) {
                agent.reset()
                say(if (ending in setOf("thanks", "thank you", "salamat")) "You’re welcome." else "Okay.")
                return
            }
            val chat = execute(text)
            if (pending != null) listenForConfirmation()
            if (!chat) return
        }
    }
    private suspend fun localReply(text: String): String {
        state(AssistantPhase.WORKING, "Thinking on this phone…")
        models.release()
        return try { localAI.answer(text) }
        catch (cancel: CancellationException) { throw cancel }
        catch (low: LocalAiLowMemory) { "Your phone is low on free memory. Close another app and try again." }
        catch (link: LinkageError) { session.log("Local AI native runtime unavailable"); "The optional model could not start. I can still help with your phone and daily tasks." }
        catch (error: Exception) { session.log("Local AI reply failed: ${error.javaClass.simpleName}"); "I could not finish that thought. Try a shorter question." }
    }
    private suspend fun execute(text: String): Boolean {
        // Do not leave a previous message preview visible while a later, slower
        // non-private request is processing or while the screen is locking.
        clearPrivateDisplay()
        if (PrivateCommandPolicy.isCredentialRequest(text)) {
            clearConfirmation()
            agent.reset()
            privateReplyVisible = false
            session.state.update { it.copy(transcript = "[Credential omitted]") }
            say(PrivateCommandPolicy.CREDENTIAL_REPLY)
            return false
        }
        val config = settings.settings.first()
        state(AssistantPhase.UNDERSTANDING, "Understanding what you need…")
        val decision = agent.decide(text, config.name)
        val parsed = decision.action ?: ParsedCommand(IntentType.UNKNOWN, argument = text)
        val privateQuery = decision.isPrivate || PrivateCommandPolicy.privateAbility(parsed.type)
        privateReplyVisible = privateQuery
        notificationReplyVisible = PrivateCommandPolicy.notificationQuery(parsed.type) || parsed.type == IntentType.PREPARE_PHONE
        if (privateQuery) {
            if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                privateReplyVisible = false
                session.state.update { it.copy(transcript = "", lastResponse = "") }
                agent.reset()
                say("Unlock your phone first so I can help with your private information.")
                return false
            }
            privateTurn = currentCoroutineContext().job
        }
        return try { executeCommand(text, parsed, config, privateQuery, decision) }
        finally { if (privateQuery) privateTurn = null }
    }
    private fun clearPrivateDisplay() {
        if (!privateReplyVisible) return
        privateReplyVisible = false
        notificationReplyVisible = false
        session.state.update { it.copy(transcript = "", lastResponse = "", detail = "Ready for your next request") }
    }
    private suspend fun executeCommand(text: String, parsed: ParsedCommand, config: AssistantSettings, privateQuery: Boolean, decision: AgentDecision): Boolean {
        state(AssistantPhase.WORKING, "Working on it…")
        session.state.update { it.copy(transcript = text) }
        val epoch = localAI.currentRevision()
        val conversationEpoch = session.conversationRevision.value
        if (parsed.type == IntentType.MEMORY_FORGET) {
            session.clearConversation()
            localAI.clearConversation()
        }
        if (normalize(stripAssistantPrefix(text, config.name)) in setOf("stop listening", "pause listening", "go to sleep", "sleep now")) {
            clearConfirmation(); say("Listening paused."); pause(); return false
        }
        var conversational = decision.awaitingReply
        var usedAI = false
        var skillName = "Assistant"
        val response: String = if (decision.action == null && decision.response != null) {
            clearConfirmation()
            requireNotNull(decision.response)
        } else when (parsed.type) {
            IntentType.CONFIRM -> {
                val action = pending
                val valid = SystemClock.elapsedRealtime() <= pendingUntil && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
                clearConfirmation()
                if (action == null || !valid) "There is no active confirmation. Please ask again with your phone unlocked."
                else launcher.launch(action) ?: "Opening it for you. Complete the action in your phone app."
            }
            IntentType.CANCEL -> { clearConfirmation(); agent.reset(); "Okay. What else can I help you with?" }
            IntentType.HELP -> { clearConfirmation(); conversational = true; "Tell me what you need, CyCy. I can help you contact someone, write a message, plan your day, find a file, or check on your phone." }
            IntentType.UNKNOWN, IntentType.RECALL -> {
                clearConfirmation()
                val saved = if (config.memory && parsed.type == IntentType.RECALL &&
                    !getSystemService(KeyguardManager::class.java).isKeyguardLocked) memory.recall(text) else null
                val nativeAnswer = nativeProvider.groundedReply(text)
                when {
                    nativeAnswer != null -> { conversational = true; skillName = "Android companion"; nativeAnswer }
                    saved != null -> { conversational = true; skillName = "Local memory"; "You told me: $saved." }
                    config.localAi -> { conversational = true; usedAI = true; skillName = "Local AI"; localReply(text) }
                    else -> { conversational = true; nativeProvider.answer(text) }
                }
            }
            else -> {
                clearConfirmation()
                val skill = abilities.match(parsed)
                if (skill == null) {
                    if (parsed.type == IntentType.TIMER) "Please give a timer duration from one second to 24 hours."
                    else "I need a little more detail. What would you like me to help you do?"
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
            IntentType.LIST_APPLICATIONS, IntentType.SEARCH_APPLICATIONS, IntentType.RECENT_APPLICATIONS, IntentType.REMEMBER,
            IntentType.GREETING, IntentType.TIME_STATUS, IntentType.DATE_STATUS, IntentType.ASSISTANT_IDENTITY, IntentType.CREATOR, IntentType.OWNER_IDENTITY,
            IntentType.NOTIFICATIONS, IntentType.RECENT_CONVERSATIONS, IntentType.DAILY_BRIEFING)
        if (parsed.type in setOf(IntentType.DEVICE_HEALTH, IntentType.PREPARE_PHONE, IntentType.REMINDER_LIST,
                IntentType.REMINDER_CREATE, IntentType.REMINDER_COMPLETE, IntentType.MEMORY_LIST,
                IntentType.MEMORY_FORGET, IntentType.FILE_SEARCH, IntentType.RECENT_FILES)) conversational = true
        if (parsed.type in followUpQueries) conversational = true
        if (!usedAI && !privateQuery && parsed.type !in setOf(IntentType.CALL, IntentType.MESSAGE, IntentType.CONFIRM, IntentType.CANCEL))
            localAI.recordTurn(text, response, epoch)
        if (config.history && !privateQuery) withContext(Dispatchers.IO) {
            dao.addHistory(CommandHistoryEntity(command = SecretText(text), response = SecretText(response), skill = skillName)); dao.trimHistory()
        }
        currentCoroutineContext().ensureActive()
        if (privateQuery && getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            privateReplyVisible = false
            session.state.update { it.copy(transcript = "", lastResponse = "") }
            agent.reset()
            say("Your phone locked. Unlock it to continue with your private information.")
            return false
        }
        session.recordTurn(text, response, conversationEpoch)
        if (notificationReplyVisible && conversationEpoch == session.conversationRevision.value)
            notificationConversationRevision = conversationEpoch
        say(response)
        return conversational || agent.hasPendingReply()
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
        agent.reset()
        session.clearConversation()
        session.state.update { it.copy(phase = AssistantPhase.PAUSED, detail = "Paused · tap the mic to resume", confirmation = null, running = false) }
        stopSelf()
    }
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis_service", "Assistant status", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) }) }
    private fun notification(detail: String): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "jarvis_service").setSmallIcon(R.drawable.ic_notification).setContentTitle("JARVIS is active")
            .setContentText(if (privateReplyVisible) "Helping with a private request" else detail.take(100)).setVisibility(NotificationCompat.VISIBILITY_SECRET).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(0, "Pause", pause).addAction(0, "Open app", open).build()
    }
    private fun notifyStatus(detail: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(detail)) }
    override fun onTimeout(startId: Int) {
        manuallyPaused = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        manuallyPaused = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        events.close()
        scope.cancel(); localAI.cancel(); renewal = null
        agent.reset()
        val stoppedVoiceModels = models
        CoroutineScope(Dispatchers.IO).launch { stoppedVoiceModels.release() }
        if (lock?.isHeld == true) lock?.release()
        unregisterReceiver(screenReceiver)
        clearConfirmation()
        responder.shutdown()
        if (privateReplyVisible) session.state.update { it.copy(transcript = "", lastResponse = "", detail = "Paused · tap the mic to resume") }
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

