package dev.jarvis.skills.battery
import dev.jarvis.assistant.parser.*
import dev.jarvis.core.device.DeviceInspector
import dev.jarvis.skills.*
import javax.inject.Inject
class BatterySkill @Inject constructor(private val device: DeviceInspector) : AssistantSkill {
    override val name = "Battery"; override val description = "Read battery percentage and charging status."
    override val aliases = setOf("battery", "baterya")
    override fun canHandle(command: ParsedCommand) = command.type == IntentType.BATTERY_STATUS
    override suspend fun execute(command: ParsedCommand) = SkillResult(device.battery())
}

