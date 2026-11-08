package dev.jarvis

import dev.jarvis.assistant.response.AtomicModelCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class LocalModelCopyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun target() = File(temporary.root, "model.gguf")
    @Test fun `complete model is published and a correct cache does not reopen assets`() = runBlocking {
        val model = target()
        val bytes = "GGUF test data".toByteArray()
        assertEquals(model, AtomicModelCopy.prepare(model, bytes.size.toLong()) { ByteArrayInputStream(bytes) })
        assertArrayEquals(bytes, model.readBytes())
        assertFalse(File(temporary.root, "model.gguf.partial").exists())
        AtomicModelCopy.prepare(model, bytes.size.toLong()) { throw AssertionError("A valid cache reopened the asset") }
        Unit
    }
    @Test fun `new complete copy atomically replaces an invalid existing cache`() = runBlocking {
        val model = target().apply { writeText("old") }
        val bytes = "GGUF replacement".toByteArray()
        AtomicModelCopy.prepare(model, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        assertArrayEquals(bytes, model.readBytes())
        assertFalse(File(temporary.root, "model.gguf.partial").exists())
    }
    @Test fun `short or oversized asset does not publish or replace the previous model`() = runBlocking {
        val model = target().apply { writeText("old") }
        val bytes = "GGUF short".toByteArray()
        for (expected in listOf(bytes.size + 1L, bytes.size - 1L)) {
            try {
                AtomicModelCopy.prepare(model, expected) { ByteArrayInputStream(bytes) }
                fail("An invalid model size was published")
            } catch (_: IllegalStateException) {}
            assertEquals("old", model.readText())
            assertFalse(File(temporary.root, "model.gguf.partial").exists())
        }
    }
    @Test fun `cancellation during input read removes partial data and keeps old cache`() = runBlocking {
        val model = target().apply { writeText("old") }
        val bytes = "GGUF private model".toByteArray()
        lateinit var pending: Deferred<File>
        pending = async(start = CoroutineStart.LAZY) {
            AtomicModelCopy.prepare(model, bytes.size.toLong()) {
                object : ByteArrayInputStream(bytes) {
                    override fun read(output: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(output, offset, length)
                        pending.cancel()
                        return count
                    }
                }
            }
        }
        pending.start()
        try { pending.await(); fail("Cancelled model copy returned a model") }
        catch (_: CancellationException) {}
        assertEquals("old", model.readText())
        assertFalse(File(temporary.root, "model.gguf.partial").exists())
    }
    @Test fun `input failure closes its source and removes partial data`() = runBlocking {
        val model = target().apply { writeText("old") }
        var closed = false
        val bytes = "GGUF test data".toByteArray()
        try {
            AtomicModelCopy.prepare(model, bytes.size.toLong()) {
                object : ByteArrayInputStream(bytes) {
                    var reads = 0
                    override fun read(output: ByteArray, offset: Int, length: Int): Int {
                        if (reads++ > 0) throw IOException("Read failed")
                        return super.read(output, offset, minOf(length, 3))
                    }
                    override fun close() { closed = true; super.close() }
                }
            }
            fail("Input failure was swallowed")
        } catch (_: IOException) {}
        assertTrue(closed)
        assertEquals("old", model.readText())
        assertFalse(File(temporary.root, "model.gguf.partial").exists())
    }
}
