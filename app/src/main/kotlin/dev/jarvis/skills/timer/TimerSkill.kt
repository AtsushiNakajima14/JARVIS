package dev.jarvis.skills.timer
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jarvis.assistant.parser.*
import dev.jarvis.skills.*
import javax.inject.Inject
class TimerSkill @Inject constructor(@param:ApplicationContext private val context: Context, private val scheduler: TimerScheduler) : AssistantSkill {
    override val name = "Timer"; override val description = "Set an AlarmManager timer with a notification when time is up."
    override val aliases = setOf("timer", "minutes", "seconds")
    override fun canHandle(command: ParsedCommand) = command.type == IntentType.TIMER
    override suspend fun execute(command: ParsedCommand): SkillResult {
        if (command.value !in 1..86400) return SkillResult("Use a duration between one second and 24 hours.")
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return SkillResult("Allow Notifications in Permissions so the timer can alert you.")
        val exact = scheduler.schedule(command.value)
        val length = if (command.value % 60 == 0L) "${command.value / 60} minutes" else "${command.value} seconds"
        return SkillResult("Timer set for $length.${if (!exact) " Android may delay it. Enable exact alarms in Permissions for precise timers." else ""}")
    }
}
