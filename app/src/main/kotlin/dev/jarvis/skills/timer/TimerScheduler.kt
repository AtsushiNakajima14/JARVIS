package dev.jarvis.skills.timer
import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.MainActivity
import dev.jarvis.R
import dev.jarvis.core.database.*
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimerScheduler @Inject constructor(@param:ApplicationContext private val context: Context, private val dao: JarvisDao) {
    suspend fun schedule(seconds: Long): Boolean {
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val at = System.currentTimeMillis() + seconds * 1000
        dao.remember(UserPreferenceEntity("timer:$id", SecretText(at.toString()), "timer"))
        try { return scheduleAt(id, at) }
        catch (error: Exception) { dao.forget("timer:$id"); throw error }
    }
    private fun scheduleAt(id: Int, at: Long): Boolean {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, id, Intent(context, TimerReceiver::class.java).putExtra("timer_id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val exact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        return exact
    }
    suspend fun restore() {
        dao.preferenceList("timer").forEach { timer ->
            val at = timer.content.text.toLongOrNull() ?: return@forEach
            scheduleAt(timer.id.removePrefix("timer:").toInt(), maxOf(System.currentTimeMillis() + 2000, at))
        }
    }
}
@AndroidEntryPoint
class TimerReceiver : BroadcastReceiver() {
    @Inject lateinit var dao: JarvisDao
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("timer_id", 0)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("jarvis_timers", "Timers", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            setSound(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
        })
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) manager.notify(id, NotificationCompat.Builder(context, "jarvis_timers")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Your timer is finished").setContentText("Time is up.")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open).build())
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { dao.forget("timer:$id") } finally { pending.finish() } }
    }
}

