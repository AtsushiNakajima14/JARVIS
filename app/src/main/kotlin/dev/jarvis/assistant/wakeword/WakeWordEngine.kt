package dev.jarvis.assistant.wakeword

/** A single activation, cancellable without retaining the microphone. */
interface WakeWordEngine {
    suspend fun awaitWakeWord()
}

