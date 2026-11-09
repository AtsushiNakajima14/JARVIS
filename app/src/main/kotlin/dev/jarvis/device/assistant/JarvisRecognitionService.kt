package dev.jarvis.device.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.assistant.speech.OfflineTranscriber
import dev.jarvis.assistant.wakeword.VoskModelStore
import dev.jarvis.core.common.AssistantSession
import dev.jarvis.core.voice.AssistantService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject

/** A valid, local RecognitionService is required for Android's voice-assistant registration. */
@AndroidEntryPoint
class JarvisRecognitionService : RecognitionService() {
    @Inject lateinit var offline: OfflineTranscriber
    @Inject lateinit var session: AssistantSession
    @Inject lateinit var models: VoskModelStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private var activeCallback: Callback? = null
    @Volatile private var partial = ""

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (listening?.isActive == true) { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
        val language = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: Locale.getDefault().toLanguageTag()
        if (!language.substringBefore('-').equals("en", true)) {
            listener.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            return
        }
        activeCallback = listener
        partial = ""
        val previous = listening
        listening = scope.launch {
            try {
                previous?.cancelAndJoin()
                stopService(Intent(this@JarvisRecognitionService, AssistantService::class.java))
                withTimeout(5_000) { session.state.first { !it.running } }
                delay(150)
                val result = offline.recognize(emptyList(), false,
                    onReady = { scope.launch { if (activeCallback === listener) listener.readyForSpeech(Bundle()) } },
                    onPartial = { value -> partial = value; scope.launch {
                        if (activeCallback === listener) listener.partialResults(results(listOf(value)))
                    } }, freeForm = true)
                if (activeCallback === listener) {
                    if (result.alternatives.isEmpty()) listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                    else listener.results(results(result.alternatives))
                }
            } catch (_: TimeoutCancellationException) { if (activeCallback === listener) listener.error(SpeechRecognizer.ERROR_AUDIO) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: SecurityException) { if (activeCallback === listener) listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            catch (_: Exception) { if (activeCallback === listener) listener.error(SpeechRecognizer.ERROR_AUDIO) }
            finally {
                if (activeCallback === listener) activeCallback = null
                withContext(NonCancellable + Dispatchers.IO) { models.release() }
            }
        }
    }

    override fun onStopListening(listener: Callback) {
        if (activeCallback !== listener) return
        activeCallback = null
        val previous = listening
        val lastPartial = partial
        previous?.cancel()
        scope.launch {
            previous?.join()
            if (lastPartial.isBlank()) listener.error(SpeechRecognizer.ERROR_NO_MATCH)
            else listener.results(results(listOf(lastPartial)))
        }
    }
    override fun onCancel(listener: Callback) {
        if (activeCallback === listener) { activeCallback = null; listening?.cancel() }
    }
    override fun onDestroy() { activeCallback = null; scope.cancel(); super.onDestroy() }
    private fun results(values: List<String>) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(values.filter { it.isNotBlank() }.take(5)))
    }
}
