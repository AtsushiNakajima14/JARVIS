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
import dev.jarvis.assistant.speech.AndroidSpeechEngine
import dev.jarvis.assistant.wakeword.*
import dev.jarvis.core.common.*
import dev.jarvis.core.database.*
import dev.jarvis.core.device.ActionLauncher
import dev.jarvis.core.permissions.PermissionStatus
import dev.jarvis.skills.SkillRegistry
import kotlinx.coroutines.*
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private var lock: PowerManager.WakeLock? = null
    private var renewal: Job? = null
    private var pending: Intent? = null
    private var pendingUntil = 0L
    private var expiry: Job? = null
    private var foreground = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { clearConfirmation() }
    }
    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        scope.launch {
            for (event in session.events) {
                val previous = listening
                listening = scope.launch {
                    previous?.cancelAndJoin()
                    when (event) {
                        AssistantEvent.Pause -> pause()
                        AssistantEvent.Listen -> runAssistant(listenFirst = true)
                        is AssistantEvent.Text -> runAssistant(text = event.text)
                    }
                }
            }
        }
        scope.launch { session.state.map { it.phase to it.detail }.distinctUntilChanged().collect { (_, detail) -> if (foreground) notifyStatus(detail) } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { session.send(AssistantEvent.Pause); return START_NOT_STICKY }
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
            ACTION_LISTEN -> session.send(AssistantEvent.Listen)
            ACTION_TEXT -> session.send(AssistantEvent.Text(intent.getStringExtra(EXTRA_TEXT).orEmpty()))
            else -> if (listening?.isActive != true) listening = scope.launch { runAssistant() }
        }
        return START_STICKY
    }
    private suspend fun runAssistant(listenFirst: Boolean = false, text: String? = null) {
        try {
            var failures = 0
            if (text != null) { execute(text); if (pending != null) listenForConfirmation() }
            else if (listenFirst) listenAndExecute()
            while (currentCoroutineContext().isActive) {
                val config = settings.settings.first()
                if (!config.wakeWord || (!config.background && !session.visible)) { pause(); return }
                state(AssistantPhase.STARTING, "Preparing offline wake recognition…")
                models.get()
                state(AssistantPhase.WAKE_WORD, "Say Jarvis")
                try {
                    wake.awaitWakeWord()
                    say("Yes?")
                    listenAndExecute()
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
    private suspend fun listenAndExecute() {
        var result = ""
        for (attempt in 0..1) {
            state(AssistantPhase.LISTENING, if (attempt == 0) "Listening…" else "Please try once more…")
            try {
                result = speech.recognize(settings.settings.first()) { partial -> session.state.update { it.copy(transcript = partial) } }
                if (result.isNotBlank()) break
            } catch (timeout: TimeoutCancellationException) { /* A bounded listening window; wake detection resumes. */ }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { session.log("Speech retry: ${error.javaClass.simpleName}"); delay(400) }
        }
        if (result.isBlank()) { clearConfirmation(); say("I did not catch that."); return }
        execute(result)
        if (pending != null) listenForConfirmation()
    }
    private suspend fun listenForConfirmation() {
        if (pending != null) {
            state(AssistantPhase.LISTENING, "Say confirm or cancel")
            val reply = try { speech.recognize(settings.settings.first()) { partial -> session.state.update { it.copy(transcript = partial) } } }
                catch (timeout: TimeoutCancellationException) { "cancel" }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { "cancel" }
            execute(reply)
        }
    }
    private suspend fun execute(text: String) {
        state(AssistantPhase.PROCESSING, "Processing…")
        session.state.update { it.copy(transcript = text) }
        val config = settings.settings.first()
        val parsed = parser.parse(text, config.name)
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
            else -> {
                clearConfirmation()
                val skill = skills.match(parsed)
                if (skill == null) {
                    if (parsed.type == IntentType.TIMER) "Please give a timer duration from one second to 24 hours." else "I did not understand. Say help for examples."
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
        if (config.history) withContext(Dispatchers.IO) {
            dao.addHistory(CommandHistoryEntity(command = SecretText(text), response = SecretText(response), skill = skillName)); dao.trimHistory()
        }
        say(response)
    }
    private fun clearConfirmation() { expiry?.cancel(); expiry = null; pending = null; pendingUntil = 0; session.state.update { it.copy(confirmation = null) } }
    private suspend fun say(text: String) {
        state(AssistantPhase.SPEAKING, text)
        try { responder.speak(text, settings.settings.first()) }
        catch (timeout: TimeoutCancellationException) { session.log("Speech output exceeded its time limit") }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { session.log("Speech output unavailable"); session.state.update { it.copy(detail = "$text · Install an offline TTS voice in Android settings if you cannot hear me.") } }
        delay(180) // Let the speaker drain before reacquiring the microphone.
    }
    private fun state(phase: AssistantPhase, detail: String) { session.state.update { it.copy(phase = phase, detail = detail, running = true) } }
    private fun pause() {
        clearConfirmation()
        session.state.value = AssistantState(AssistantPhase.PAUSED, "Paused · tap the mic to resume")
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
        scope.cancel(); renewal = null
        if (lock?.isHeld == true) lock?.release()
        unregisterReceiver(screenReceiver)
        clearConfirmation()
        responder.shutdown()
        // The immutable model stays cached for the process. A rapid service restart must
        // never close a native model while a new recognizer is acquiring it.
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

