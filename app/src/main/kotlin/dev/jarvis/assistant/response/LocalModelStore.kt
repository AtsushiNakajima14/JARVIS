package dev.jarvis.assistant.response

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalModelStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    suspend fun model(): File = mutex.withLock {
        withContext(Dispatchers.IO) {
            val folder = File(context.noBackupFilesDir, "local-ai")
            check(folder.isDirectory || folder.mkdirs()) { "Could not create the local AI model directory" }
            val target = File(folder, MODEL)
            AtomicModelCopy.prepare(target, MODEL_BYTES) { context.assets.open("local-ai/$MODEL") }
        }
    }
    companion object {
        const val MODEL = "SmolLM2-135M-Instruct-Q4_0.gguf"
        const val MODEL_BYTES = 91893088L
    }
}

/** Caller serializes copies for one target; incomplete files are never handed to JNI. */
internal object AtomicModelCopy {
    suspend fun prepare(target: File, expectedBytes: Long, source: () -> InputStream): File {
        require(expectedBytes > 0)
        currentCoroutineContext().ensureActive()
        if (target.isFile && target.length() == expectedBytes) return target
        val partial = File(target.parentFile, "${target.name}.partial")
        try {
            source().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        currentCoroutineContext().ensureActive()
                        if (count < 0) break
                        if (count == 0) { yield(); continue }
                        check(copied + count <= expectedBytes) { "Local AI model copy exceeds the expected size" }
                        output.write(buffer, 0, count)
                        copied += count
                    }
                    check(copied == expectedBytes) { "Local AI model copy was incomplete" }
                    output.flush()
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            currentCoroutineContext().ensureActive()
            return target
        } finally { if (partial.isFile) partial.delete() }
    }
}
