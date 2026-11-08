package dev.jarvis

import dev.jarvis.assistant.wakeword.ResourceLeaseStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoskModelLeaseTest {
    private class Handle(val id: Int) { var closed = false }

    @Test fun `warmup is cached and idle release reclaims it once`() = runTest {
        var opened = 0
        val closed = mutableListOf<Int>()
        val store = ResourceLeaseStore({ Handle(++opened) }, { it.closed = true; closed += it.id })
        val first = store.get()
        assertSame(first, store.get())
        store.release(); store.release()
        assertTrue(first.closed)
        assertEquals(listOf(1), closed)
        val second = store.get()
        assertNotSame(first, second)
        assertFalse(second.closed)
        store.release()
        assertEquals(listOf(1, 2), closed)
    }

    @Test fun `release waits for recognizer cleanup before closing the model`() = runTest {
        val events = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val handle = Handle(1)
        val store = ResourceLeaseStore({ handle }, { it.closed = true; events += "model closed" })
        val capture = launch {
            store.useResource {
                entered.complete(Unit)
                try { finish.await(); assertFalse(it.closed) }
                finally { events += "recognizer closed" }
            }
        }
        entered.await(); store.release()
        assertFalse(handle.closed)
        finish.complete(Unit); capture.join()
        assertEquals(listOf("recognizer closed", "model closed"), events)
    }

    @Test fun `old service cleanup cannot close a restarted service active model`() = runTest {
        val oldEntered = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val oldFinish = CompletableDeferred<Unit>()
        val newFinish = CompletableDeferred<Unit>()
        val handle = Handle(1)
        var closes = 0
        val store = ResourceLeaseStore({ handle }, { it.closed = true; closes++ })
        val old = launch { store.useResource { oldEntered.complete(Unit); oldFinish.await() } }
        oldEntered.await(); store.release()
        val restarted = launch { store.useResource { assertSame(handle, it); newEntered.complete(Unit); newFinish.await(); assertFalse(it.closed) } }
        newEntered.await(); oldFinish.complete(Unit); old.join()
        assertFalse(handle.closed)
        assertEquals(0, closes)
        newFinish.complete(Unit); restarted.join()
        assertTrue(handle.closed)
        assertEquals(1, closes)
    }

    @Test fun `cancelling capture still decrements lease and closes a requested model`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val store = ResourceLeaseStore({ Handle(1) }, { delay(1); it.closed = true; events += "model" })
        val capture = launch {
            store.useResource {
                entered.complete(Unit)
                try { awaitCancellation() } finally { events += "recognizer" }
            }
        }
        entered.await(); store.release(); capture.cancelAndJoin()
        assertEquals(listOf("recognizer", "model"), events)
    }

    @Test fun `cancelled native construction publishes its handle for cleanup without a leaked lease`() = runTest {
        val loading = CompletableDeferred<Unit>()
        val loaded = CompletableDeferred<Unit>()
        val handle = Handle(1)
        var bodyRan = false
        val store = ResourceLeaseStore({ loading.complete(Unit); loaded.await(); handle }, { it.closed = true })
        val capture = launch { store.useResource { bodyRan = true } }
        loading.await(); capture.cancel()
        val cleanup = launch { store.release() }
        loaded.complete(Unit); capture.join(); cleanup.join()
        assertFalse(bodyRan)
        assertTrue(handle.closed)
    }

    @Test fun `failed construction leaves the cache usable`() = runTest {
        var attempts = 0
        var closes = 0
        val store = ResourceLeaseStore({ if (++attempts == 1) error("load failed") else Handle(attempts) }, { closes++ })
        try { store.get(); fail("Expected failed construction") } catch (_: IllegalStateException) { }
        assertEquals(2, store.useResource { it.id })
        store.release()
        assertEquals(1, closes)
    }

    @Test fun `exception inside a lease cannot retain the model after release`() = runTest {
        val handle = Handle(1)
        val store = ResourceLeaseStore({ handle }, { it.closed = true })
        try { store.useResource { store.release(); error("recognizer failed") } }
        catch (_: IllegalStateException) { }
        assertTrue(handle.closed)
    }

    @Test fun `a new model cannot load until the old native close finishes`() = runTest {
        var opened = 0
        val closing = CompletableDeferred<Unit>()
        val finishClose = CompletableDeferred<Unit>()
        val store = ResourceLeaseStore({ Handle(++opened) }, {
            if (it.id == 1) { closing.complete(Unit); finishClose.await() }
            it.closed = true
        })
        val first = store.get()
        val cleanup = launch { store.release() }
        closing.await()
        val next = async { store.useResource { assertFalse(it.closed); it.id } }
        yield()
        assertEquals(1, opened)
        finishClose.complete(Unit); cleanup.join()
        assertTrue(first.closed)
        assertEquals(2, next.await())
        store.release()
    }

    @Test fun `cancelling cleanup cannot interrupt a native close already in progress`() = runTest {
        val closing = CompletableDeferred<Unit>()
        val finishClose = CompletableDeferred<Unit>()
        val handle = Handle(1)
        val store = ResourceLeaseStore({ handle }, { closing.complete(Unit); finishClose.await(); it.closed = true })
        store.get()
        val cleanup = launch { store.release() }
        closing.await(); cleanup.cancel(); finishClose.complete(Unit); cleanup.join()
        assertTrue(handle.closed)
    }
}
