package dev.jarvis.assistant.agent

import dev.jarvis.assistant.parser.CommandParser
import dev.jarvis.assistant.parser.FuzzyMatcher
import dev.jarvis.assistant.parser.IntentType
import dev.jarvis.assistant.parser.ParsedCommand
import dev.jarvis.assistant.parser.SpokenApplicationNames
import dev.jarvis.assistant.parser.normalize
import dev.jarvis.assistant.parser.stripAssistantPrefix
import java.time.ZoneId

data class AgentMemory(val id: String, val content: String, val category: String = "important")
data class AgentKnowledge(
    val appLabels: List<String> = emptyList(),
    val preferredChatApp: String? = null,
    val memories: List<AgentMemory> = emptyList(),
    val communicationApps: List<String> = emptyList()
)

/** One action per turn; response can provide a native fallback for an unresolved goal. */
data class AgentDecision(
    val action: ParsedCommand? = null,
    val response: String? = null,
    val awaitingReply: Boolean = false,
    val isPrivate: Boolean = false
)

/**
 * Lightweight goal/slot agent. State is RAM-only, expires after two minutes, and is discarded on reset.
 * Callers reset on screen lock, stop, explicit data deletion and service destruction. A model is not
 * involved in choosing abilities or authorizing actions. Message text and memory contents are data.
 */
