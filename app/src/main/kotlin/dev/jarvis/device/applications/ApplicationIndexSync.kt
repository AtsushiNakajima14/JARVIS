package dev.jarvis.device.applications

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.core.common.AssistantSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Tracks public launcher metadata automatically for this application's process lifetime. */
@Singleton
class ApplicationIndexSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val apps: ApplicationRepository,
    private val session: AssistantSession
) : AutoCloseable {
    private var application: Application? = null
    private var scope: CoroutineScope? = null
    private var scheduler: ApplicationIndexScheduler? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action !in PACKAGE_ACTIONS || intent?.data?.scheme != "package") return
            scheduler?.request()
        }
    }
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { scheduler?.request() }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** Call once from Application.onCreate; repeated calls do not add duplicate receivers. */
    @Synchronized fun start(application: Application) {
        if (this.application != null) return
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val worker = ApplicationIndexScheduler(workerScope) {
            try {
                apps.scan()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { session.log("Application index refresh failed: ${error.javaClass.simpleName}") }
        }
        scope = workerScope
        scheduler = worker
        this.application = application
        val filter = IntentFilter().apply {
            PACKAGE_ACTIONS.forEach(::addAction)
            addDataScheme("package")
        }
        try {
            // Package changes are protected system broadcasts; no other app needs access.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            application.registerActivityLifecycleCallbacks(callbacks)
            worker.request()
        } catch (error: Exception) {
            close()
            session.log("Application index observer unavailable: ${error.javaClass.simpleName}")
        }
    }

    @Synchronized override fun close() {
        val app = application ?: return
        application = null
        runCatching { context.unregisterReceiver(receiver) }
        app.unregisterActivityLifecycleCallbacks(callbacks)
        scheduler?.close()
        scheduler = null
        scope?.cancel()
        scope = null
    }

    private companion object {
        val PACKAGE_ACTIONS = setOf(Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_CHANGED, Intent.ACTION_PACKAGE_REPLACED)
    }
}

/** Coalesce bursts without cancelling a scan or dropping events that arrive during it. */
internal class ApplicationIndexScheduler(scope: CoroutineScope, private val refresh: suspend () -> Unit) : AutoCloseable {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val job: Job = scope.launch {
        for (request in requests) {
            delay(250)
            while (requests.tryReceive().isSuccess) { /* Merge an installation/update burst. */ }
            refresh()
        }
    }
    fun request() { requests.trySend(Unit) }
    override fun close() { requests.close(); job.cancel() }
}
