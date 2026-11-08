package dev.jarvis.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.core.common.AssistantSettings
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject

class SpeechFailure(val code: Int) : Exception("Speech recognition error $code")

class AndroidSpeechEngine @Inject constructor(@param:ApplicationContext private val context: Context, private val offline: OfflineTranscriber) {
    suspend fun recognize(settings: AssistantSettings, onPartial: (String) -> Unit): String = withContext(Dispatchers.Main.immediate) {
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!onDevice && (!settings.networkSpeech || !SpeechRecognizer.isRecognitionAvailable(context))) return@withContext offline.recognize(onPartial)
        try {
            platformRecognize(settings, onDevice, onPartial)
        } catch (failure: SpeechFailure) {
            if (failure.code !in setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_SERVER_DISCONNECTED)) throw failure
            if (onDevice && settings.networkSpeech && SpeechRecognizer.isRecognitionAvailable(context)) platformRecognize(settings, false, onPartial)
            else offline.recognize(onPartial)
        }
    }
    private suspend fun platformRecognize(settings: AssistantSettings, onDevice: Boolean, onPartial: (String) -> Unit): String {
        val recognizer = if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        return try {
            withTimeout(18000) {
                suspendCancellableCoroutine<String> { continuation ->
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                        override fun onPartialResults(partialResults: Bundle?) { onPartial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()) }
                        override fun onResults(results: Bundle?) {
                            if (continuation.isActive) continuation.resume(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                        }
                        override fun onError(error: Int) { if (continuation.isActive) continuation.resumeWithException(SpeechFailure(error)) }
                    })
                    recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (settings.language == "system") Locale.getDefault().toLanguageTag() else settings.language)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !settings.networkSpeech)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    })
                }
            }
        } finally { recognizer.cancel(); recognizer.destroy() }
    }
}

