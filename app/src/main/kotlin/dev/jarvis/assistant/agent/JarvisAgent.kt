package dev.jarvis.assistant.agent

import android.app.KeyguardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.core.common.AssistantSession
import dev.jarvis.core.common.SettingsStore
import dev.jarvis.core.database.JarvisDao
import dev.jarvis.device.applications.ApplicationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Native goal planning. The optional language model never selects or executes phone actions. */
@Singleton
class JarvisAgent @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val apps: ApplicationRepository,
    private val dao: JarvisDao,
    private val settings: SettingsStore,
    private val session: AssistantSession
) {
    private val core = AgentCore()
    private var revision = -1L

    suspend fun decide(input: String, assistantName: String): AgentDecision {
        val startRevision = session.conversationRevision.value
        if (revision != startRevision) { core.reset(); revision = startRevision }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val config = settings.settings.first()
        val inventory = apps.list()
        val saved = if (config.memory && !keyguard.isKeyguardLocked) dao.preferenceList() else emptyList()
        // An explicit memory is the only source of a preferred communication app.
        val preferred = saved.firstNotNullOfOrNull { memory ->
            val text = memory.content.text
            val match = Regex("(?:i (?:use|prefer)|my (?:preferred |favorite )?(?:chat|messaging) app is)\\s+(.+?)(?:\\s+for (?:chatting|messages|messaging|communication)|$)", RegexOption.IGNORE_CASE).find(text)
            match?.groupValues?.get(1)?.let { label -> inventory.singleOrNull { it.label.equals(label.trim(), true) }?.label }
        }
        val knowledge = if (keyguard.isKeyguardLocked) AgentKnowledge() else AgentKnowledge(
            appLabels = inventory.map { it.label }, preferredChatApp = preferred,
            memories = saved.take(100).map { AgentMemory(it.id, it.content.text, "memory") },
            communicationApps = inventory.filter { app ->
                app.category == "Social" || Regex("messenger|messages|whatsapp|telegram|signal|discord|viber|instagram", RegexOption.IGNORE_CASE).containsMatchIn(app.label)
            }.map { it.label })
        if (session.conversationRevision.value != startRevision) {
            core.reset()
            revision = session.conversationRevision.value
            throw CancellationException("Conversation cleared while understanding the request")
        }
        return core.handle(input, assistantName, knowledge)
    }

    fun hasPendingReply(): Boolean = revision == session.conversationRevision.value && core.hasPendingReply()
    fun reset() { core.reset(); revision = session.conversationRevision.value }
}
