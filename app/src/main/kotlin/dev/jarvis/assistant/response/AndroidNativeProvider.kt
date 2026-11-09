package dev.jarvis.assistant.response

import dev.jarvis.assistant.parser.normalize
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** Grounded, inexpensive replies when no optional language model is enabled. */
class AndroidNativeProvider @Inject constructor() : AIProvider {
    override val name = "Android companion"
    override suspend fun answer(question: String): String = reply(question)

    fun reply(question: String, clock: Clock = Clock.systemDefaultZone()): String = groundedReply(question, clock)
        ?: "I can help with your phone, people you want to contact, writing a message, finding files, and planning your day. What would you like to work on? For broader questions, you can enable the optional on-phone model in Settings."

    fun groundedReply(question: String, clock: Clock = Clock.systemDefaultZone()): String? {
        val value = normalize(question)
        return when {
            Regex("\\b(?:what time|current time|time is it)\\b").containsMatchIn(value) ->
                "It's ${LocalTime.now(clock).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}."
            Regex("\\b(?:what(?:'s| is)? (?:the )?date|what day|today s date)\\b").containsMatchIn(value) ->
                "Today is ${LocalDate.now(clock).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))}."
            value in setOf("thanks", "thank you", "salamat") -> "You're welcome, CyCy. I'm here when you need me."
            Regex("\\b(?:how are you|how is it going)\\b").containsMatchIn(value) ->
                "I'm ready to help you, CyCy. What's on your mind?"
            else -> null
        }
    }
}
