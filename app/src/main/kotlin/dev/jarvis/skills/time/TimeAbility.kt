package dev.jarvis.skills.time

import dev.jarvis.assistant.parser.IntentType
import dev.jarvis.assistant.parser.ParsedCommand
import dev.jarvis.skills.AssistantAbility
import dev.jarvis.skills.SkillResult
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** The phone's clock supplies time and date; no model or network is involved. */
class TimeAbility @Inject constructor() : AssistantAbility {
    override val name = "Time and date"
    override val description = "Read the current local time and date from Android's clock."
    override val aliases = setOf("time", "date", "what time is it", "what day is it")
    override fun canHandle(command: ParsedCommand) = command.type in setOf(IntentType.TIME_STATUS, IntentType.DATE_STATUS)
    override suspend fun execute(command: ParsedCommand) = SkillResult(ClockAnswer.reply(command.type))
}

object ClockAnswer {
    fun reply(type: IntentType, clock: Clock = Clock.systemDefaultZone()): String = when (type) {
        IntentType.TIME_STATUS -> "It's ${LocalTime.now(clock).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}."
        IntentType.DATE_STATUS -> "Today is ${LocalDate.now(clock).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))}."
        else -> "Would you like the current time or today's date?"
    }
}
