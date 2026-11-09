package dev.jarvis.skills.personal

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.notifications.NotificationRepository
import dev.jarvis.assistant.parser.IntentType
import dev.jarvis.assistant.parser.ParsedCommand
import dev.jarvis.assistant.response.AssistantPersona
import dev.jarvis.assistant.abilities.ReminderRepository
import java.time.LocalTime
import dev.jarvis.device.security.UnlockActivity
import dev.jarvis.skills.AssistantSkill
import dev.jarvis.skills.SkillResult
import javax.inject.Inject

/** Grounded identity and phone awareness do not depend on the tiny model guessing. */
class PersonalAssistantSkill @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notifications: NotificationRepository,
    private val reminders: ReminderRepository
) : AssistantSkill {
    override val name = "Personal assistant"
    override val description = "Personal identity, notification awareness and Android's secure unlock prompt."
    override val aliases = setOf("who made you", "who am i", "read my notifications", "who messaged me", "unlock my phone", "catch me up")
    override fun canHandle(command: ParsedCommand) = command.type in TYPES

    override suspend fun execute(command: ParsedCommand): SkillResult = when (command.type) {
        IntentType.CREATOR -> SkillResult("I was created by ${AssistantPersona.CREATOR_NAME}, also known as ${AssistantPersona.CREATOR_ALIAS}.")
        IntentType.OWNER_IDENTITY -> SkillResult("You're ${AssistantPersona.CREATOR_NAME}. I'll call you ${AssistantPersona.FRIENDLY_NAME}.")
        IntentType.ASSISTANT_IDENTITY -> SkillResult("I'm JARVIS, ${AssistantPersona.FRIENDLY_NAME}'s private Android companion. Tell me what you need, and I'll help with your phone, communication, memories, and day-to-day tasks.")
        IntentType.GREETING -> {
            val greeting = when (LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
            val capacity = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val battery = if (capacity in 0..100) " Your battery is at $capacity percent." else ""
            SkillResult("$greeting, ${AssistantPersona.FRIENDLY_NAME}.$battery What's on your mind?")
        }
        IntentType.NOTIFICATIONS -> SkillResult(notifications.answer(recentChats = false, appFilter = command.argument))
        IntentType.RECENT_CONVERSATIONS -> SkillResult(notifications.answer(recentChats = true, appFilter = command.argument))
        IntentType.DAILY_BRIEFING -> {
            val capacity = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val battery = if (capacity in 0..100) "Your battery is at $capacity percent. " else ""
            SkillResult("Here's your briefing, ${AssistantPersona.FRIENDLY_NAME}. $battery${reminders.todaySummary()} ${notifications.answer(recentChats = false)}")
        }
        IntentType.UNLOCK_PHONE -> {
            if (!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) SkillResult("Your phone is already unlocked, ${AssistantPersona.FRIENDLY_NAME}.")
            else SkillResult("I'll open Android's unlock screen. Use your fingerprint, face, or device PIN there.", Intent(context, UnlockActivity::class.java))
        }
        else -> SkillResult("What would you like help with, ${AssistantPersona.FRIENDLY_NAME}?")
    }

    private companion object {
        val TYPES = setOf(IntentType.CREATOR, IntentType.OWNER_IDENTITY, IntentType.ASSISTANT_IDENTITY,
            IntentType.GREETING, IntentType.DAILY_BRIEFING, IntentType.NOTIFICATIONS,
            IntentType.RECENT_CONVERSATIONS, IntentType.UNLOCK_PHONE)
    }
}
