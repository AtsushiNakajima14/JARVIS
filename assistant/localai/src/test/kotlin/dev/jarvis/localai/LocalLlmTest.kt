package dev.jarvis.localai

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalLlmTest {
    private open class Backend : LocalInferenceBackend {
        override val loaded = true
        val forgotten = AtomicInteger()
        override fun ensureLoaded() {}
        override fun generate(requestId: Long, modelPath: ByteArray, prompt: ByteArray, maxTokens: Int, timeoutMs: Long) =
            "A real backend result".toByteArray()
        override fun cancel(requestId: Long) {}
        override fun forget(requestId: Long) { forgotten.incrementAndGet() }
    }
    private fun model() = File.createTempFile("localai-test-", ".gguf").apply { deleteOnExit() }

    @Test fun `input and output cross the worker as real UTF8`() = runBlocking {
        val backend = object : Backend() {
            override fun generate(requestId: Long, modelPath: ByteArray, prompt: ByteArray, maxTokens: Int, timeoutMs: Long): ByteArray {
                assertEquals("Kumusta, 日本 👋", prompt.toString(Charsets.UTF_8))
                return "  Magandang araw 👋  ".toByteArray(Charsets.UTF_8)
            }
        }
        val llm = LocalLlm(backend)
        assertEquals("Magandang araw 👋", llm.generate(model().path, "Kumusta, 日本 👋"))
        llm.awaitIdle()
        assertEquals(1, backend.forgotten.get())
    }
    @Test fun `cancellation reaches native work and idle waits for cleanup`() = runBlocking {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val cancelSeen = AtomicBoolean(false)
        val backend = object : Backend() {
            override fun generate(requestId: Long, modelPath: ByteArray, prompt: ByteArray, maxTokens: Int, timeoutMs: Long): ByteArray {
                entered.countDown()
                check(stopped.await(3, TimeUnit.SECONDS)) { "Cancellation did not reach the backend" }
                throw CancellationException("native request cancelled")
            }
            override fun cancel(requestId: Long) { cancelSeen.set(true); stopped.countDown() }
        }
        val llm = LocalLlm(backend)
        val running = async(Dispatchers.Default) { llm.generate(model().path, "Hello") }
        assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
        running.cancelAndJoin()
        llm.awaitIdle()
        assertTrue(cancelSeen.get())
        assertEquals(1, backend.forgotten.get())
    }
    @Test fun `concurrent callers are serialized and native exceptions propagate`() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val backend = object : Backend() {
            override fun generate(requestId: Long, modelPath: ByteArray, prompt: ByteArray, maxTokens: Int, timeoutMs: Long): ByteArray {
                val count = active.incrementAndGet()
                maximum.accumulateAndGet(count) { before, now -> maxOf(before, now) }
                try {
                    Thread.sleep(20)
                    if (prompt.toString(Charsets.UTF_8) == "fail") throw IllegalStateException("backend failed")
                    return "done".toByteArray()
                } finally { active.decrementAndGet() }
            }
        }
        val llm = LocalLlm(backend)
        val path = model().path
        val answers = (1..4).map { async(Dispatchers.Default) { llm.generate(path, "question $it") } }.awaitAll()
        assertEquals(listOf("done", "done", "done", "done"), answers)
        assertEquals(1, maximum.get())
        try { llm.generate(path, "fail"); fail("Backend failure was hidden") }
        catch (expected: IllegalStateException) { assertEquals("backend failed", expected.message) }
        llm.awaitIdle()
        assertEquals(5, backend.forgotten.get())
    }
    @Test fun `missing model and invalid resource limits fail without native allocation`() = runBlocking {
        val llm = LocalLlm(Backend())
        try { llm.generate("missing-localai-model.gguf", "Hello"); fail("Missing model was accepted") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("model")) }
        try { llm.generate(model().path, "Hello", maxTokens = 1000); fail("Unbounded output was accepted") }
        catch (_: IllegalArgumentException) {}
        llm.awaitIdle()
    }
}
