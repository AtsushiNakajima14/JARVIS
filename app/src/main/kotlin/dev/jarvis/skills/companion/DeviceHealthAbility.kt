package dev.jarvis.skills.companion

import android.app.KeyguardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.abilities.ReminderRepository
import dev.jarvis.assistant.notifications.NotificationRepository
import dev.jarvis.assistant.parser.IntentType
import dev.jarvis.assistant.parser.ParsedCommand
import dev.jarvis.device.knowledge.DeviceHealthAdvice
import dev.jarvis.device.knowledge.DeviceKnowledgeRepository
import dev.jarvis.skills.AssistantSkill
import dev.jarvis.skills.SkillResult
import javax.inject.Inject

class DeviceHealthAbility @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val knowledge: DeviceKnowledgeRepository,
    private val reminders: ReminderRepository,
    private val notifications: NotificationRepository
) : AssistantSkill {
    override val name = "Device awareness"
    override val description = "Understand phone health and prepare a device overview"
    override val aliases = setOf("phone feels slow", "prepare my phone")
    override fun canHandle(command: ParsedCommand) = command.type in setOf(IntentType.DEVICE_HEALTH, IntentType.PREPARE_PHONE)
    override suspend fun execute(command: ParsedCommand): SkillResult {
        if (locked()) return SkillResult("Unlock your phone first so I can check your personal phone overview.")
        val device = knowledge.refresh()
        val response = if (command.type == IntentType.DEVICE_HEALTH) DeviceHealthAdvice.describe(device)
            else "I've checked your phone. ${device.summary()} ${device.freeStorage?.let { "${dev.jarvis.core.device.DeviceInspector.size(it)} storage is free. " } ?: ""}${reminders.todaySummary()} ${notifications.summary()}"
        return SkillResult(if (locked()) "Unlock your phone first to hear your phone overview." else response)
    }
    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
}