class AgentCore(
    private val parser: CommandParser = CommandParser(),
    private val nlu: LocalNlu = LocalNlu(),
    zoneId: ZoneId = ZoneId.systemDefault()
) {
    private val time = ReminderTime(zoneId)
    private var pending: Pending? = null
    private var expiresAt = 0L
    private var lastMemory: String? = null
    private var memoryExpiresAt = 0L

    private sealed interface Pending {
        data class Contact(val mode: Mode?) : Pending
        data class ContactMode(val person: String) : Pending
        data class MessageBody(val person: String) : Pending
        data object DraftRecipient : Pending
        data class DraftPurpose(val person: String) : Pending
        data class DraftReview(val person: String, val body: String) : Pending
        data class DraftContact(val body: String) : Pending
        data class MemoryContent(val prefix: String = "") : Pending
        data object MemoryForget : Pending
        data class ReminderTitle(val whenText: String = "") : Pending
        data class ReminderWhen(val title: String) : Pending
        data object TimerDuration : Pending
        data object CompleteTask : Pending
        data class AppChoice(val labels: List<String>) : Pending
        data class AppConfirmation(val label: String) : Pending
    }
    private enum class Mode { CALL, MESSAGE }

    @Synchronized fun reset() {
        pending = null; expiresAt = 0; lastMemory = null; memoryExpiresAt = 0
    }

    @Synchronized fun hasPendingReply(): Boolean = pending != null

    @Synchronized fun handle(
        input: String,
        assistantName: String = "Jarvis",
        knowledge: AgentKnowledge = AgentKnowledge(),
        nowMillis: Long = System.currentTimeMillis()
    ): AgentDecision {
        if (nowMillis >= expiresAt) pending = null
        if (nowMillis >= memoryExpiresAt) lastMemory = null
        val receivingLiteral = pending is Pending.MessageBody || pending is Pending.DraftPurpose || pending is Pending.MemoryContent
        var raw = if (receivingLiteral) stripWakeOnly(input.take(2000), assistantName)
            else stripAssistantPrefix(input.take(2000), assistantName).trim()
        var text = normalize(raw)
        if (text.isBlank()) return reply("I'm here. What would you like help with?")
        if (text in cancelWords) {
            reset()
            return reply("Okay, I've stopped that. What would you like to do instead?")
        }
        if (!receivingLiteral && (negated.matches(text) || Regex("^(?:don't|do not|never|not)\\b").containsMatchIn(text))) {
            pending = null
            return reply("Okay. I won't do that. What would you like help with?")
        }
        val changedTopic = raw.replaceFirst(Regex("^(?:actually[, ]+|instead[, ]+|new question[: ,]+)", RegexOption.IGNORE_CASE), "")
        if (changedTopic != raw && isNewRequest(normalize(changedTopic))) {
            pending = null
            raw = changedTopic
            text = normalize(raw)
        }
        // A fresh, explicit request supersedes a pending slot. Contents being dictated into a
        // message/draft are an exception: their verbs must never become device actions.
        val state = pending
        val acceptsContent = receivingLiteral || state is Pending.ReminderTitle || state is Pending.CompleteTask
        if (state != null && (acceptsContent || isExpectedReply(state, text) || !isNewRequest(text))) {
            return continueConversation(state, raw, text, nowMillis)
        }
        pending = null

        if (text in setOf("and storage", "what about storage", "how about storage", "and my storage"))
            return action(IntentType.STORAGE)
        if (text in setOf("and ram", "what about ram", "how about ram", "and memory", "what about memory"))
            return action(IntentType.MEMORY_USAGE)
        if (text in setOf("and battery", "what about battery", "how about my battery"))
            return action(IntentType.BATTERY_STATUS)

        // Keep user text intact before looking for keywords such as "delete", "battery" or "call".
        if (Regex("^(?:remember(?: that)?|tandaan(?: mo)?(?: na)?)\\s+").containsMatchIn(text)) {
            if (text in setOf("remember my name", "remember my preferences", "remember my important projects", "remember something", "remember this", "remember that"))
                return ask(Pending.MemoryContent(when (text) {
                    "remember my name" -> "my name is "
                    "remember my important projects" -> "my important projects are "
                    else -> ""
                }),
                    if (text == "remember my name") "What name would you like me to remember?" else "What would you like me to remember?", nowMillis)
            val command = parser.parse(raw, assistantName)
            if (command.type == IntentType.REMEMBER) return remember(raw.replaceFirst(
                Regex("^(?:remember(?: that)?|tandaan(?: mo)?(?: na)?)\\s+", RegexOption.IGNORE_CASE), ""), nowMillis)
        }
        val direct = parser.parse(raw, assistantName)
        if (direct.type in setOf(IntentType.TIME_STATUS, IntentType.DATE_STATUS)) return action(direct)
        if (direct.type == IntentType.CALL && Regex("^(?:call|dial|tawagan|tawagi)\\b").containsMatchIn(text))
            return action(direct, isPrivate = true)
        if (direct.type == IntentType.MESSAGE && direct.body.isNotBlank()) return action(direct, isPrivate = true)

        if (negated.matches(text) || Regex("^(?:don't|do not|never|not)\\b").containsMatchIn(text))
            return reply("Okay. I won't do that. What would you like help with?")
        if (Regex("^(?:how (?:do|does|can|would|should)|why|explain|tell me how)\\b").containsMatchIn(text))
            return explain(text)

        if (text in setOf("remember", "remember something", "remember my name", "save a memory", "remember this", "remember that"))
            return ask(Pending.MemoryContent(), "What would you like me to remember?", nowMillis)
        if (text in setOf("show my memories", "show memories", "list my memories", "what do you remember about me", "what have you remembered", "my memories"))
            return action(IntentType.MEMORY_LIST, isPrivate = true)
        if (Regex("^(?:forget|remove (?:a |the |my )?memory|delete (?:a |the |my )?memory)\\b").containsMatchIn(text)) {
            val query = raw.replaceFirst(Regex("^(?:forget|(?:remove|delete) (?:a |the |my )?memory)\\s*", RegexOption.IGNORE_CASE), "")
                .removePrefix("about ").trim()
            if (normalize(query) in setOf("this", "that", "it", "this memory", "that memory")) {
                val remembered = lastMemory
                return if (remembered != null) { lastMemory = null; action(IntentType.MEMORY_FORGET, remembered, isPrivate = true) }
                else ask(Pending.MemoryForget, "Which saved memory would you like me to forget?", nowMillis)
            }
            if (query.isBlank() || normalize(query) in setOf("everything", "all", "all memories"))
                return ask(Pending.MemoryForget, "Which memory should I forget? You can review your saved memories first.", nowMillis)
            return action(IntentType.MEMORY_FORGET, query, isPrivate = true)
        }

        val reminder = Regex("^(?:remind me|set (?:a )?reminder|create (?:a )?reminder|add (?:a )?reminder)(?:\\s+(.*))?$", RegexOption.IGNORE_CASE).matchEntire(raw)
        if (reminder != null) return reminderRequest(reminder.groupValues[1], nowMillis)
        if (text in setOf("show my reminders", "list my reminders", "my reminders", "show my tasks", "list my tasks", "what is on my list", "what's on my list", "what should i do today", "what do i have to do today"))
            return action(IntentType.REMINDER_LIST, isPrivate = true)
        val complete = Regex("^(?:complete (?:(?:the|my) )?(?:task|reminder)|finish (?:(?:the|my) )?(?:task|reminder)|mark (?:(?:the|my) )?(?:task|reminder))\\s*(.*)$", RegexOption.IGNORE_CASE).matchEntire(raw)
        if (complete != null) {
            val title = complete.groupValues[1].replace(Regex(" (?:as )?(?:done|complete|finished)$", RegexOption.IGNORE_CASE), "").trim()
            return if (title.isBlank()) ask(Pending.CompleteTask, "Which reminder did you finish?", nowMillis)
            else action(IntentType.REMINDER_COMPLETE, title, isPrivate = true)
        }
        Regex("^mark (.+?) (?:as )?(?:done|complete|finished)$", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            return action(IntentType.REMINDER_COMPLETE, it.groupValues[1], isPrivate = true)
        }
        if (Regex("^(?:(?:show|find|list|check) (?:me )?(?:my |the )?)?(?:recent|latest) (?:files|documents|downloads)$").matches(text))
            return action(IntentType.RECENT_FILES, isPrivate = true)
        Regex("^(?:find|search for|look for|search) (?:(?:my|the|a) )?(?:files?|documents?|downloads?)(?: (?:called|named|about|for))?\\s*(.*)$", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            return action(IntentType.FILE_SEARCH, it.groupValues[1], isPrivate = true)
        }
        Regex("^(?:find|search for|look for) (.+\\.(?:pdf|txt|docx?|xlsx?|pptx?|csv|zip|jpg|png))$", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            return action(IntentType.FILE_SEARCH, it.groupValues[1], isPrivate = true)
        }

        if (text in setOf("call", "dial", "make a call", "make a phone call"))
            return ask(Pending.Contact(Mode.CALL), "Who would you like to call?", nowMillis)
        if (text in setOf("send a message", "send a text", "message someone", "text someone"))
            return ask(Pending.Contact(Mode.MESSAGE), "Who would you like to message?", nowMillis)
        if (direct.type == IntentType.MESSAGE && direct.body.isBlank())
            return ask(Pending.MessageBody(direct.argument), "What would you like to say to ${direct.argument}?", nowMillis)

        Regex("^(?:(?:i (?:need|want|would like) to|help me) )?(?:talk|speak|reach out|get in touch) (?:to|with) (.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            val person = cleanPerson(it.groupValues[1])
            return if (!isPerson(person) || normalize(person) in genericPeople)
                ask(Pending.Contact(null), "Who would you like to contact?", nowMillis)
            else ask(Pending.ContactMode(person), "Would you like to call $person or write a message?", nowMillis)
        }
        Regex("^(?:(?:i (?:need|want|would like) to|help me) )?(?:contact|reach) (.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            val person = cleanPerson(it.groupValues[1])
            return if (!isPerson(person) || normalize(person) in genericPeople)
                ask(Pending.Contact(null), "Who would you like to contact?", nowMillis)
            else ask(Pending.ContactMode(person), "Would you like to call $person or write a message?", nowMillis)
        }
        Regex("^(?:help me )?(?:write|draft|compose) (?:(?:a |the )?(?:message|text|note|something)) (?:to|for) (.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            return ask(Pending.DraftPurpose(cleanPerson(it.groupValues[1])), "What would you like to tell ${it.groupValues[1]}?", nowMillis)
        }

        if (text in setOf("i want to check my messages", "i want to see my messages", "check my messages", "show my messages", "let me check my messages", "take me to my messages", "i want to chat", "open my chat app"))
            return chooseCommunicationApp(knowledge, nowMillis)

        // Synonym similarity identifies goals that are safe to clarify or inspect. It does not
        // invent recipients, reminder times, saved facts or perform approximate private actions.
        when (nlu.classify(text).goal) {
            LocalNlu.Goal.COMMUNICATE -> return ask(Pending.Contact(null), "Who would you like to contact?", nowMillis)
            LocalNlu.Goal.WRITE -> return ask(Pending.DraftRecipient, "Who is it for?", nowMillis)
            LocalNlu.Goal.DEVICE_HEALTH -> return action(IntentType.DEVICE_HEALTH)
            LocalNlu.Goal.PREPARE_PHONE -> return action(IntentType.PREPARE_PHONE, isPrivate = true)
            LocalNlu.Goal.TODAY -> return action(IntentType.REMINDER_LIST, isPrivate = true)
            LocalNlu.Goal.NONE -> Unit
        }

        val showing = Regex("^(?:show(?: me)?|bring me|let me (?:see|use)|i (?:want|need) to (?:use|check)|i would like to use) (.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)
        if (showing != null && !Regex("\\b(?:notifications?|memories|tasks|reminders|files|documents)\\b").containsMatchIn(text)) {
            resolveApp(showing.groupValues[1], knowledge, nowMillis)?.let { return it }
        }
        if (direct.type == IntentType.OPEN_APPLICATION) {
            resolveApp(direct.argument, knowledge, nowMillis)?.let { return it }
            if (knowledge.appLabels.isNotEmpty()) return ask(Pending.AppChoice(knowledge.appLabels.distinct().take(1000)),
                "I didn't catch which app you meant. Which app would you like to open?", nowMillis)
            return action(direct)
        }
        if (text in setOf("it's too dark", "it is too dark", "i need some light", "give me some light"))
            return action(IntentType.FLASHLIGHT_ON)
        if (text in setOf("it's too loud", "it is too loud", "make it quieter", "turn it down"))
            return action(IntentType.VOLUME_DOWN)
        if (text in setOf("i can't hear", "i cannot hear", "make it louder", "turn it up"))
            return action(IntentType.VOLUME_UP)

        if (direct.type == IntentType.CONFIRM) return action(direct)
        if (direct.type == IntentType.HELP) return reply("Tell me what you need. I can help you contact someone, write a message, check your phone, find files, remember preferences, or plan reminders.")
        if (direct.type == IntentType.TIMER && direct.value == 0L && explicitAction.containsMatchIn(text))
            return ask(Pending.TimerDuration, "How long should the timer be?", nowMillis)
        if (direct.type in sideEffects && !explicitAction.containsMatchIn(text)) return fallback(raw)
        if (direct.type !in setOf(IntentType.UNKNOWN, IntentType.RECALL) && direct.confidence >= .7) return action(direct,
            isPrivate = direct.type in privateIntents)
        if (direct.type == IntentType.RECALL) return AgentDecision(action = direct, response = nativeFallback, isPrivate = true)
        if (direct.type == IntentType.UNKNOWN) clarifyHeardApplication(text, knowledge, nowMillis)?.let { return it }
        return fallback(raw)
    }

    private fun continueConversation(state: Pending, raw: String, text: String, now: Long): AgentDecision {
        when (state) {
            is Pending.Contact -> {
                val person = cleanPerson(raw)
                if (!isPerson(person)) return ask(state, "Who would you like to contact? Use their saved contact name.", now)
                return when (state.mode) {
                    Mode.CALL -> finish(ParsedCommand(IntentType.CALL, argument = person))
                    Mode.MESSAGE -> ask(Pending.MessageBody(person), "What would you like to say to $person?", now)
                    null -> ask(Pending.ContactMode(person), "Would you like to call $person or write a message?", now)
                }
            }
            is Pending.ContactMode -> return when {
                text in setOf("call", "a call", "call them", "phone", "phone call", "call please") -> finish(ParsedCommand(IntentType.CALL, argument = state.person))
                text in setOf("message", "text", "write", "write a message", "send a message", "a message", "message them") -> ask(Pending.MessageBody(state.person), "What would you like to say to ${state.person}?", now)
                else -> ask(state, "Would you prefer a phone call or a message to ${state.person}?", now)
            }
            is Pending.MessageBody -> {
                val body = raw.replaceFirst(Regex("^(?:say|saying|tell (?:him|her|them))\\s+", RegexOption.IGNORE_CASE), "")
                return finish(ParsedCommand(IntentType.MESSAGE, argument = state.person, body = body.take(1500)))
            }
            Pending.DraftRecipient -> {
                val person = cleanPerson(raw)
                if (!isPerson(person)) return ask(state, "Who is the message for?", now)
                return ask(Pending.DraftPurpose(person), "What would you like to tell $person?", now)
            }
            is Pending.DraftPurpose -> {
                val body = localDraft(raw)
                return ask(Pending.DraftReview(state.person, body), "Here's a draft: $body Would you like to open it in your messaging app?", now)
            }
            is Pending.DraftReview -> {
                if (text in yesWords || text in setOf("open it", "open the draft", "open my messaging app", "send it")) {
                    if (normalize(state.person) in genericPeople)
                        return ask(Pending.DraftContact(state.body), "What is your friend's saved contact name?", now)
                    return finish(ParsedCommand(IntentType.MESSAGE, argument = state.person, body = state.body))
                }
                if (text in setOf("make it shorter", "shorter", "keep it short")) {
                    val shorter = state.body.substringBefore(". ").take(200).trim().let { if (it.endsWith('.')) it else "$it." }
                    return ask(state.copy(body = shorter), "Here's the shorter draft: $shorter Open it in your messaging app?", now)
                }
                if (text.startsWith("change it to ") || text.startsWith("instead say ")) {
                    val body = raw.substringAfter(" ").substringAfter(" ").removePrefix("to ").take(1500)
                    return ask(state.copy(body = body), "Updated draft: $body Open it in your messaging app?", now)
                }
                return ask(state, "You can change the draft, ask me to make it shorter, or open it in your messaging app.", now)
            }
            is Pending.DraftContact -> {
                val person = cleanPerson(raw)
                if (!isPerson(person) || normalize(person) in genericPeople) return ask(state, "What is their full saved contact name?", now)
                return finish(ParsedCommand(IntentType.MESSAGE, argument = person, body = state.body))
            }
            is Pending.MemoryContent -> return remember(if (state.prefix.isNotEmpty() && !text.startsWith(state.prefix)) state.prefix + raw else raw, now)
            Pending.MemoryForget -> {
                if (text in setOf("all", "everything", "all memories", "this", "that"))
                    return ask(state, "Tell me which saved memory to remove, or open Memory to review and delete entries.", now)
                pending = null
                return action(IntentType.MEMORY_FORGET, raw.removePrefix("the one about "), isPrivate = true)
            }
            is Pending.ReminderTitle -> {
                val request = time.split(raw)
                val title = request.title
                if (title.isBlank()) return ask(state.copy(whenText = request.timeText.ifEmpty { state.whenText }), "What should I remind you about?", now)
                val whenText = request.timeText.ifEmpty { state.whenText }
                return finishReminder(title, whenText, now)
            }
            is Pending.ReminderWhen -> return finishReminder(state.title, raw, now)
            Pending.TimerDuration -> {
                val duration = parser.durationSeconds(text)
                if (duration !in 1..86_400) return ask(state, "How long should the timer be? Tell me a duration up to 24 hours.", now)
                pending = null
                return action(IntentType.TIMER, value = duration)
            }
            Pending.CompleteTask -> {
                if (text in setOf("it", "that", "this", "all", "everything")) return ask(state, "Which reminder did you finish? Tell me its title.", now)
                pending = null
                return action(IntentType.REMINDER_COMPLETE, raw, isPrivate = true)
            }
            is Pending.AppChoice -> {
                val query = text.removePrefix("use ").removePrefix("open ")
                val exact = state.labels.singleOrNull { normalize(it) == query }
                    ?: state.labels.singleOrNull { SpokenApplicationNames.canonical(it) == SpokenApplicationNames.canonical(query) }
                if (exact != null) { pending = null; return action(IntentType.OPEN_APPLICATION, exact) }
                return ask(state, "Which app would you like: ${state.labels.take(4).joinToString()}?", now)
            }
            is Pending.AppConfirmation -> {
                if (text in yesWords || text == normalize(state.label)) { pending = null; return action(IntentType.OPEN_APPLICATION, state.label) }
                return ask(state, "Did you mean ${state.label}? Say yes, or tell me the app's name.", now)
            }
        }
    }

    private fun reminderRequest(request: String, now: Long): AgentDecision {
        if (request.isBlank()) return ask(Pending.ReminderTitle(), "What should I remind you about?", now)
        val parsed = time.split(request)
        if (parsed.title.isBlank()) return ask(Pending.ReminderTitle(parsed.timeText), "What should I remind you about?", now)
        return finishReminder(parsed.title, parsed.timeText, now)
    }

    private fun finishReminder(title: String, whenText: String, now: Long): AgentDecision {
        val epoch = time.parse(whenText, now)
        if (epoch == null) return ask(Pending.ReminderWhen(title),
            "When should I remind you to $title? You can say in ten minutes, or tomorrow at 9 AM.", now)
        pending = null
        return action(IntentType.REMINDER_CREATE, title.take(240), value = epoch, isPrivate = true)
    }

    private fun chooseCommunicationApp(knowledge: AgentKnowledge, now: Long): AgentDecision {
        val installed = knowledge.appLabels.distinctBy(::normalize)
        val preferred = knowledge.preferredChatApp?.let { preference -> installed.singleOrNull { normalize(it) == normalize(preference) } }
        if (preferred != null) return action(IntentType.OPEN_APPLICATION, preferred)
        val candidates = knowledge.communicationApps.filter { label -> installed.any { normalize(it) == normalize(label) } }
            .ifEmpty { installed.filter { label -> Regex("\\b(messenger|messages|whatsapp|telegram|signal|discord|instagram|viber|wechat)\\b").containsMatchIn(normalize(label)) } }
            .distinctBy(::normalize)
        return when (candidates.size) {
            0 -> reply("Which app do you use for messages? Tell me to open its name, or allow app discovery in JARVIS.")
            1 -> action(IntentType.OPEN_APPLICATION, candidates.single())
            else -> ask(Pending.AppChoice(candidates.take(12)), "Which app would you like to use: ${candidates.take(4).joinToString()}?", now)
        }
    }

    private fun resolveApp(query: String, knowledge: AgentKnowledge, now: Long): AgentDecision? {
        val target = query.removeSuffix(" app").trim()
        val apps = knowledge.appLabels.distinctBy(::normalize)
        apps.singleOrNull { normalize(it) == normalize(target) }?.let { return action(IntentType.OPEN_APPLICATION, it) }
        val targetKey = SpokenApplicationNames.canonical(target)
        val canonical = if (targetKey.isBlank()) emptyList() else apps.filter { SpokenApplicationNames.canonical(it) == targetKey }
        if (canonical.size == 1) return action(IntentType.OPEN_APPLICATION, canonical.single())
        if (canonical.size > 1) return ask(Pending.AppChoice(canonical.take(5)),
            "Which app did you mean: ${canonical.take(5).joinToString()}?", now)
        if (target.length !in 3..120) return null
        val candidates = apps.take(1000).filter { it.length <= 120 }.map { it to FuzzyMatcher.score(target, it) }
            .filter { it.second >= .74 }.sortedByDescending { it.second }
        if (candidates.isEmpty()) return null
        if (candidates.size > 1 && candidates[0].second - candidates[1].second < .12)
            return ask(Pending.AppChoice(candidates.take(5).map { it.first }), "Which app did you mean: ${candidates.take(5).joinToString { it.first }}?", now)
        return ask(Pending.AppConfirmation(candidates.first().first), "Did you mean ${candidates.first().first}?", now)
    }

    /** Unclear verbs are not authorization. A short fragment may suggest one known app, then ask. */
    private fun clarifyHeardApplication(text: String, knowledge: AgentKnowledge, now: Long): AgentDecision? {
        val words = text.split(Regex("[^\\p{L}\\p{N}']+")).filter(String::isNotBlank)
        if (words.size !in 1..8) return null
        val suffixKeys = (1..minOf(6, words.size)).mapNotNull { count ->
            val prefix = words.dropLast(count)
            if (prefix.size > 2 || prefix.any { it in fragmentContextWords }) null
            else SpokenApplicationNames.canonical(words.takeLast(count).joinToString(" ")).takeIf { it.length >= 5 }
        }.toSet()
        if (suffixKeys.isEmpty()) return null
        val apps = knowledge.appLabels.asSequence().filter { it.isNotBlank() && it.length <= 120 }.take(1000).distinctBy(::normalize)
        val candidates = apps.mapNotNull { label ->
            val key = SpokenApplicationNames.canonical(label)
            if (key !in suffixKeys) null else label to key.length
        }.toList()
        val longest = candidates.maxOfOrNull { it.second } ?: return null
        val labels = candidates.filter { it.second == longest }.map { it.first }
        return if (labels.size == 1) ask(Pending.AppConfirmation(labels.single()),
            "I heard ${labels.single()}, but I'm not sure what you want. Did you mean open ${labels.single()}?", now)
        else ask(Pending.AppChoice(labels.take(5)), "Which app did you mean: ${labels.take(5).joinToString()}?", now)
    }

    private fun remember(content: String, now: Long): AgentDecision {
        pending = null
        lastMemory = content.take(1500)
        memoryExpiresAt = now + CONTEXT_TTL
        return action(IntentType.REMEMBER, content.take(1500), isPrivate = true)
    }

    private fun explain(text: String): AgentDecision = when {
        "storage" in text || "slow" in text -> reply("You can review storage in Android Settings, remove files you no longer need, and restart the phone. I can check available storage and memory if you'd like.")
        "notification" in text -> reply("In JARVIS Settings, enable notification access and choose whether I may read previews. I can only read notifications Android shares with me.")
        "reminder" in text -> reply("Tell me what you want to remember and when, and I'll create a local reminder. You can ask what is on your list later.")
        "memory" in text || "remember" in text -> reply("Tell me what to remember. I only save information when you ask, and you can review or forget it later.")
        else -> fallback(text)
    }

    private fun localDraft(raw: String): String {
        val text = raw.replaceFirst(Regex("^(?:tell (?:him|her|them)(?: that)?|say(?: that)?)\\s+", RegexOption.IGNORE_CASE), "").take(1200).trim()
        if (text.isEmpty()) return "Hello!"
        val body = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return if (body.last() in setOf('.', '!', '?')) body else "$body."
    }

    private fun cleanPerson(raw: String) = raw.replaceFirst(Regex("^(?:to |my contact |contact |call |message |text )", RegexOption.IGNORE_CASE), "").trim().take(120)
    private fun stripWakeOnly(raw: String, assistantName: String): String {
        val names = setOf(assistantName, "Jarvis").filter(String::isNotBlank).joinToString("|") { Regex.escape(it) }
        // A bare assistant name may be part of the dictated fact or message ("JARVIS is my project").
        return raw.trim().replaceFirst(Regex("^(?:(?:hey|hi|hello|ok|okay)\\s+(?:$names)\\b[\\s,.:;!?-]*|(?:$names)\\b\\s*[,.:;!?-]+\\s*)", RegexOption.IGNORE_CASE), "").trim()
    }
    private fun isPerson(person: String) = person.isNotBlank() && normalize(person) !in setOf("yes", "no", "someone", "somebody", "anyone", "i don't know", "i do not know", "a person")
    private fun isExpectedReply(state: Pending, text: String): Boolean = when (state) {
        is Pending.DraftReview -> text in setOf("open it", "open the draft", "open my messaging app", "send it")
        is Pending.ContactMode -> text in setOf("call them", "message them", "send a message", "write a message", "call please")
        is Pending.AppChoice -> state.labels.any { normalize(it) == text.removePrefix("use ").removePrefix("open ") }
        else -> false
    }
    private fun isNewRequest(text: String): Boolean = Regex("^(?:open|launch|remember|forget|remind me|set a reminder|show my|list my|read my|check my|call |dial |send a message to |text .+ saying |message .+ saying |what |who |how |prepare my|my phone|help me|i need to|i want to)\\b").containsMatchIn(text)

    private fun finish(command: ParsedCommand): AgentDecision { pending = null; return action(command, isPrivate = true) }
    private fun ask(next: Pending, question: String, now: Long): AgentDecision {
        pending = next; expiresAt = now + CONTEXT_TTL
        return AgentDecision(response = question, awaitingReply = true, isPrivate = next !is Pending.AppChoice && next !is Pending.AppConfirmation)
    }
    private fun reply(text: String) = AgentDecision(response = text)
    private fun fallback(raw: String) = AgentDecision(action = ParsedCommand(IntentType.UNKNOWN, confidence = 0.0, argument = raw), response = nativeFallback)
    private fun action(type: IntentType, argument: String = "", value: Long = 0, isPrivate: Boolean = false) = action(ParsedCommand(type, argument = argument, value = value), isPrivate)
    private fun action(command: ParsedCommand, isPrivate: Boolean = false) = AgentDecision(action = command, isPrivate = isPrivate)

    companion object {
        const val CONTEXT_TTL = 120_000L
        private val cancelWords = setOf("cancel", "stop", "never mind", "nevermind", "forget it", "no", "no thanks", "hindi", "ayaw", "huwag")
        private val yesWords = setOf("yes", "yes please", "confirm", "sure", "okay", "ok", "go ahead", "oo", "sige")
        private val genericPeople = setOf("my friend", "a friend", "friend", "my family", "family", "someone", "my colleague", "a colleague")
        private val fragmentContextWords = setOf("i", "my", "we", "our", "they", "he", "she", "it", "this", "that", "there",
            "is", "are", "was", "what", "why", "how", "when", "who", "where", "which", "whose", "do", "does", "did",
            "can", "could", "would", "will", "should", "not", "dont", "don't", "never", "no", "stop", "cancel",
            "remember", "forget", "save", "store", "call", "dial", "message", "text", "send", "read", "reply",
            "draft", "write", "remind", "reminder", "task", "find", "search", "file", "document", "open", "launch",
            "show", "use", "check", "take", "go", "help", "to", "like", "love", "hate", "prefer", "need", "want")
        private val negated = Regex("^(?:i (?:don't|do not) want to|please (?:don't|do not)|don't|do not|never) .+$")
        private const val nativeFallback = "I can help with your phone, messages, files, memories, and reminders. Tell me a little more about what you'd like to do."
        private val sideEffects = setOf(IntentType.FLASHLIGHT_ON, IntentType.FLASHLIGHT_OFF, IntentType.VOLUME_UP,
            IntentType.VOLUME_DOWN, IntentType.VOLUME_SET, IntentType.TIMER)
        private val explicitAction = Regex("^(?:(?:i (?:want|need) to|let's) )?(?:turn|switch|set|start|run|make|increase|decrease|lower|raise|mute|unmute|flashlight|torch|volume|timer|buksan|patayin|lakasan|hinaan|taasan|babaan)\\b")
        private val privateIntents = setOf(IntentType.NOTIFICATIONS, IntentType.RECENT_CONVERSATIONS, IntentType.DAILY_BRIEFING,
            IntentType.CALL, IntentType.MESSAGE, IntentType.REMEMBER, IntentType.RECALL)
    }
}
