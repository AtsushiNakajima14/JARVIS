package dev.jarvis.assistant.speech

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.parser.CommandLexicon
import dev.jarvis.core.common.AssistantSettings
import dev.jarvis.core.voice.MicrophoneLease
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject

class SpeechFailure(val code: Int) : Exception("Speech recognition error $code")

data class SpeechProviderAvailability(
    val googleAvailable: Boolean,
    val googlePackage: String? = null,
    val googleService: String? = null,
    val googleName: String? = null,
    val onDeviceAvailable: Boolean = false
)

class AndroidSpeechEngine @Inject constructor(@param:ApplicationContext private val context: Context,
    private val offline: OfflineTranscriber) {
    /** Only enabled RecognitionService components from known Google packages are eligible. */
    fun availability(): SpeechProviderAvailability {
        val provider = googleProvider()
        return SpeechProviderAvailability(provider != null, provider?.packageName, provider?.className,
            provider?.let(RecognitionPolicy::providerName), onDeviceAvailable())
    }

    private fun onDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= 31 &&
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    private fun googleProvider(): RecognitionServiceCandidate? {
        val services = runCatching {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE),
                PackageManager.MATCH_DISABLED_COMPONENTS).mapNotNull { resolved ->
                val service = resolved.serviceInfo ?: return@mapNotNull null
                RecognitionServiceCandidate(service.packageName, service.name, service.enabled,
                    service.applicationInfo.enabled, service.exported)
            }
        }.getOrDefault(emptyList())
        return RecognitionPolicy.googleProvider(services)
    }

    suspend fun recognize(settings: AssistantSettings, appLabels: List<String>, confirmation: Boolean = false,
        onReady: (String) -> Unit, onPartial: (String) -> Unit,
        freeForm: Boolean = false): SpeechResult = withContext(Dispatchers.Main.immediate) {
        val language = if (settings.language == "system") Locale.getDefault().toLanguageTag() else settings.language
        val provider = googleProvider()
        val onDevice = onDeviceAvailable()
        val backend = RecognitionPolicy.choose(language, settings.networkSpeech, onDevice, provider != null)
        if (backend == RecognitionBackend.OFFLINE_COMMANDS) return@withContext offline.recognize(appLabels, confirmation, onReady, onPartial, freeForm)

        suspend fun attempt(selected: RecognitionBackend): SpeechResult? = try {
            platformRecognize(language, selected, provider, appLabels, confirmation, onReady, onPartial)
        } catch (failure: SpeechFailure) {
            if (failure.code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) throw failure
            currentCoroutineContext().ensureActive()
            null
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: RuntimeException) {
            // A provider can be disabled/uninstalled after discovery or refuse a bind.
            currentCoroutineContext().ensureActive()
            null
        }

        attempt(backend)?.let { return@withContext it }
        // Provider cancel/destroy are binder operations; allow microphone release before a new owner starts.
        delay(150)
        val fallback = RecognitionPolicy.fallback(backend, onDevice)
        if (fallback == RecognitionBackend.ON_DEVICE) {
            attempt(fallback)?.let { return@withContext it }
            delay(150)
        }
        offline.recognize(appLabels, confirmation, onReady, onPartial, freeForm)
    }

    private suspend fun platformRecognize(language: String, backend: RecognitionBackend,
        provider: RecognitionServiceCandidate?, appLabels: List<String>, confirmation: Boolean,
        onReady: (String) -> Unit, onPartial: (String) -> Unit): SpeechResult = MicrophoneLease.exclusive {
        val onDevice = backend == RecognitionBackend.ON_DEVICE
        val recognizer = if (onDevice && Build.VERSION.SDK_INT >= 31)
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else error("Only on-device recognition is permitted")
        val source = if (onDevice) "Phone - on-device speech"
            else "Google speech - ${RecognitionPolicy.providerName(requireNotNull(provider))}"
        try {
            val recognitionLanguage = installedLanguage(recognizer, language)
            withTimeout(18000) {
                suspendCancellableCoroutine<SpeechResult> { continuation ->
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) { if (continuation.isActive) onReady(source) }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                        override fun onPartialResults(partialResults: Bundle?) {
                            if (continuation.isActive) onPartial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                        }
                        override fun onResults(results: Bundle?) {
                            if (continuation.isActive) continuation.resume(SpeechResult(
                                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() }?.take(5).orEmpty(), source))
                        }
                        override fun onError(error: Int) {
                            if (!continuation.isActive) return
                            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                                continuation.resume(SpeechResult(emptyList(), source))
                            else continuation.resumeWithException(SpeechFailure(error))
                        }
                    })
                    val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguage)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                        // Inventory-derived hints remain on-device. Google receives fixed public command words only.
                        val hints = if (RecognitionPolicy.usesDeviceBias(backend, confirmation))
                            (appLabels.take(150) + CommandLexicon.phrases(appLabels)
                                .filter { !it.startsWith("jarvis ") }.take(150)).distinct()
                        else if (backend == RecognitionBackend.PHONE_SERVICE) RecognitionPolicy.phoneBias(confirmation)
                        else emptyList()
                        if (Build.VERSION.SDK_INT >= 33 && hints.isNotEmpty())
                            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints))
                    }
                    recognizer.startListening(request)
                }
            }
        } finally {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    private suspend fun installedLanguage(recognizer: SpeechRecognizer, requested: String): String {
        if (Build.VERSION.SDK_INT < 33) return requested
        return withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { continuation ->
                try {
                    recognizer.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, requested)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }, context.mainExecutor, object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            if (continuation.isActive) continuation.resume(
                                OfflineLanguagePolicy.installedVariant(requested, support.installedOnDeviceLanguages))
                        }
                        override fun onError(error: Int) {
                            if (continuation.isActive) continuation.resume(requested)
                        }
                    })
                } catch (_: RuntimeException) {
                    if (continuation.isActive) continuation.resume(requested)
                }
            }
        } ?: requested
    }
}
