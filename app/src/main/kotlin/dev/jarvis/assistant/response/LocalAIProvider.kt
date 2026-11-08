package dev.jarvis.assistant.response

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.memory.MemoryRepository
import dev.jarvis.core.common.SettingsStore
import dev.jarvis.device.applications.ApplicationRepository
import dev.jarvis.localai.LocalLlm
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

class LocalAiLowMemory : Exception("Not enough free memory to start local AI")

@Singleton
class LocalAIProvider @Inject constructor(@param:ApplicationContext private val context: Context,
    private val models: LocalModelStore, private val settings: SettingsStore,
    private val memory: MemoryRepository, private val applications: ApplicationRepository) : AIProvider {
    override val name = "SmolLM2 135M · on this phone"
    private val runtime = LocalLlm()
    private val conversation = ConversationContext()

    override suspend fun answer(question: String): String {
        // Snapshot before the first suspension: a queued request must not restore a
        // conversation that the user cleared while inference was starting/running.
        val snapshot = conversation.snapshot()
        return withContext(Dispatchers.IO) {
            val config = settings.settings.first()
            check(config.localAi) { "Local AI is disabled" }
            val memoryInfo = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
            if (memoryInfo.availMem < 220L * 1024 * 1024) throw LocalAiLowMemory()
            val apps = applications.list()
            val tokens = question.lowercase().split(Regex("[^a-z0-9]+" )).filter { it.length > 2 }.toSet()
            val relevant = apps.filter { app -> tokens.any { app.label.contains(it, true) } }.take(4).joinToString { it.label }
            val facts = "Phone: ${Build.MANUFACTURER} ${Build.MODEL}. ${apps.size} launchable apps indexed. " +
                (if (relevant.isNotBlank()) "Relevant installed apps: $relevant. " else "") + "Now: ${LocalDateTime.now().withSecond(0).withNano(0)}."
            val preference = if (config.memory) memory.recall(question) else null
            val prompt = LocalPrompt.build(config.name, question, facts, preference, snapshot.turns)
            if (currentRevision() != snapshot.revision) throw CancellationException("Conversation cleared before local AI inference")
            val model = models.model()
            currentCoroutineContext().ensureActive()
            if (currentRevision() != snapshot.revision) throw CancellationException("Conversation cleared before local AI inference")
            val response = try {
                LocalPrompt.cleanReply(runtime.generate(model.absolutePath, prompt, maxTokens = 64, timeoutMs = 20_000))
            } finally {
                // Cancellation must wait for native allocations to be freed before wake recognition reloads.
                withContext(NonCancellable) { runtime.awaitIdle() }
            }
            currentCoroutineContext().ensureActive()
            check(response.isNotBlank()) { "Local AI did not produce a reply" }
            if (!recordTurn(question, response, snapshot.revision)) throw CancellationException("Conversation cleared during local AI reply")
            response
        }
    }
    fun cancel() = runtime.cancel()
    suspend fun awaitIdle() = runtime.awaitIdle()
    fun currentRevision(): Long = conversation.currentRevision()
    fun recordTurn(question: String, response: String, expectedRevision: Long = currentRevision()): Boolean =
        conversation.recordTurn(question, response, expectedRevision)
    fun clearConversation() { conversation.clear(); runtime.cancel() }
}
