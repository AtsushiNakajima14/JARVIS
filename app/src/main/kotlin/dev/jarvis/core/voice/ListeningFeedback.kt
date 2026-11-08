package dev.jarvis.core.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import javax.inject.Inject

class ListeningFeedback @Inject constructor() {
    /** A short non-speech cue after the recorder/provider is actually ready. */
    fun ready() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 35)
            try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80) }
            catch (error: Exception) { tone.release(); return }
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 200)
        }
    }
}
