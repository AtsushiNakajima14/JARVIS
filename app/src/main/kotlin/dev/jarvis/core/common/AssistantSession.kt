package dev.jarvis.core.common

import android.content.Intent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

enum class AssistantPhase { IDLE, STARTING, WAKE_WORD, LISTENING, PROCESSING, SPEAKING, PAUSED, ERROR }
data class AssistantState(val phase: AssistantPhase = AssistantPhase.IDLE, val detail: String = "Ready when you are",
    val transcript: String = "", val confirmation: String? = null, val running: Boolean = false,
    val lastResponse: String = "", val recognitionSource: String = "")
sealed interface AssistantEvent {
    data object Wake : AssistantEvent
    data object Listen : AssistantEvent
    data class Text(val text: String) : AssistantEvent
    data object Pause : AssistantEvent
}
@Singleton
class AssistantSession @Inject constructor() {
    val state = MutableStateFlow(AssistantState())
    val launches = Channel<Intent>(Channel.BUFFERED)
    val logs = MutableStateFlow<List<String>>(emptyList())
    @Volatile var visible = false
    fun log(event: String) { logs.update { (listOf("${java.time.LocalTime.now().withNano(0)}  $event") + it).take(60) } }
}

