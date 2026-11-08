package dev.jarvis.skills.apps

import dev.jarvis.assistant.parser.*
import dev.jarvis.device.applications.ApplicationRepository
import dev.jarvis.skills.*
import javax.inject.Inject

class ApplicationSkill @Inject constructor(private val apps: ApplicationRepository) : AssistantSkill {
    override val name = "Application knowledge"
    override val description = "List, categorize, and find recently installed launcher applications."
    override val aliases = setOf("apps", "applications", "recent apps")
    override fun canHandle(command: ParsedCommand) = command.type in setOf(IntentType.LIST_APPLICATIONS, IntentType.SEARCH_APPLICATIONS, IntentType.RECENT_APPLICATIONS)
    override suspend fun execute(command: ParsedCommand): SkillResult {
        if (apps.list().isEmpty()) apps.scan()
        val all = apps.list()
        val selected = when (command.type) {
            IntentType.RECENT_APPLICATIONS -> all.sortedByDescending { it.installedAt }
            IntentType.SEARCH_APPLICATIONS -> all.filter { it.category.equals(if (command.argument == "programming") "Development" else command.argument, true) }
            else -> all
        }
        if (selected.isEmpty()) return SkillResult("I found no apps in that category. Check Installed apps in Settings to refresh the index.")
        return SkillResult("${selected.size} apps${if (command.type == IntentType.RECENT_APPLICATIONS) ", most recent first" else ""}: ${selected.take(8).joinToString { it.label }}${if (selected.size > 8) ". See the full index in Settings" else ""}.")
    }
}

