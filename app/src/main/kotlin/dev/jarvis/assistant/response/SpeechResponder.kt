package dev.jarvis.assistant.response

import android.content.Context
import android.media.*
import android.speech.tts.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.core.common.AssistantSettings
import kotlinx.coroutines.*
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechResponder @Inject constructor(@param:ApplicationContext private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val utterances = mutableMapOf<String, CompletableDeferred<Unit>>()
    private suspend fun initialize(): TextToSpeech? {
        if (tts == null) {
            val initialized = CompletableDeferred<Boolean>(); ready = initialized
            tts = TextToSpeech(context) { status -> initialized.complete(status == TextToSpeech.SUCCESS) }.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { synchronized(utterances) { utterances.remove(id)?.complete(Unit) } }
                    @Deprecated("Legacy callback") override fun onError(id: String?) { synchronized(utterances) { utterances.remove(id)?.completeExceptionally(IllegalStateException("Speech output failed")) } }
                })
            }
        }
        return if (withTimeoutOrNull(7000) { ready?.await() } == true) tts else null
    }
    suspend fun voices(): List<Pair<String, String>> = withContext(Dispatchers.Main.immediate) {
        initialize()?.voices?.filter { !it.isNetworkConnectionRequired }?.sortedBy { it.locale.displayName }?.map { it.name to "${it.locale.displayName} · ${it.name}" }.orEmpty()
    }
    suspend fun speak(text: String, settings: AssistantSettings) = withContext(Dispatchers.Main.immediate) {
        val engine = initialize() ?: throw IllegalStateException("Install an offline text-to-speech voice in Android settings.")
        val locale = if (settings.language == "system") Locale.getDefault() else Locale.forLanguageTag(settings.language)
        // Google's recognition preference does not authorize uploading local AI replies for synthesis.
        val voices = engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
        val voice = voices.firstOrNull { it.name == settings.voice } ?: voices.firstOrNull { it.locale == locale }
            ?: voices.firstOrNull { it.locale.language == locale.language } ?: voices.firstOrNull { it.locale.language == "en" }
            ?: throw IllegalStateException("Install an offline text-to-speech voice in Android settings.")
        engine.voice = voice; engine.setSpeechRate(settings.speed); engine.setPitch(settings.pitch)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        synchronized(utterances) { utterances[id] = done }
        val audio = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build()).setOnAudioFocusChangeListener {}.build()
        audio.requestAudioFocus(focus)
        try {
            if (engine.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) error("Speech output failed")
            withTimeout(45000) { done.await() }
        } finally { engine.stop(); synchronized(utterances) { utterances.remove(id) }; audio.abandonAudioFocusRequest(focus) }
    }
    fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null; ready = null }
}
