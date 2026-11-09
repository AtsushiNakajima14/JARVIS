package dev.jarvis.assistant.parser

import dev.jarvis.assistant.agent.AgentSpeechPolicy

/** Alternatives may rescue a clear, unambiguous device command, never a confirmation/contact action. */
class SpeechSelection(private val parser: CommandParser = CommandParser()) {
    fun choose(alternatives: List<String>, assistantName: String = "Jarvis", confirmation: Boolean = false): String {
        val candidates = alternatives.filter { it.isNotBlank() }.map { ClockSpeechCorrection.correct(it, assistantName) }.distinct().take(5)
        val primary = candidates.firstOrNull().orEmpty()
        if (confirmation || AgentSpeechPolicy.preservePrimary(primary, assistantName)) return primary
        val first = parser.parse(primary, assistantName)
        if (first.type != IntentType.TIME_STATUS && Regex("\\b(?:time|thyme)\\b").containsMatchIn(normalize(primary))) return primary
        if (first.type !in setOf(IntentType.UNKNOWN, IntentType.RECALL)) return primary
        val restricted = setOf(IntentType.UNKNOWN, IntentType.RECALL, IntentType.CONFIRM, IntentType.CANCEL,
            IntentType.CALL, IntentType.MESSAGE, IntentType.REMEMBER, IntentType.HELP,
            IntentType.NOTIFICATIONS, IntentType.RECENT_CONVERSATIONS, IntentType.UNLOCK_PHONE, IntentType.DAILY_BRIEFING)
        val known = candidates.drop(1).map { it to parser.parse(it, assistantName) }
            .filter { it.second.confidence >= 0.7 && it.second.type !in restricted }
        val meanings = known.map { it.second }.distinct()
        return if (meanings.size == 1) known.first().first else primary
    }
}

data class NamedApplication(val id: String, val label: String)

object ApplicationMatcher {
    fun find(query: String, apps: List<NamedApplication>): String? {
        if (query.isBlank()) return null
        val exact = apps.filter { normalize(it.label) == normalize(query) || it.id == query }
        if (exact.size == 1) return exact.single().id
        if (exact.size > 1) return null
        val joined = apps.filter { SpokenApplicationNames.canonical(it.label) == SpokenApplicationNames.canonical(query) }
        if (joined.size == 1) return joined.single().id
        if (joined.size > 1) return null
        val withoutGenericSuffix = normalize(query).removeSuffix(" application").removeSuffix(" app")
        if (withoutGenericSuffix != normalize(query) && withoutGenericSuffix.isNotBlank()) {
            // Preserve real names such as WhatsApp or MyApp before removing a generic noun.
            return find(withoutGenericSuffix, apps)
        }
        val ranked = apps.map { app ->
            val words = normalize(app.label).split(Regex("[^a-z0-9]+"))
            val score = if (normalize(query) in words && query.length >= 4) 0.92
                else FuzzyMatcher.score(query, app.label)
            app.id to score
        }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        return best.first.takeIf { best.second >= 0.74 && (ranked.size == 1 || best.second - ranked[1].second >= 0.09) }
    }
}
