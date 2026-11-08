package dev.jarvis.assistant.wakeword

import dev.jarvis.core.voice.VoskAudioSource
import kotlinx.coroutines.*
import org.vosk.Recognizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoskWakeWordEngine @Inject constructor(private val models: VoskModelStore, private val audio: VoskAudioSource) : WakeWordEngine {
    override suspend fun awaitWakeWord(): Unit = withContext(Dispatchers.IO) {
        val model = models.get()
        val recognizer = Recognizer(model, 16000f, "[\"jarvis\", \"[unk]\"]")
        try {
            audio.capture(recognizer) { text, _ -> Regex("\\bjarvis\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }
        } finally { recognizer.close() }
        Unit
    }
}

