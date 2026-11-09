package dev.jarvis.assistant.abilities

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.MainActivity
import dev.jarvis.R
import dev.jarvis.assistant.parser.normalize
import dev.jarvis.core.database.JarvisDao
import dev.jarvis.core.database.SecretText
import dev.jarvis.core.database.UserPreferenceEntity
import dev.jarvis.core.security.PrivateCommandPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject
import javax.inject.Singleton

data class LocalReminder(val id: String, val title: String, val dueAt: Long,
    val completed: Boolean = false, val notified: Boolean = false)

/** WorkManager stores only an opaque ID. Private task titles remain encrypted in Room. */
@Singleton
class ReminderRepository @Inject constructor(@param:ApplicationContext private val context: Context,
    private val dao: JarvisDao) {
    private val mutation = Mutex()
    val reminders = dao.preferences().map { rows -> rows.filter { it.kind == KIND }.mapNotNull(::decode).sortedBy { it.dueAt } }

    suspend fun list(): List<LocalReminder> = dao.preferenceList(KIND).mapNotNull(::decode).sortedBy { it.dueAt }

    suspend fun create(title: String, dueAt: Long): LocalReminder = mutation.withLock {
        require(title.isNotBlank() && title.length <= 240) { "Tell me a shorter task name." }
        require(!PrivateCommandPolicy.isCredentialRequest("remember $title")) { "Credentials cannot be saved in reminders." }
        require(ReminderPolicy.validDueTime(dueAt, System.currentTimeMillis())) { "Choose a future time within the next year." }
        require(list().size < 100) { "Your task list is full. Delete a finished reminder first." }
        val item = LocalReminder("reminder:${UUID.randomUUID()}", title.trim(), dueAt)
        // Await durable work insertion; if insertion fails, do not claim it was scheduled.
        withContext(NonCancellable + Dispatchers.IO) {
            save(item)
            try { schedule(item) }
            catch (error: Exception) { dao.forget(item.id); throw error }
        }
        item
    }

    suspend fun complete(query: String): String = mutation.withLock {
        val matches = ReminderPolicy.matches(query, list().filterNot { it.completed })
        when (matches.size) {
            0 -> "I couldn't identify an unfinished task with that name. Tell me its title or choose it in your task list."
            1 -> { finish(matches.single()); "I've marked that task as done." }
            else -> "That matches ${matches.size} unfinished tasks. Tell me the full title or choose one in your task list."
        }
    }

    suspend fun completeId(id: String): Boolean = mutation.withLock {
        val item = list().singleOrNull { it.id == id && !it.completed } ?: return@withLock false
        finish(item)
        true
    }

    private suspend fun finish(item: LocalReminder) = withContext(NonCancellable + Dispatchers.IO) {
        save(item.copy(completed = true))
        awaitPersisted(WorkManager.getInstance(context).cancelUniqueWork(workName(item.id)))
        context.getSystemService(NotificationManager::class.java).cancel(item.id, 1)
    }

    suspend fun forget(id: String) = mutation.withLock {
        if (list().none { it.id == id }) return@withLock
        withContext(NonCancellable + Dispatchers.IO) {
            dao.forget(id)
            awaitPersisted(WorkManager.getInstance(context).cancelUniqueWork(workName(id)))
            context.getSystemService(NotificationManager::class.java).cancel(id, 1)
        }
    }

    suspend fun clear() = mutation.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            val old = list()
            dao.clearKind(KIND)
            awaitPersisted(WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG))
            val notifications = context.getSystemService(NotificationManager::class.java)
            old.forEach { notifications.cancel(it.id, 1) }
        }
    }

    /** Repairs a process-death gap between Room persistence and WorkManager insertion. */
    suspend fun restore() = mutation.withLock {
        withContext(Dispatchers.IO) {
            list().filter { !it.completed && !it.notified }.forEach { schedule(it, ExistingWorkPolicy.KEEP) }
        }
    }

    suspend fun todaySummary(): String {
        val tasks = list().filterNot { it.completed }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayTasks = tasks.filter { Instant.ofEpochMilli(it.dueAt).atZone(zone).toLocalDate() <= today }
        return when {
            tasks.isEmpty() -> "You have no unfinished tasks."
            todayTasks.isEmpty() -> "You have ${tasks.size} unfinished tasks, with none due today."
            else -> "You have ${todayTasks.size} unfinished tasks due today or earlier. " +
                todayTasks.take(3).joinToString("; ") { it.title.take(100) } + "."
        }
    }

    /** Returns false only if a wall-clock change means this work is still early. */
    suspend fun deliver(id: String, now: Long = System.currentTimeMillis()): Boolean = mutation.withLock {
        val item = list().singleOrNull { it.id == id } ?: return@withLock true
        if (item.completed || item.notified) return@withLock true
        if (item.dueAt > now) return@withLock false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return@withLock true
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, 31, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Deliberately generic even while unlocked: later lockscreen transitions cannot expose titles.
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Your reminder is due")
            .setContentText("Open JARVIS to see your saved task.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true).setContentIntent(open).build()
        try { manager.notify(item.id, 1, notification) }
        catch (_: SecurityException) { return@withLock true }
        save(item.copy(notified = true))
        true
    }

    private suspend fun schedule(item: LocalReminder, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(workDataOf("reminder_id" to item.id))
            .setInitialDelay(maxOf(0, item.dueAt - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .addTag(WORK_TAG).build()
        awaitPersisted(WorkManager.getInstance(context).enqueueUniqueWork(workName(item.id), policy, request))
    }

    private suspend fun awaitPersisted(operation: Operation) = suspendCancellableCoroutine<Unit> { continuation ->
        val result = operation.result
        result.addListener({
            if (continuation.isActive) {
                try { result.get(); continuation.resume(Unit) }
                catch (error: Exception) { continuation.resumeWithException(error) }
            }
        }, Executor { it.run() })
    }

    private suspend fun save(item: LocalReminder) {
        val value = JSONObject().put("title", item.title).put("dueAt", item.dueAt)
            .put("completed", item.completed).put("notified", item.notified).toString()
        dao.remember(UserPreferenceEntity(item.id, SecretText(value), KIND))
    }

    companion object {
        const val KIND = "companion_reminder"
        const val WORK_TAG = "jarvis_companion_reminders"
        private const val CHANNEL = "jarvis_reminders"
        fun workName(id: String) = "jarvis.reminder.$id"
        fun decode(row: UserPreferenceEntity): LocalReminder? = runCatching {
            if (row.kind != KIND) return null
            val value = JSONObject(row.content.text)
            LocalReminder(row.id, value.getString("title").take(240), value.getLong("dueAt"),
                value.optBoolean("completed"), value.optBoolean("notified"))
        }.getOrNull()
    }
}

object ReminderPolicy {
    fun validDueTime(dueAt: Long, now: Long): Boolean = now >= 0 && dueAt > now && dueAt - now <= 366L * 24 * 60 * 60 * 1000
    fun matches(query: String, tasks: List<LocalReminder>): List<LocalReminder> {
        val value = normalize(query).take(240)
        if (value.isBlank() || value in setOf("it", "this", "that", "all", "everything")) return emptyList()
        val exact = tasks.filter { normalize(it.title) == value }
        if (exact.isNotEmpty()) return exact
        val tokens = value.split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in setOf("the", "task", "reminder", "about", "done") }
        if (tokens.isEmpty()) return emptyList()
        return tasks.filter { task -> val words = normalize(task.title).split(Regex("[^a-z0-9]+")).toSet(); tokens.all { it in words } }
    }
}
