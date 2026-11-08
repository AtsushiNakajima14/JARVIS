package dev.jarvis.assistant.speech

import dev.jarvis.assistant.wakeword.VoskModelStore
import dev.jarvis.core.voice.VoskAudioSource
import kotlinx.coroutines.*
import org.vosk.Recognizer
import javax.inject.Inject

class OfflineTranscriber @Inject constructor(private val models: VoskModelStore, private val audio: VoskAudioSource) {
    suspend fun recognize(onPartial: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val recognizer = Recognizer(models.get(), 16000f)
        try {
            audio.capture(recognizer, 15000) { text, complete ->
                onPartial(text)
                complete && text.isNotBlank()
            }
        } finally { recognizer.close() }
    }
}

