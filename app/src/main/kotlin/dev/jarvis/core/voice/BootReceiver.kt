package dev.jarvis.core.voice

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.MainActivity
import dev.jarvis.R
import dev.jarvis.skills.timer.TimerScheduler
import kotlinx.coroutines.*
import javax.inject.Inject

/** Modern Android forbids starting a microphone FGS at boot; offer a visible resume action. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var timers: TimerScheduler
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { timers.restore() } finally { pending.finish() } }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("jarvis_service", "Assistant status", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(11, NotificationCompat.Builder(context, "jarvis_service").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS is ready").setContentText("Open JARVIS to resume listening after restart.").setContentIntent(open).setAutoCancel(true).build())
    }
}
