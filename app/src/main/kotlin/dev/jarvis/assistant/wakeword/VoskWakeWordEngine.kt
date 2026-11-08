package dev.jarvis.assistant.wakeword

import dev.jarvis.core.voice.VoskAudioSource
import android.os.SystemClock
import dev.jarvis.assistant.parser.*
import dev.jarvis.assistant.speech.*
import kotlinx.coroutines.*
import org.vosk.Recognizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoskWakeWordEngine @Inject constructor(private val models: VoskModelStore, private val audio: VoskAudioSource) : WakeWordEngine {
    override suspend fun awaitWakeWord(appLabels: List<String>, onDetected: () -> Unit,
        onPartial: (String) -> Unit, onAudioActivity: () -> Unit): WakeActivation = withContext(Dispatchers.IO) {
        models.withModel { model ->
            val wake = Recognizer(model, 16000f, "[\"jarvis\", \"[unk]\"]")
            val preRoll = TransientPcmBuffer(8)
            val stableWake = StableWakeDetector()
            var command: OfflineCommandDecoder? = null
            var activatedAt = 0L
            val phrases = CommandLexicon.phrases(appLabels)
            fun activation(result: SpeechResult): WakeActivation {
                if (result.wakeOnly) return WakeActivation()
                val selected = SpeechSelection().choose(result.alternatives)
                return if (stripAssistantPrefix(selected).isBlank()) WakeActivation()
                else WakeActivation(result.alternatives, result.source)
            }
            try {
                audio.recordFrames<WakeActivation> { samples, count ->
                    // Energy activity precedes wake confirmation, so proactive speech
                    // can wait for quiet without changing recognition or audio ownership.
                    if (SpeechActivity.isActive(samples, count)) onAudioActivity()
                    val decoder = command
                    if (decoder == null) {
                        preRoll.add(samples, count)
                        val complete = wake.acceptWaveForm(samples, count)
                        val text = if (complete) VoskResultReader.read(wake.result).alternatives.firstOrNull().orEmpty()
                            else VoskResultReader.partial(wake.partialResult)
                        if (stableWake.observe(text, complete)) {
                            activatedAt = SystemClock.elapsedRealtime()
                            onDetected()
                            val next = OfflineCommandDecoder(model, phrases, appLabels, fromWake = true)
                            command = next
                            val replayed = WakeReplay.replay(preRoll, next::accept)
                            preRoll.clear()
                            replayed?.let(::activation)
                        } else null
                    } else {
                        val result = decoder.accept(samples, count)
                        if (result == null) onPartial(decoder.partial())
                        when {
                            result != null -> activation(result)
                            SystemClock.elapsedRealtime() - activatedAt >= 18000 -> activation(decoder.finish())
                            else -> null
                        }
                    }
                } ?: WakeActivation()
            } finally { preRoll.clear(); try { command?.close() } finally { wake.close() } }
        }
    }
}

