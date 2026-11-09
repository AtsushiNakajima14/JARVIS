package dev.jarvis.device.assistant

import android.service.voice.VoiceInteractionService

/** Lightweight system-bound entry point. Binding alone never starts recording or reads a screen. */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    private var destroyed = false
    override fun onReady() {
        super.onReady()
        if (!destroyed) isReady = true
    }
    override fun onShutdown() {
        isReady = false
        super.onShutdown()
    }
    override fun onDestroy() {
        destroyed = true
        isReady = false
        super.onDestroy()
    }

    companion object {
        @Volatile var isReady: Boolean = false
            private set
    }
}
