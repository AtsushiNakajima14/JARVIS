package dev.jarvis

import dev.jarvis.core.voice.MicrophoneLease
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MicrophoneLeaseTest {
    @Test fun `new recognizer waits for cancelled wake recorder cleanup`() = runTest {
        val acquired = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val newOwner = CompletableDeferred<Unit>()
        val wake = launch {
            MicrophoneLease.exclusive {
                acquired.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            }
        }
        acquired.await()
        val recognition = launch { MicrophoneLease.exclusive { newOwner.complete(Unit) } }
        wake.cancel()
        yield()
        assertFalse(newOwner.isCompleted)
        cleanup.complete(Unit)
        wake.join(); recognition.join()
        assertTrue(newOwner.isCompleted)
    }

    @Test fun `cancelled waiting recognizer does not retain the microphone`() = runTest {
        val release = CompletableDeferred<Unit>()
        val acquired = CompletableDeferred<Unit>()
        val owner = launch { MicrophoneLease.exclusive { acquired.complete(Unit); release.await() } }
        acquired.await()
        var cancelledStarted = false
        val waiting = launch { MicrophoneLease.exclusive { cancelledStarted = true } }
        yield(); waiting.cancelAndJoin()
        release.complete(Unit); owner.join()
        assertFalse(cancelledStarted)
        assertEquals("available", MicrophoneLease.exclusive { "available" })
    }
}
