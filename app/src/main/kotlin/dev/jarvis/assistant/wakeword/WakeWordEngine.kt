package dev.jarvis.assistant.wakeword

/** A single activation, cancellable without retaining the microphone. */
data class WakeActivation(val alternatives: List<String> = emptyList(), val source: String = "Offline English · device commands")
interface WakeWordEngine {
    suspend fun awaitWakeWord(appLabels: List<String>, onDetected: () -> Unit, onPartial: (String) -> Unit,
        onAudioActivity: () -> Unit = {}, freeForm: Boolean = false): WakeActivation
}

