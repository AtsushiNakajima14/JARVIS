package dev.jarvis.assistant.parser

import java.text.Normalizer
import java.util.Locale

enum class IntentType {
    OPEN_APPLICATION, LIST_APPLICATIONS, SEARCH_APPLICATIONS, RECENT_APPLICATIONS,
    FLASHLIGHT_ON, FLASHLIGHT_OFF, BATTERY_STATUS, VOLUME_UP, VOLUME_DOWN, VOLUME_SET,
    CAMERA, TIMER, CALL, MESSAGE, MUSIC_PLAY, MUSIC_PAUSE, MUSIC_NEXT, MUSIC_PREVIOUS,
    MOST_USED, RARELY_USED, STORAGE, MEMORY_USAGE, DEVICE_INFO, SYSTEM_STATUS,
    REMEMBER, RECALL, SETTINGS, CONFIRM, CANCEL, HELP, UNKNOWN,
    ASSISTANT_IDENTITY, CREATOR, OWNER_IDENTITY, NOTIFICATIONS, RECENT_CONVERSATIONS, UNLOCK_PHONE,
    GREETING, DAILY_BRIEFING
}

data class ParsedCommand(
    val type: IntentType,
    val confidence: Double = 1.0,
    val argument: String = "",
    val value: Long = 0,
    val body: String = ""
)

/** Deterministic, network-free parsing; sensitive actions always require explicit words. */
class CommandParser {
    fun parse(input: String, assistantName: String = "Jarvis"): ParsedCommand {
        val raw = input.trim().take(2000)
        val originalCommand = stripAssistantPrefix(raw, assistantName)
        val text = normalize(originalCommand)
        fun command(type: IntentType, argument: String = "", value: Long = 0, body: String = "") =
            ParsedCommand(type, argument = argument, value = value, body = body)
        if (text in setOf("yes", "confirm", "confirm it", "yes please", "oo", "sige", "oo sige")) return command(IntentType.CONFIRM)
        if (text in setOf("no", "cancel", "stop", "never mind", "hindi", "ayaw", "huwag")) return command(IntentType.CANCEL)
        if (text.isBlank() && Regex("^(?:hi|hello|hey)\\b").containsMatchIn(normalize(raw))) return command(IntentType.GREETING)
        if (text.isBlank() || text in setOf("help", "what can you do", "tulong")) return command(IntentType.HELP)
        Regex("^(?:remember(?: that)?|tandaan(?: mo)?(?: na)?)\\s+(.+)$").find(text)?.let {
            return command(IntentType.REMEMBER, it.groupValues[1])
        }
        // Personal text and contact names are data, even when they contain device-command words.
        Regex("^(?:call|dial|tawagan(?: mo)?|tawagi)\\s+(.+)$").find(text)?.let { return command(IntentType.CALL, it.groupValues[1]) }
        Regex("^(?:send (?:a )?(?:message|text) to|message|text|i-text|itext)\\s+(.+?)(?:\\s+(?:saying|that says|na)\\s+|:\\s*)(.+)$", RegexOption.IGNORE_CASE).find(originalCommand)?.let {
            return command(IntentType.MESSAGE, normalize(it.groupValues[1]), body = it.groupValues[2])
        }
        Regex("^(?:send (?:a )?(?:message|text) to|message|text|i-text|itext)\\s+(.+)$").find(text)?.let {
            return command(IntentType.MESSAGE, it.groupValues[1])
        }
        PersonalCommandParser.parse(text)?.let { return it }
        val opening = Regex("^(open(?: up)?|launch|start|run|bring up|take me to|go to|i want to (?:open|launch|use|start|run)|i(?: would|['’]d) like to (?:open|launch|use|start|run)|let['’]s (?:open|launch|use)|buksan(?: mo)?(?: ang)?|ablihi(?: ang)?|abrihi(?: ang)?)\\s+(.+)$").find(text)
        if (opening != null) {
            val address = opening.groupValues[1]
            val target = opening.groupValues[2].removeSuffix(" please")
            val startingTimer = address.substringAfterLast(' ') in setOf("start", "run") &&
                Regex("^(?:(?:a|the) )?(?:timer\\b|alarm in\\b)").containsMatchIn(target)
            val enablingTorch = address.startsWith("buksan") && target in setOf("flashlight", "torch", "senter")
            if (!startingTimer && !enablingTorch) return when (target) {
                "camera" -> command(IntentType.CAMERA)
                "settings" -> command(IntentType.SETTINGS)
                else -> command(IntentType.OPEN_APPLICATION, target)
            }
        }
        if (text.contains("flashlight") || text.contains("torch") || text.contains("flash light") || text.contains("senter")) {
            val off = Regex("\\b(off|disable|patayin|i-off|palonga)\\b").containsMatchIn(text)
            val on = Regex("\\b(on|enable|buksan|i-on|pasiga)\\b").containsMatchIn(text)
            return when { off -> command(IntentType.FLASHLIGHT_OFF); on -> command(IntentType.FLASHLIGHT_ON); else -> ParsedCommand(IntentType.UNKNOWN, 0.0) }
        }
        if (Regex("\\b(battery|batteries|baterya|batery|batterie|charge percentage)\\b").containsMatchIn(text) ||
            Regex("\\bbuttery\\s+(percentage|percent|level|status)\\b").containsMatchIn(text)) return command(IntentType.BATTERY_STATUS)
        if (Regex("\\b(timer|alarm in)\\b").containsMatchIn(text)) {
            val seconds = durationSeconds(text)
            return if (seconds in 1..86400) command(IntentType.TIMER, value = seconds) else ParsedCommand(IntentType.TIMER, 0.4)
        }
        if (text.contains("volume") || text.contains("lakas ng tunog")) {
            val n = Regex("\\b([0-9]{1,6})\\b\\s*(?:percent|%)?").find(text)?.groupValues?.get(1)?.toLongOrNull()
            return when {
                n != null && n in 0..100 -> command(IntentType.VOLUME_SET, value = n)
                n != null -> ParsedCommand(IntentType.UNKNOWN, 0.0)
                Regex("\\b(up|increase|louder|lakasan|taasan)\\b").containsMatchIn(text) -> command(IntentType.VOLUME_UP)
                Regex("\\b(down|decrease|lower|quieter|hinaan|babaan)\\b").containsMatchIn(text) -> command(IntentType.VOLUME_DOWN)
                text.contains("mute") -> command(IntentType.VOLUME_SET, value = 0)
                else -> ParsedCommand(IntentType.UNKNOWN, 0.0)
            }
        }
        if (text in setOf("pause", "pause music", "pause the music", "stop music")) return command(IntentType.MUSIC_PAUSE)
        if (text in setOf("play", "play music", "resume music", "resume")) return command(IntentType.MUSIC_PLAY)
        if (text in setOf("next", "next song", "next track", "skip song")) return command(IntentType.MUSIC_NEXT)
        if (text in setOf("previous", "previous song", "previous track")) return command(IntentType.MUSIC_PREVIOUS)
        if (text.contains("most") && (text.contains("use") || text.contains("used"))) return command(IntentType.MOST_USED)
        if (text.contains("rarely") || text.contains("least used")) return command(IntentType.RARELY_USED)
        if (text.contains("storage") || text.contains("disk space")) return command(IntentType.STORAGE)
        if (Regex("\\b(memory usage|ram)\\b").containsMatchIn(text)) return command(IntentType.MEMORY_USAGE)
        if (text.contains("device info") || text.contains("phone info")) return command(IntentType.DEVICE_INFO)
        if (text.contains("system status") || text.contains("network status")) return command(IntentType.SYSTEM_STATUS)
        if ((text.contains("recent") || text.contains("newly")) && (text.contains("app") || text.contains("install"))) return command(IntentType.RECENT_APPLICATIONS)
        if (text.contains("apps") || text.contains("applications")) {
            val category = listOf("editing", "development", "programming", "social", "education", "productivity", "entertainment", "games").firstOrNull { text.contains(it) }
            return if (category != null) command(IntentType.SEARCH_APPLICATIONS, category) else command(IntentType.LIST_APPLICATIONS)
        }
        if (text in setOf("open camera", "camera", "buksan ang camera")) return command(IntentType.CAMERA)
        if (text in setOf("settings", "open settings", "show settings")) return command(IntentType.SETTINGS)
        if (text.startsWith("how ") || text.startsWith("what ") || text.startsWith("recall ") || text.contains("remember")) return command(IntentType.RECALL, text)
        return ParsedCommand(IntentType.UNKNOWN, 0.0, text)
    }

