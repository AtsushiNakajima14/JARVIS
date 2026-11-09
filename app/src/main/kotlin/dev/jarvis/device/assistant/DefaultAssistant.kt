package dev.jarvis.device.assistant

import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService

/** Android owns role selection and the resulting system binding; JARVIS never writes secure settings. */
object DefaultAssistant {
    fun isSelected(context: Context): Boolean = runCatching {
        VoiceInteractionService.isActiveService(context, ComponentName(context, JarvisVoiceInteractionService::class.java))
    }.getOrDefault(false)

    fun canOpenFromBackground(context: Context): Boolean = isSelected(context) &&
        JarvisVoiceInteractionService.isReady && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun requestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT))
                return roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        }
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }

    const val ACTION_ASSIST_LISTEN = "dev.jarvis.ASSIST_LISTEN"
}
