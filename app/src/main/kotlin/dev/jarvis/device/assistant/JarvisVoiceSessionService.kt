package dev.jarvis.device.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dev.jarvis.MainActivity

class JarvisVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = JarvisVoiceSession(this)
}

/** The system assistant gesture opens JARVIS visibly; microphone permission stays with MainActivity. */
class JarvisVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
        // This companion has no screen-inspection feature. Do not request assist structure or screenshots.
        setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            startAssistantActivity(Intent(context, MainActivity::class.java)
                .setAction(DefaultAssistant.ACTION_ASSIST_LISTEN)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: RuntimeException) {
            // An OEM refusing assistant UI must not leave an invisible active voice session.
        } finally { finish() }
    }
}
