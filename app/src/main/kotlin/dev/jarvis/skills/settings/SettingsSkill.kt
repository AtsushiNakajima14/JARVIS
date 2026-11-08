package dev.jarvis.skills.settings
import android.content.Intent
import android.provider.Settings
import dev.jarvis.assistant.parser.*
import dev.jarvis.skills.*
import javax.inject.Inject
class SettingsSkill @Inject constructor() : AssistantSkill {
    override val name = "Settings"; override val description = "Open Android settings."
    override val aliases = setOf("settings")
    override fun canHandle(command: ParsedCommand) = command.type == IntentType.SETTINGS
    override suspend fun execute(command: ParsedCommand) = SkillResult("Opening settings.", Intent(Settings.ACTION_SETTINGS))
}

