package dev.jarvis.assistant.abilities

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.memory.MemoryRepository
import dev.jarvis.assistant.parser.IntentType
import dev.jarvis.assistant.parser.ParsedCommand
import dev.jarvis.core.common.SettingsStore
import dev.jarvis.skills.AssistantSkill
import dev.jarvis.skills.SkillResult
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Native abilities selected by the agent; they never send messages or execute model output. */
class CompanionAbility @Inject constructor(@param:ApplicationContext private val context: Context,
    private val memory: MemoryRepository, private val reminders: ReminderRepository,
    private val files: FileKnowledgeRepository, private val settings: SettingsStore) : AssistantSkill {
    override val name = "Personal companion"
    override val description = "Remember approved facts, organize tasks, and find accessible documents."
    override val aliases = setOf("memories", "reminders", "documents")
    override fun canHandle(command: ParsedCommand) = command.type in TYPES
    override suspend fun execute(command: ParsedCommand): SkillResult {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) return SkillResult("Unlock your phone so I can help with your private memories, tasks, and files.")
        val result = when (command.type) {
            IntentType.MEMORY_LIST -> if (settings.settings.first().memory) SkillResult(memory.describe())
                else SkillResult("Saved memory is disabled. You can review or delete saved facts in the memory viewer.")
            IntentType.MEMORY_FORGET -> SkillResult(memory.forgetMatching(command.argument))
            IntentType.REMINDER_CREATE -> {
                if (!ReminderPolicy.validDueTime(command.value, System.currentTimeMillis()))
                    return SkillResult("When should I remind you? Choose a future time within the next year.")
                val item = reminders.create(command.argument, command.value)
                val permission = if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "" else " Allow JARVIS notifications in Android settings to receive the alert."
                SkillResult("I'll remind you around ${formatTime(item.dueAt)}. Android may delay reminders to save battery.$permission")
            }
            IntentType.REMINDER_LIST -> {
                val items = reminders.list().filterNot { it.completed }
                SkillResult(if (items.isEmpty()) "You have no unfinished tasks. Tell me what you'd like a reminder for."
                    else "You have ${items.size} unfinished tasks. " + items.take(5).joinToString(" ") { "${it.title.take(100)}, ${formatTime(it.dueAt)}." } +
                        if (items.size > 5) " The rest are in your task list." else "")
            }
            IntentType.REMINDER_COMPLETE -> SkillResult(reminders.complete(command.argument))
            IntentType.FILE_SEARCH, IntentType.RECENT_FILES -> {
                if (command.argument in setOf("__choose__", "choose files", "select files", "add files")) {
                    SkillResult("Choose the documents you want me to know about. I save only their names and access details.",
                        Intent(context, FileAccessActivity::class.java))
                } else {
                    val matches = files.search(if (command.type == IntentType.RECENT_FILES) "" else command.argument)
                    if (matches.isEmpty()) SkillResult("I couldn't find that in the documents shared with me or Android's accessible recent files. Choose documents to add them to my local index.",
                        Intent(context, FileAccessActivity::class.java))
                    else SkillResult("In your shared documents and accessible files, I found " +
                        matches.take(5).joinToString(", ") { it.name.take(90) } + "." +
                        if (matches.size > 5) " There are more results; tell me a more specific filename." else "")
                }
            }
            else -> SkillResult("Tell me what you would like help with.")
        }
        return if (keyguard.isKeyguardLocked) SkillResult("Your phone locked. Unlock it to continue with your private information.") else result
    }

    companion object {
        val TYPES = setOf(IntentType.FILE_SEARCH, IntentType.RECENT_FILES, IntentType.REMINDER_CREATE,
            IntentType.REMINDER_LIST, IntentType.REMINDER_COMPLETE, IntentType.MEMORY_LIST, IntentType.MEMORY_FORGET)
        fun formatTime(value: Long): String = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a")
            .format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
    }
}
