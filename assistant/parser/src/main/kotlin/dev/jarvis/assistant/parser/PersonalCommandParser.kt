package dev.jarvis.assistant.parser

/** Explicit personal-assistant requests; notification contents are never parsed as commands. */
internal object PersonalCommandParser {
    private val creator = setOf(
        "who made you", "who created you", "who built you", "who developed you", "who programmed you",
        "who is your creator", "who is your developer", "who is your maker", "tell me who made you"
    )
    private val identity = setOf("who are you", "what are you", "what is your name", "what's your name",
        "introduce yourself", "tell me about yourself")
    private val owner = setOf("who am i", "what is my name", "what's my name", "what do you call me",
        "who is your owner", "what is your owner's name", "who do you belong to")
    private val unlock = setOf("unlock my phone", "unlock the phone", "unlock my device", "unlock the device",
        "unlock my screen", "unlock the screen", "unlock phone", "help me unlock my phone",
        "show the unlock screen", "show me the unlock screen", "open the unlock screen")
    private val greetings = setOf("hello", "hi", "hey", "hello there", "good morning", "good afternoon",
        "good evening", "how are you", "how are you doing", "how's it going", "how is it going")
    private val briefings = setOf("give me a briefing", "give me my briefing", "my briefing", "daily briefing",
        "give me my daily briefing", "catch me up", "what did i miss", "what have i missed")
    private val notifications = listOf(
        Regex("^(?:read(?: out| me)?|show|check|summari[sz]e|tell me(?: about)?) (?:all (?:of )?)?(?:(?:my|the) )?(?:(?:new|latest|last|recent|unread) )?(?:notifications?|messages?)$"),
        Regex("^(?:(?:do i have|have i got|are there)(?: any)?|any)(?: (?:new|latest|recent|unread))? (?:notifications?|messages?)$"),
        Regex("^what (?:notifications?|messages?) do i have$"),
        Regex("^what (?:are|is) (?:(?:my|the) )?(?:(?:new|latest|last|recent|unread) )?(?:notifications?|messages?)$"),
        Regex("^(?:what does it say|read that notification)$")
    )
    private val recent = listOf(
        Regex("^who (?:(?:recently|last) )?(?:messaged|texted|contacted|chatted with) me(?: (?:recently|last|lately))?$"),
        Regex("^who (?:(?:did|have) i|i) (?:(?:recently|last) )?(?:chat(?:ted)?|talk(?:ed)?) (?:with|to)(?: (?:recently|last|lately))?$"),
        Regex("^who (?:sent|has sent) me (?:a |any )?(?:(?:new|recent|last) )?(?:messages?|texts?)$"),
        Regex("^(?:show|read|check|list) (?:my )?(?:recent|latest|last) (?:chats|conversations|contacts)$"),
        Regex("^who (?:was|is) my (?:last|most recent) (?:chat|conversation) with$")
    )
    private val appSuffix = Regex("\\s+(?:on|from|in)\\s+([\\p{L}\\p{N}][\\p{L}\\p{N} .&'’+_-]{0,79})$")
    private val embeddedApp = Regex("^(?:read(?: out| me)?|show|check|summari[sz]e|tell me(?: about)?) (?:(?:my|the) )?([\\p{L}\\p{N}][\\p{L}\\p{N} .&'’+_-]{0,79}) (?:notifications?|messages?)$")

    fun parse(text: String): ParsedCommand? {
        val query = text.removeSuffix(" please")
        when (query) {
            in creator -> return ParsedCommand(IntentType.CREATOR)
            in identity -> return ParsedCommand(IntentType.ASSISTANT_IDENTITY)
            in owner -> return ParsedCommand(IntentType.OWNER_IDENTITY)
            in unlock -> return ParsedCommand(IntentType.UNLOCK_PHONE)
            in greetings -> return ParsedCommand(IntentType.GREETING)
            in briefings -> return ParsedCommand(IntentType.DAILY_BRIEFING)
        }
        // First try the complete request so app names never broaden the command shape.
        if (recent.any { it.matches(query) }) return ParsedCommand(IntentType.RECENT_CONVERSATIONS)
        if (notifications.any { it.matches(query) }) return ParsedCommand(IntentType.NOTIFICATIONS)
        embeddedApp.matchEntire(query)?.let {
            val filter = it.groupValues[1].removeSuffix(" app").trim()
            if (filter.isNotBlank()) return ParsedCommand(IntentType.NOTIFICATIONS, argument = filter)
        }
        val app = appSuffix.find(query) ?: return null
        val request = query.take(app.range.first)
        val filter = app.groupValues[1].trim().removeSuffix(" app").trim()
        if (filter.isBlank()) return null
        if (recent.any { it.matches(request) }) return ParsedCommand(IntentType.RECENT_CONVERSATIONS, argument = filter)
        if (notifications.any { it.matches(request) }) return ParsedCommand(IntentType.NOTIFICATIONS, argument = filter)
        return null
    }
}
