package dev.jarvis.device.scanner

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.core.database.*
import dev.jarvis.core.common.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Only records package + timestamp; message bodies and security codes are never read. */
@AndroidEntryPoint
class NotificationIndexer : NotificationListenerService() {
    @Inject lateinit var dao: JarvisDao
    @Inject lateinit var settings: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        scope.launch {
            if (!settings.settings.first().notifications) return@launch
            runCatching {
                dao.remember(UserPreferenceEntity("notification:${sbn.packageName}", SecretText("${sbn.packageName} · ${java.util.Date(sbn.postTime)}"), "notification"))
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

