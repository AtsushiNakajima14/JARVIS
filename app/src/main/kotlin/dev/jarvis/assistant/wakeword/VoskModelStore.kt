package dev.jarvis.assistant.wakeword

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoskModelStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private var model: Model? = null
    suspend fun get(): Model = mutex.withLock {
        model ?: withContext(Dispatchers.IO) {
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
            Model(target.absolutePath).also { model = it }
        }
    }
    suspend fun release() = mutex.withLock { withContext(Dispatchers.IO) { model?.close(); model = null } }
}

