package dev.jarvis.skills.apps

import dev.jarvis.assistant.parser.*
import dev.jarvis.device.applications.ApplicationRepository
import dev.jarvis.skills.*
import javax.inject.Inject

class OpenAppSkill @Inject constructor(private val apps: ApplicationRepository) : AssistantSkill {
    override val name = "Open application"
    override val description = "Launch an installed application by name."
    override val aliases = setOf("open", "launch", "buksan", "ablihi")
    override fun canHandle(command: ParsedCommand) = command.type == IntentType.OPEN_APPLICATION
    override suspend fun execute(command: ParsedCommand): SkillResult {
        if (apps.list().isEmpty()) apps.scan()
        val app = apps.find(command.argument) ?: return SkillResult("I could not find one clear match. Try the full application name.")
        val intent = apps.launchIntent(app) ?: return SkillResult("That application cannot be opened.")
        return SkillResult("Opening ${app.label}.", launch = intent)
    }
}