    fun durationSeconds(text: String): Long {
        if (Regex("-\\s*[0-9]|[0-9]+\\.[0-9]+").containsMatchIn(text)) return 0
        val words = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "sixty" to 60, "isa" to 1, "dalawa" to 2, "lima" to 5, "napulo" to 10)
        val pattern = Regex("\\b([0-9]{1,6}|${words.keys.joinToString("|")})\\s*(hours?|hrs?|oras|minutes?|mins?|minuto|seconds?|secs?|segundo)\\b")
        return pattern.findAll(text).sumOf { m ->
            val count = m.groupValues[1].toLongOrNull() ?: words.getValue(m.groupValues[1]).toLong()
            count * when { m.groupValues[2].startsWith("h") || m.groupValues[2] == "oras" -> 3600; m.groupValues[2].startsWith("m") -> 60; else -> 1 }
        }
    }
}

/** Strip only leading address/politeness, preserving message contents and case. */
fun stripAssistantPrefix(input: String, assistantName: String = "Jarvis"): String {
    val names = setOf(assistantName, "Jarvis").filter { it.isNotBlank() }.joinToString("|") { Regex.escape(it) }
    val wake = Regex("^(?:(?:hey|hi|hello|ok|okay)\\s+)?(?:$names)\\b[\\s,.:;!?…-]*", RegexOption.IGNORE_CASE)
    val polite = Regex("^(?:please\\s+|(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?)", RegexOption.IGNORE_CASE)
    var text = input.trim().take(2000)
    repeat(2) { text = text.replaceFirst(wake, "").replaceFirst(polite, "").trim() }
    return text
}

fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
    .replace(Regex("[?!.,]+$"), "").replace(Regex("\\s+"), " ").trim()

object FuzzyMatcher {
    fun score(query: String, candidate: String): Double {
        val a = normalize(query); val b = normalize(candidate)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (b.startsWith("$a ")) return 0.93
        if (a.length < 4) return 0.0
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val row = IntArray(b.length + 1); row[0] = i + 1
            for (j in b.indices) row[j + 1] = minOf(row[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = row
        }
        return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
    }
}

