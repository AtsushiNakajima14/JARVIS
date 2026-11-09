package dev.jarvis.assistant.agent

import dev.jarvis.assistant.parser.FuzzyMatcher
import dev.jarvis.assistant.parser.normalize
import kotlin.math.sqrt

/** Small, auditable semantic vocabulary. No network, embeddings, model or personal-data training. */
class LocalNlu {
    enum class Goal { COMMUNICATE, WRITE, DEVICE_HEALTH, PREPARE_PHONE, TODAY, NONE }
    data class Classification(val goal: Goal, val confidence: Double, val keywords: Set<String>)

    private val concepts = mapOf(
        "talk" to setOf("talk", "speak", "contact", "reach", "connect", "communicate"),
        "someone" to setOf("someone", "somebody", "person", "friend", "family"),
        "write" to setOf("write", "compose", "draft", "writing"),
        "message" to setOf("message", "text", "note", "something"),
        "phone" to setOf("phone", "device", "mobile"),
        "slow" to setOf("slow", "sluggish", "laggy", "lagging", "lag", "slowing", "slower"),
        "prepare" to setOf("prepare", "ready", "check", "status"),
        "today" to setOf("today", "day", "daily"),
        "tasks" to setOf("tasks", "task", "reminders", "reminder", "plans", "plan", "schedule")
    )
    private val stopWords = setOf("i", "me", "my", "a", "an", "the", "is", "it", "to", "for", "and", "of",
        "can", "could", "would", "you", "please", "want", "need", "getting", "get", "help", "with", "what",
        "should", "do", "am", "been", "has", "be", "very", "really", "so", "some", "like", "its")
    private val examples = mapOf(
        Goal.COMMUNICATE to listOf("talk someone", "contact someone", "speak friend"),
        Goal.WRITE to listOf("write message", "write something", "draft message"),
        Goal.DEVICE_HEALTH to listOf("phone slow", "device lagging", "phone sluggish"),
        Goal.PREPARE_PHONE to listOf("prepare phone", "phone ready", "check phone"),
        Goal.TODAY to listOf("tasks today", "reminders today", "what should i do today", "plan day")
    )

    fun keywords(text: String): Set<String> = normalize(text).replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .split(Regex("\\s+")).filter { it.isNotBlank() && it !in stopWords }.take(40)
        .map(::concept).toSet()

    private fun concept(word: String): String {
        concepts.entries.firstOrNull { word in it.value }?.let { return it.key }
        // A minor misspelling may select a question or a read-only check, never a side effect.
        if (word.length in 5..32) concepts.entries.firstOrNull { entry ->
            entry.value.any { it.length >= 5 && FuzzyMatcher.score(word, it) >= .82 }
        }?.let { return it.key }
        return word
    }

    fun semanticSimilarity(left: String, right: String): Double = similarity(keywords(left), keywords(right))

    private fun similarity(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size / sqrt(a.size.toDouble() * b.size)

    fun classify(text: String): Classification {
        val words = keywords(text)
        val scores = examples.map { (goal, phrases) -> goal to (phrases.maxOfOrNull { similarity(words, keywords(it)) } ?: 0.0) }
            .sortedByDescending { it.second }
        val best = scores.first()
        val required = when (best.first) {
            Goal.COMMUNICATE -> "talk" in words
            Goal.WRITE -> "write" in words
            Goal.DEVICE_HEALTH -> "phone" in words && "slow" in words
            Goal.PREPARE_PHONE -> "phone" in words && "prepare" in words
            Goal.TODAY -> "today" in words
            Goal.NONE -> false
        }
        val unambiguous = best.second - scores[1].second >= .12
        return Classification(if (required && best.second >= .67 && unambiguous) best.first else Goal.NONE,
            best.second, words)
    }
}
