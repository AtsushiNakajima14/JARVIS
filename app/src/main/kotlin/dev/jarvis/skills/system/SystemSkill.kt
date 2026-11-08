package dev.jarvis.skills.system
import dev.jarvis.assistant.parser.*
import dev.jarvis.core.device.DeviceInspector
import dev.jarvis.device.applications.ApplicationRepository
import dev.jarvis.device.usage.UsageRepository
import dev.jarvis.skills.*
import javax.inject.Inject
class SystemSkill @Inject constructor(private val device: DeviceInspector, private val usage: UsageRepository, private val apps: ApplicationRepository) : AssistantSkill {
    override val name = "Device intelligence"; override val description = "Inspect device, network, RAM, storage and optional usage statistics."
    override val aliases = setOf("device information", "storage", "memory usage", "system status", "most used", "rarely used")
    override fun canHandle(command: ParsedCommand) = command.type in setOf(IntentType.DEVICE_INFO, IntentType.MEMORY_USAGE, IntentType.STORAGE, IntentType.SYSTEM_STATUS, IntentType.MOST_USED, IntentType.RARELY_USED)
    override suspend fun execute(command: ParsedCommand) = SkillResult(when (command.type) {
        IntentType.DEVICE_INFO -> device.snapshot().filterKeys { it in setOf("Device", "Android", "RAM", "Storage") }.values.joinToString(". ")
        IntentType.MEMORY_USAGE -> device.ram()
        IntentType.SYSTEM_STATUS -> device.network() + " " + device.battery()
        IntentType.STORAGE -> usage.storage(apps.list())
        else -> usage.usage(apps.list(), command.type == IntentType.RARELY_USED)
    })
}

