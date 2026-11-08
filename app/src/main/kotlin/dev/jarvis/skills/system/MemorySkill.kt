package dev.jarvis.skills.system
import dev.jarvis.assistant.memory.MemoryRepository
import dev.jarvis.assistant.parser.*
import dev.jarvis.core.common.SettingsStore
import dev.jarvis.skills.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject
class MemorySkill @Inject constructor(private val memory: MemoryRepository, private val settings: SettingsStore) : AssistantSkill {
    override val name = "Local memory"; override val description = "Remember preferences and recall relevant saved facts."
    override val aliases = setOf("remember", "recall", "tandaan")
    override fun canHandle(command: ParsedCommand) = command.type in setOf(IntentType.REMEMBER, IntentType.RECALL)
    override suspend fun execute(command: ParsedCommand): SkillResult {
        if (!settings.settings.first().memory) return SkillResult("Memory is disabled in Settings.")
        if (command.type == IntentType.REMEMBER) { memory.remember(command.argument); return SkillResult("I will remember that.") }
        return SkillResult(memory.recall(command.argument)?.let { "You told me: $it." } ?: "I have no saved preference about that yet.")
    }
}

