package dev.jarvis.core.common

import android.content.Intent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

enum class AssistantPhase { IDLE, STARTING, WAKE_WORD, LISTENING, UNDERSTANDING, WORKING, PROCESSING, SPEAKING, PAUSED, ERROR }
data class CompanionTurn(val user: String, val assistant: String, val timestamp: Long = System.currentTimeMillis(), val inputSource: String = "")
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
    val conversation = MutableStateFlow<List<CompanionTurn>>(emptyList())
    val conversationRevision = MutableStateFlow(0L)
    fun recordTurn(user: String, assistant: String, expectedRevision: Long = conversationRevision.value) {
        if (user.isBlank() || assistant.isBlank() || expectedRevision != conversationRevision.value) return
        conversation.update { turns ->
            if (expectedRevision != conversationRevision.value) turns
            else (turns + CompanionTurn(user.take(2000), assistant.take(3500), inputSource = state.value.recognitionSource)).takeLast(40)
        }
    }
    fun clearConversation() {
        conversationRevision.update { it + 1 }
        conversation.value = emptyList()
    }
    @Volatile var visible = false
    fun log(event: String) { logs.update { (listOf("${java.time.LocalTime.now().withNano(0)}  $event") + it).take(60) } }
}

