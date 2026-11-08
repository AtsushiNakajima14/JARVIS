package dev.jarvis.localai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CPU-only local inference. One executor serializes all native allocations, including
 * across accidentally duplicated instances. Each reply frees its model and context.
 */
class LocalLlm internal constructor(private val native: LocalInferenceBackend) {
    constructor() : this(JniInferenceBackend)
    private class Request(val id: Long, val cancelled: AtomicBoolean = AtomicBoolean(), var finished: Boolean = false)
    private val requests = ConcurrentHashMap<Long, Request>()

    suspend fun generate(modelPath: String, prompt: String, maxTokens: Int = 80,
        timeoutMs: Long = 20_000): String {
        require(prompt.isNotBlank()) { "A local AI prompt is required." }
        require(maxTokens in 1..128) { "Local AI generation is limited to 128 tokens." }
        require(timeoutMs in 1..60_000) { "Local AI timeout must be between 1 ms and 60 seconds." }
        return suspendCancellableCoroutine { continuation ->
            val request = Request(sequence.incrementAndGet())
            requests[request.id] = request
            continuation.invokeOnCancellation { cancelRequest(request) }
            executor.execute {
                try {
                    if (!continuation.isActive || request.cancelled.get()) throw CancellationException("Local AI cancelled")
                    require(File(modelPath).isFile) { "Local AI model is not installed." }
                    native.ensureLoaded()
                    if (!continuation.isActive || request.cancelled.get()) throw CancellationException("Local AI cancelled")
                    val bytes = native.generate(request.id, modelPath.toByteArray(Charsets.UTF_8),
                        prompt.toByteArray(Charsets.UTF_8), maxTokens, timeoutMs)
                    if (continuation.isActive) continuation.resume(bytes.toString(Charsets.UTF_8).trim())
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    synchronized(request) {
                        request.finished = true
                        requests.remove(request.id)
                        if (native.loaded) native.forget(request.id)
                    }
                }
            }
        }
    }

    /** Request immediate cancellation; native CPU decode checks an atomic abort flag. */
    fun cancel() { requests.values.forEach(::cancelRequest) }

    /** Wait until preceding native work has released all model/context resources. */
    suspend fun awaitIdle(): Unit = suspendCancellableCoroutine { continuation ->
        executor.execute { if (continuation.isActive) continuation.resume(Unit) }
    }

    private fun cancelRequest(request: Request) {
        synchronized(request) {
            if (request.finished) return
            request.cancelled.set(true)
            if (native.loaded) native.cancel(request.id)
        }
    }

    companion object {
        private val sequence = AtomicLong()
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "JARVIS-local-ai").apply { isDaemon = true }
        }
    }
}

internal interface LocalInferenceBackend {
    val loaded: Boolean
    fun ensureLoaded()
    fun generate(requestId: Long, modelPath: ByteArray, prompt: ByteArray, maxTokens: Int, timeoutMs: Long): ByteArray
    fun cancel(requestId: Long)
    fun forget(requestId: Long)
}

private object JniInferenceBackend : LocalInferenceBackend {
    @Volatile override var loaded = false
        private set
    @Synchronized override fun ensureLoaded() {
        if (!loaded) { System.loadLibrary("jarvis_local_llm"); loaded = true }
    }
    override fun generate(requestId: Long, modelPath: ByteArray, prompt: ByteArray, maxTokens: Int, timeoutMs: Long): ByteArray =
        NativeLlm.generate(requestId, modelPath, prompt, maxTokens, timeoutMs)
    override fun cancel(requestId: Long) = NativeLlm.cancel(requestId)
    override fun forget(requestId: Long) = NativeLlm.forget(requestId)
}

internal object NativeLlm {
    external fun generate(requestId: Long, modelPath: ByteArray, prompt: ByteArray,
        maxTokens: Int, timeoutMs: Long): ByteArray
    external fun cancel(requestId: Long)
    external fun forget(requestId: Long)
}
