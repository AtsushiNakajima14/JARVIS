package dev.jarvis.assistant.speech

import dev.jarvis.assistant.wakeword.VoskModelStore
import dev.jarvis.core.voice.VoskAudioSource
import dev.jarvis.assistant.parser.CommandLexicon
import kotlinx.coroutines.*
import javax.inject.Inject

class OfflineTranscriber @Inject constructor(private val models: VoskModelStore, private val audio: VoskAudioSource) {
    suspend fun recognize(appLabels: List<String>, confirmation: Boolean, onReady: (String) -> Unit,
        onPartial: (String) -> Unit): SpeechResult = withContext(Dispatchers.IO) {
        models.withModel { model ->
            OfflineCommandDecoder(model, CommandLexicon.phrases(appLabels), appLabels, constrained = !confirmation).use { decoder ->
                audio.recordFrames(15000, onReady = { onReady("Offline English · device commands") }) { samples, count ->
                    val result = decoder.accept(samples, count)
                    if (result != null && result.text.isNotBlank()) result
                    else { onPartial(decoder.partial()); null }
                } ?: decoder.finish()
            }
        }
    }
}

