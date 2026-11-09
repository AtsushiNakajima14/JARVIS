package dev.jarvis.core.voice

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One audio owner across wake listening, platform speech and system assistant recognition. */
object MicrophoneLease {
    private val mutex = Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
