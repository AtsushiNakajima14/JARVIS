package dev.jarvis.core.device

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.R
import dev.jarvis.core.common.AssistantSession
import javax.inject.Inject

class ActionLauncher @Inject constructor(@param:ApplicationContext private val context: Context, private val session: AssistantSession) {
    suspend fun launch(intent: Intent): String? {
        if (intent.resolveActivity(context.packageManager) == null) return "No application on this device can handle that action."
        if (session.visible) { session.launches.send(intent); return null }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return "Open JARVIS and try again. Notifications are disabled."
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("jarvis_actions", "Actions waiting for you", NotificationManager.IMPORTANCE_HIGH))
        val pending = PendingIntent.getActivity(context, 42, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(42, NotificationCompat.Builder(context, "jarvis_actions").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your action is ready").setContentText("Tap to continue on your device.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build())
        return "Your action is ready. Tap the JARVIS action notification to continue."
    }
}

