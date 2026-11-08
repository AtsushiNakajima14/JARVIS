package dev.jarvis

import dev.jarvis.device.applications.ApplicationIndexScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationIndexSchedulerTest {
    @Test fun `installation bursts trigger one scan`() = runTest {
        var scans = 0
        val scheduler = ApplicationIndexScheduler(backgroundScope) { scans++ }
        try {
            repeat(20) { scheduler.request() }
            runCurrent()
            advanceTimeBy(251)
            runCurrent()
            assertEquals(1, scans)
        } finally { scheduler.close() }
    }
    @Test fun `events during a scan cause a later scan without cancelling the first`() = runTest {
        var scans = 0
        val finishFirst = CompletableDeferred<Unit>()
        val scheduler = ApplicationIndexScheduler(backgroundScope) {
            scans++
            if (scans == 1) finishFirst.await()
        }
        try {
            scheduler.request()
            runCurrent(); advanceTimeBy(251); runCurrent()
            assertEquals(1, scans)
            repeat(5) { scheduler.request() }
            finishFirst.complete(Unit)
            runCurrent(); advanceTimeBy(251); runCurrent()
            assertEquals(2, scans)
        } finally { scheduler.close() }
    }
    @Test fun `closing the observer cancels pending refreshes`() = runTest {
        var scans = 0
        val scheduler = ApplicationIndexScheduler(backgroundScope) { scans++ }
        scheduler.request(); runCurrent()
        scheduler.close()
        advanceUntilIdle()
        assertEquals(0, scans)
    }
}
