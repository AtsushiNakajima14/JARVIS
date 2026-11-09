package dev.jarvis.assistant.notifications

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.core.common.SettingsStore
import dev.jarvis.core.database.JarvisDao
import dev.jarvis.core.database.SecretText
import dev.jarvis.core.database.UserPreferenceEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(@param:ApplicationContext private val context: Context,
    private val dao: JarvisDao, private val settings: SettingsStore) {
    private val mutex = Mutex()
    private val ledger = NotificationLedger()
    private val revision = AtomicLong()
    private val mutableAnnouncements = MutableSharedFlow<NotificationAnnouncement>(extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val announcements = mutableAnnouncements.asSharedFlow()
    private val mutableInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val invalidations = mutableInvalidations.asSharedFlow()
    private var previousReading = false
    @Volatile private var snapshotSource: (() -> Array<out StatusBarNotification>)? = null
    @Volatile private var connected = false

    fun currentRevision(): Long = revision.get()
    fun isCurrent(announcement: NotificationAnnouncement): Boolean = revision.get() == announcement.revision &&
        announcement.postedAt >= System.currentTimeMillis() - 120_000

    /** The listener owns this reference only while Android has connected it. */
    fun connect(source: () -> Array<out StatusBarNotification>) { snapshotSource = source; connected = true }
    fun disconnect(source: () -> Array<out StatusBarNotification>) {
        if (snapshotSource === source) {
            connected = false
            snapshotSource = null
            if (!hasAccess()) {
                // Android may destroy the listener immediately after revocation.
                // This finite cleanup is independent of that service's cancelled scope.
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { clear() }
                }
            }
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val expected = currentRevision()
        val config = settings.settings.first()
        if (!config.notificationReading || !hasAccess()) return@withContext
        val source = snapshotSource ?: return@withContext
        val notifications = runCatching { source().sortedByDescending { it.postTime }.take(NotificationPolicy.MAX_RECORDS) }.getOrNull() ?: return@withContext
        val records = notifications.mapNotNull(::extract)
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (source !== snapshotSource || expected != currentRevision() || !settings.settings.first().notificationReading || !hasAccess()) return@withLock
            val now = System.currentTimeMillis()
            if (!previousReading) ledger.allowExisting() // A new opt-in can import current Android previews.
            previousReading = true
            val changed = ledger.replaceActive(records, now)
            for (record in changed) if (record.postedAt > ledger.clearedAt) persist(record, now)
            loadRecent(now) // Also prune expired history when no new notification arrived.
        }
    }

    suspend fun posted(sbn: StatusBarNotification, expectedRevision: Long = currentRevision()) = withContext(Dispatchers.IO) {
        if (sbn.packageName == context.packageName || isExcluded(sbn)) return@withContext
        val config = settings.settings.first()
        if (!hasAccess() || expectedRevision != currentRevision()) return@withContext
        if (!config.notificationReading && !config.notificationAnnouncements && !config.notifications) return@withContext
        val app = appLabel(sbn.packageName)
        // Do not access notification extras at all before the reading opt-in.
        val captured = if (config.notificationReading) extract(sbn) else null
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (expectedRevision != currentRevision() || !hasAccess()) return@withLock
            val current = settings.settings.first()
            val now = System.currentTimeMillis()
            if (current.notificationReading && !previousReading) ledger.allowExisting()
            previousReading = current.notificationReading
            val entry = if (current.notificationReading) captured else null
            val metadata = NotificationRecord(hash(sbn.key), sbn.packageName, app, postedAt = sbn.postTime)
            val change = ledger.update(entry ?: metadata, current.notificationAnnouncements, now)
            if (current.notificationReading && entry != null && change.changed) persist(entry, now)
            if (current.notifications) dao.remember(UserPreferenceEntity("notification:${sbn.packageName}",
                SecretText("${sbn.packageName} · ${java.util.Date(sbn.postTime)}"), "notification"))
            if (change.announce && expectedRevision == currentRevision())
                mutableAnnouncements.tryEmit(NotificationAnnouncement(app, sbn.postTime, expectedRevision))
        }
    }

    suspend fun removed(key: String, expectedRevision: Long = currentRevision()) {
        mutex.withLock { if (expectedRevision == currentRevision()) ledger.remove(hash(key)) }
    }

    suspend fun answer(recentChats: Boolean, appFilter: String = ""): String = withContext(Dispatchers.IO) {
        val expected = currentRevision()
        val config = settings.settings.first()
        if (!hasAccess()) return@withContext "Enable Notification access for JARVIS in Permissions first. I can only read previews that Android shares."
        if (!config.notificationReading) return@withContext "Enable Read notification previews in Settings first. Notification access alone does not let JARVIS save or read message previews."
        if (locked()) return@withContext "Unlock your phone first. I do not read private notifications or recent chat names while it is locked."
        refresh()
        val result = mutex.withLock {
            val now = System.currentTimeMillis()
            if (recentChats) {
                val history = loadRecent(now)
                NotificationPolicy.recentChatsAnswer(history, appFilter)
            } else if (!connected) "Android has not connected the notification listener yet. Check Notification access and reopen JARVIS."
            else NotificationPolicy.notificationAnswer(ledger.snapshot(now), appFilter)
        }
        when {
            expected != currentRevision() -> "Notification data was cleared. Please ask again."
            !settings.settings.first().notificationReading || !hasAccess() -> "Notification reading is off."
            locked() -> "Unlock your phone first to hear private notifications."
            else -> result
        }
    }

    /** A device briefing needs a count, not the contents of private message previews. */
    suspend fun summary(): String = withContext(Dispatchers.IO) {
        val expected = currentRevision()
        if (!hasAccess() || !settings.settings.first().notificationReading)
            return@withContext "Notification awareness is off. You can enable it in Settings."
        if (locked()) return@withContext "Unlock your phone to check notifications."
        refresh()
        val result = mutex.withLock {
            if (!connected) "Android has not connected notification access yet."
            else {
                val count = ledger.snapshot(System.currentTimeMillis()).size
                if (count == 0) "No active notifications are visible to me."
                else "I can see $count active ${if (count == 1) "notification" else "notifications"}. Ask me to read them when you're ready."
            }
        }
        when {
            locked() -> "Unlock your phone to check notifications."
            expected != currentRevision() -> "Notification data changed. Ask me to check again."
            !hasAccess() || !settings.settings.first().notificationReading -> "Notification awareness is off."
            else -> result
        }
    }

    /** Invalidate in-flight captures before waiting for any encrypted database operation. */
    suspend fun clear() {
        revision.incrementAndGet()
        mutableInvalidations.tryEmit(Unit)
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                ledger.clear(System.currentTimeMillis())
                previousReading = settings.settings.first().notificationReading
                dao.clearKind(KIND)
            }
        }
    }

    suspend fun forget(id: String) {
        revision.incrementAndGet()
        mutableInvalidations.tryEmit(Unit)
        mutex.withLock {
            ledger.clear(System.currentTimeMillis())
            previousReading = settings.settings.first().notificationReading
            dao.forget(id)
        }
    }

    /** The UI may format a captured row without showing internal JSON or bypassing consent. */
    suspend fun savedPreviewLabel(row: UserPreferenceEntity): String? {
        val expected = currentRevision()
        if (row.kind != KIND || !hasAccess() || locked() || !settings.settings.first().notificationReading) return null
        val now = System.currentTimeMillis()
        if (row.createdAt !in (now - NotificationPolicy.RETENTION_MS)..(now + 60_000)) return null
        val record = decode(row.content.text) ?: return null
        if (record.contentHidden || locked() || !hasAccess() || expected != currentRevision()) return null
        return record.appLabel + record.sender.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() +
            ": " + record.preview.ifBlank { record.title }.take(220)
    }

    private fun hasAccess() = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun isExcluded(sbn: StatusBarNotification) = sbn.isOngoing ||
        sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
    private fun appLabel(packageName: String): String = runCatching {
        val pm = context.packageManager
        NotificationPolicy.clean(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)), 80)
    }.getOrDefault(packageName.substringAfterLast('.').take(80)).ifBlank { "an app" }

    private fun extract(sbn: StatusBarNotification): NotificationRecord? = runCatching {
        if (sbn.packageName == context.packageName || isExcluded(sbn)) return@runCatching null
        val notification = sbn.notification
        val label = appLabel(sbn.packageName)
        val base = NotificationRecord(hash(sbn.key), sbn.packageName, label, postedAt = sbn.postTime)
        // Explicit secret visibility or security apps never expose even a title.
        if (notification.visibility == Notification.VISIBILITY_SECRET || NotificationPolicy.hidesContent(sbn.packageName, label))
            return@runCatching base.copy(contentHidden = true)
        val extras = notification.extras
        val title = NotificationPolicy.clean(extras.getCharSequence(Notification.EXTRA_TITLE), 160)
        if (NotificationPolicy.hidesContent(sbn.packageName, title)) return@runCatching base.copy(contentHidden = true)
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)?.messages.orEmpty()
        val incoming = messages.lastOrNull { message ->
            !senderOf(message).isNullOrBlank()
        }
        val styleMessaging = messages.isNotEmpty()
        val messaging = incoming != null || (!styleMessaging && notification.category == Notification.CATEGORY_MESSAGE)
        val sender = if (incoming != null) {
            NotificationPolicy.clean(senderOf(incoming), 80)
        } else if (messaging && !title.equals(label, true)) title else ""
        val body = NotificationPolicy.clean(incoming?.text ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.takeLast(3)?.joinToString(" "), 420)
        // Raw body text exists only transiently for this guard; rejected text is never retained.
        if (NotificationPolicy.hidesContent(sbn.packageName, title, sender, body)) return@runCatching base.copy(contentHidden = true)
        val messageTime = incoming?.timestamp?.takeIf { it > 0 && it <= System.currentTimeMillis() + 60_000 } ?: sbn.postTime
        base.copy(title = title, preview = body, sender = sender.takeIf(NotificationPolicy::validSender).orEmpty(), messaging = messaging,
            postedAt = messageTime)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun senderOf(message: NotificationCompat.MessagingStyle.Message): CharSequence? = message.person?.name ?: message.sender

    private suspend fun persist(record: NotificationRecord, now: Long) {
        if (!record.contentHidden && (record.preview.isNotBlank() || record.title.isNotBlank())) {
            val id = "notification_message:" + hash(record.key + "|" + record.sender + "|" + record.preview + "|" + record.title)
            dao.remember(UserPreferenceEntity(id, SecretText(encode(record)), KIND, record.postedAt))
        }
        val history = dao.preferenceList(KIND)
        val retained = history.filter { it.createdAt in (now - NotificationPolicy.RETENTION_MS)..(now + 60_000) }
            .sortedByDescending { it.createdAt }.take(NotificationPolicy.MAX_RECORDS).map { it.id }.toSet()
        history.filter { it.id !in retained }.forEach { dao.forget(it.id) }
    }

    private suspend fun loadRecent(now: Long): List<NotificationRecord> {
        val rows = dao.preferenceList(KIND)
        val retained = rows.filter { it.createdAt in (now - NotificationPolicy.RETENTION_MS)..(now + 60_000) }
            .sortedByDescending { it.createdAt }.take(NotificationPolicy.MAX_RECORDS)
        val ids = retained.map { it.id }.toSet()
        rows.filter { it.id !in ids }.forEach { dao.forget(it.id) }
        return NotificationPolicy.recent(retained.mapNotNull { decode(it.content.text) }, now)
    }

    private fun encode(record: NotificationRecord): String = JSONObject().put("key", record.key)
        .put("package", record.packageName).put("app", record.appLabel).put("title", record.title)
        .put("preview", record.preview).put("sender", record.sender).put("at", record.postedAt)
        .put("messaging", record.messaging).put("hidden", record.contentHidden).toString()

    private fun decode(text: String): NotificationRecord? = runCatching {
        val json = JSONObject(text)
        val record = NotificationRecord(json.getString("key"), json.getString("package"), json.getString("app"),
            json.optString("title"), json.optString("preview"), json.optString("sender"), json.getLong("at"),
            json.optBoolean("messaging"), json.optBoolean("hidden"))
        if (NotificationPolicy.hidesContent(record.packageName, record.title, record.preview, record.sender)) null else record
    }.getOrNull()

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object { const val KIND = "notification_message" }
}
