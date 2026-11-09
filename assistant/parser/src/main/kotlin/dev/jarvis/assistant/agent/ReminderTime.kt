package dev.jarvis.assistant.agent

import dev.jarvis.assistant.parser.CommandParser
import dev.jarvis.assistant.parser.normalize
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Deliberately rejects ambiguous bare hours instead of guessing morning or evening. */
internal class ReminderTime(private val zoneId: ZoneId) {
    data class Request(val title: String, val timeText: String)

    fun split(text: String): Request {
        val raw = text.trim().removeSuffix(" please")
        val temporalFirst = Regex("^((?:in|after|at|today|tomorrow)\\s+.+?)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)
        if (temporalFirst != null) return Request(temporalFirst.groupValues[2], temporalFirst.groupValues[1])
        val temporalLast = Regex("^(.+?)\\s+((?:in|after)\\s+(?:[0-9]+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|sixty)\\s+(?:seconds?|minutes?|hours?|days?).*|(?:today|tomorrow)(?:\\s+at\\s+.+)?|at\\s+[0-9].*)$", RegexOption.IGNORE_CASE).matchEntire(raw)
        return if (temporalLast != null) Request(title(temporalLast.groupValues[1]), temporalLast.groupValues[2])
        else if (Regex("^(?:in|after|at|today|tomorrow)\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)) Request("", raw)
        else Request(title(raw), "")
    }

    private fun title(raw: String): String = raw.replaceFirst(Regex("^(?:to|for)\\s+", RegexOption.IGNORE_CASE), "")
        .takeUnless { normalize(it) in setOf("to", "for") }.orEmpty()

    fun parse(text: String, nowMillis: Long): Long? {
        val query = normalize(text)
        if (query.startsWith("in ") || query.startsWith("after ")) {
            val duration = query.substringAfter(' ')
            val days = Regex("^([0-9]{1,3}|a|an|one|two|three|four|five|six|seven) days?$").matchEntire(duration)?.groupValues?.get(1)
            if (days != null) {
                val amount = days.toLongOrNull() ?: mapOf("a" to 1L, "an" to 1L, "one" to 1L, "two" to 2L,
                    "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L, "seven" to 7L).getValue(days)
                return if (amount in 1..365) nowMillis + amount * 86_400_000 else null
            }
            // Reject leftover prose or invalid units; do not silently accept only part of a duration.
            val unit = "(?:[0-9]{1,6}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|sixty)\\s+(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)"
            if (!Regex("^$unit(?:\\s+(?:and\\s+)?$unit)*$").matches(duration)) return null
            val seconds = CommandParser().durationSeconds(duration)
            return if (seconds in 1..31_536_000) nowMillis + seconds * 1000 else null
        }
        val match = Regex("^(?:(today|tomorrow)\\s+)?(?:at\\s+)?([0-9]{1,2})(?::([0-9]{2}))?\\s*(am|pm)?$").matchEntire(query)
            ?: return null
        val day = match.groupValues[1]
        var hour = match.groupValues[2].toInt()
        val minute = match.groupValues[3].ifEmpty { "0" }.toInt()
        val period = match.groupValues[4]
        if (minute !in 0..59 || hour !in 0..23) return null
        if (period.isNotEmpty()) {
            if (hour !in 1..12) return null
            hour = hour % 12 + if (period == "pm") 12 else 0
        } else if (hour in 1..12) return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        var date = now.toLocalDate().plusDays(if (day == "tomorrow") 1 else 0)
        var target = date.atTime(LocalTime.of(hour, minute)).atZone(zoneId)
        if (target.toInstant().toEpochMilli() <= nowMillis) {
            if (day.isNotEmpty()) return null
            date = date.plusDays(1)
            target = date.atTime(LocalTime.of(hour, minute)).atZone(zoneId)
        }
        return target.toInstant().toEpochMilli().takeIf { it > nowMillis }
    }
}
