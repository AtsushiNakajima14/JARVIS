package dev.jarvis

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.jarvis.device.applications.ApplicationIndexSync
import dev.jarvis.assistant.abilities.ReminderRepository
import kotlinx.coroutines.*
import javax.inject.Inject

@HiltAndroidApp
class JarvisApplication : Application() {
    @Inject lateinit var applicationIndexSync: ApplicationIndexSync
    @Inject lateinit var reminders: ReminderRepository
    override fun onCreate() {
        super.onCreate()
        applicationIndexSync.start(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(20_000) { reminders.restore() } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Reconciled again on the next foreground launch or boot. */ }
        }
    }
}

