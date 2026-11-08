package dev.jarvis.assistant.wakeword

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoskModelStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val models = ResourceLeaseStore<Model>(
        open = {
            withContext(Dispatchers.IO) {
                val target = File(context.noBackupFilesDir, "model-en-us")
                val marker = File(target, ".ready")
                if (!marker.exists()) {
                    fun copy(asset: String, file: File) {
                        val children = context.assets.list(asset).orEmpty()
                        if (children.isNotEmpty()) { file.mkdirs(); children.forEach { copy("$asset/$it", File(file, it)) } }
                        else { file.parentFile?.mkdirs(); context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } } }
                    }
                    copy("model-en-us", target)
                    marker.writeText("1")
                }
                Model(target.absolutePath)
            }
        },
        close = { model: Model -> withContext(Dispatchers.IO) { model.close() } }
    )
    /** Warmup only. Native users must use withModel rather than retain this unleased result. */
    suspend fun get(): Model = models.get()
    suspend fun <T> withModel(block: suspend (Model) -> T): T = models.useResource(block)
    /** Reclaim the cache now if idle, otherwise when the final native user has closed. */
    suspend fun release() = models.release()
}

/** Native handles cannot be closed while another service generation still holds one. */
internal class ResourceLeaseStore<T : Any>(private val open: suspend () -> T, private val close: suspend (T) -> Unit) {
    private val mutex = Mutex()
    private var current: T? = null
    private var leases = 0
    private var closeWhenIdle = false

    private suspend fun loadedLocked(): T {
        if (current == null) {
            // Publish a handle even if cancellation arrives during native construction;
            // otherwise a dispatcher return could discard the handle and leak it.
            withContext(NonCancellable) { current = open() }
        }
        return checkNotNull(current)
    }

    suspend fun get(): T = mutex.withLock {
        val resource = loadedLocked()
        currentCoroutineContext().ensureActive()
        resource
    }

    suspend fun <R> useResource(block: suspend (T) -> R): R {
        val resource = mutex.withLock {
            val loaded = loadedLocked()
            currentCoroutineContext().ensureActive()
            leases += 1
            loaded
        }
        try {
            return block(resource)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    check(leases > 0)
                    leases -= 1
                    if (leases == 0 && closeWhenIdle) closeIdleLocked()
                }
            }
        }
    }

    suspend fun release() = withContext(NonCancellable) {
        mutex.withLock {
            closeWhenIdle = true
            if (leases == 0) closeIdleLocked()
        }
    }

    private suspend fun closeIdleLocked() {
        check(leases == 0)
        val resource = current
        current = null
        closeWhenIdle = false
        if (resource != null) close(resource)
    }
}

