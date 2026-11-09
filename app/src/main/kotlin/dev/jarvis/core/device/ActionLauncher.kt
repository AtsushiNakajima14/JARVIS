package dev.jarvis.core.device

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.R
import dev.jarvis.core.common.AssistantSession
import dev.jarvis.device.assistant.DefaultAssistant
import javax.inject.Inject

class ActionLauncher @Inject constructor(@param:ApplicationContext private val context: Context, private val session: AssistantSession) {
    suspend fun launch(intent: Intent): String? {
        if (intent.resolveActivity(context.packageManager) == null) return "No application on this device can handle that action."
        if (session.visible) { session.launches.send(intent); return null }
        if (DefaultAssistant.canOpenFromBackground(context)) {
            try {
                // Android binds the user's selected VoiceInteractionService with activity-start privileges.
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return null
            } catch (_: RuntimeException) {
                session.log("Android refused a default-assistant activity launch")
            }
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "Open JARVIS and try again. Notifications are disabled."
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("jarvis_actions", "Actions waiting for you", NotificationManager.IMPORTANCE_HIGH))
        val pending = PendingIntent.getActivity(context, 42, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(42, NotificationCompat.Builder(context, "jarvis_actions").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your action is ready").setContentText("Tap to continue on your device.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build())
        return if (DefaultAssistant.isSelected(context)) "Android needs a tap to finish this action. Open the JARVIS action notification."
            else "Choose JARVIS as your default digital assistant in Permissions to open apps directly while unlocked. For now, tap the JARVIS action notification."
    }
}

