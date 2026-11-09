package dev.jarvis.assistant.parser

/** Whole clock questions only: names, message bodies and mentions of time remain ordinary data. */
internal object TimeCommandParser {
    private val timeQuestions = listOf(
        Regex("^what time is it(?: now)?$"),
        Regex("^what(?: is|'s|s) (?:the )?(?:current |local )?(?:time|thyme)(?: now)?$"),
        Regex("^(?:tell me|give me|check|show me) (?:the )?(?:current |local )?time(?: now)?$"),
        Regex("^(?:current time|local time|time now|time|anong oras(?: na)?)$")
    )
    private val dateQuestions = listOf(
        Regex("^what day is (?:it|today)$"),
        Regex("^what(?: is|'s|s) (?:the )?(?:current )?date(?: today)?$"),
        Regex("^what(?: is|'s|s) today(?:'s| s)? date$"),
        Regex("^(?:tell me|give me|check|show me) (?:the )?(?:current )?date(?: today)?$"),
        Regex("^(?:current date|date today|today(?:'s| s) date|anong petsa(?: ngayon)?)$")
    )

    fun parse(text: String): ParsedCommand? {
        val query = text.removeSuffix(" please")
        return when {
            timeQuestions.any { it.matches(query) } -> ParsedCommand(IntentType.TIME_STATUS)
            dateQuestions.any { it.matches(query) } -> ParsedCommand(IntentType.DATE_STATUS)
            else -> null
        }
    }
}
