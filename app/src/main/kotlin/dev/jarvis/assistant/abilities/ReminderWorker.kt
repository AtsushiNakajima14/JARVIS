package dev.jarvis.assistant.abilities

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderWorkerDependencies { fun reminders(): ReminderRepository }

/** Default WorkManager construction plus Hilt entry point; no network constraints or payloads. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("reminder_id") ?: return Result.failure()
        return try {
            val repository = EntryPointAccessors.fromApplication(applicationContext, ReminderWorkerDependencies::class.java).reminders()
            if (repository.deliver(id)) Result.success() else Result.retry()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
