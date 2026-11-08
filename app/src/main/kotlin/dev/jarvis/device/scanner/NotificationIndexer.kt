package dev.jarvis.device.scanner

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import dev.jarvis.assistant.notifications.NotificationRepository
import dev.jarvis.core.common.AssistantSession
import dev.jarvis.core.common.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Android grants access explicitly; preview capture and app-only alerts have separate opt-ins. */
@AndroidEntryPoint
class NotificationIndexer : NotificationListenerService() {
    @Inject lateinit var notifications: NotificationRepository
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var session: AssistantSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Event>(64, BufferOverflow.DROP_OLDEST)
    private val snapshotSource: () -> Array<out StatusBarNotification> = { activeNotifications.orEmpty() }
    private var listenerConnected = false

    private sealed interface Event {
        data class Posted(val notification: StatusBarNotification, val revision: Long) : Event
        data class Removed(val key: String, val revision: Long) : Event
        data object Refresh : Event
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (event in events) {
                try {
                    when (event) {
                        is Event.Posted -> notifications.posted(event.notification, event.revision)
                        is Event.Removed -> notifications.removed(event.key, event.revision)
                        Event.Refresh -> notifications.refresh()
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { session.log("Notification preview update failed: ${error.javaClass.simpleName}") }
            }
        }
        scope.launch {
            settings.settings.map { it.notificationReading }.distinctUntilChanged().collect { reading ->
                // Opting out removes previews even when no new notifications arrive.
                if (!reading) notifications.clear()
                else if (listenerConnected) events.trySend(Event.Refresh)
            }
        }
    }

    override fun onListenerConnected() {
        listenerConnected = true
        notifications.connect(snapshotSource)
        events.trySend(Event.Refresh) // Current Android notifications, with no old-alert replay.
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        notifications.disconnect(snapshotSource)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        events.trySend(Event.Posted(sbn, notifications.currentRevision()))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        events.trySend(Event.Removed(sbn.key, notifications.currentRevision()))
    }

    override fun onDestroy() {
        notifications.disconnect(snapshotSource)
        listenerConnected = false
        events.close()
        scope.cancel()
        super.onDestroy()
    }
}
