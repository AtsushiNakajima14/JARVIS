package dev.jarvis.assistant.speech

import dev.jarvis.assistant.parser.*
import org.json.JSONObject

data class SpeechResult(val alternatives: List<String>, val source: String, val wakeOnly: Boolean = false) {
    val text: String get() = alternatives.firstOrNull().orEmpty()
}
data class DecodedUtterance(val alternatives: List<String>, val wordConfidences: List<Double> = emptyList(),
    val words: List<String> = emptyList())

/** N-best likelihoods are not probabilities; only result[].conf is a word confidence. */
object VoskResultReader {
    fun read(json: String): DecodedUtterance {
        val data = JSONObject(json)
        val nbest = data.optJSONArray("alternatives")
        val alternatives = if (nbest != null) (0 until minOf(nbest.length(), 5))
            .map { nbest.getJSONObject(it).optString("text").trim() }
        else listOf(data.optString("text").trim())
        val words = data.optJSONArray("result")
        val confidences = if (words == null) emptyList() else (0 until words.length()).map {
            words.getJSONObject(it).optDouble("conf", Double.NaN)
        }
        val wordText = if (words == null) emptyList() else (0 until words.length()).map { words.getJSONObject(it).optString("word") }
        return DecodedUtterance(alternatives.filter { it.isNotBlank() }.distinct(), confidences, wordText)
    }
    fun partial(json: String): String = JSONObject(json).optString("partial")
}

/** Grammar rescues device commands; it never supplies contact, memory or confirmation text. */
class OfflineSpeechSelection(private val appLabels: List<String>, private val parser: CommandParser = CommandParser()) {
    private val allowed = CommandLexicon.phrases(appLabels).map { normalize(stripAssistantPrefix(it)) }.toSet()
    fun isWakeOnly(free: DecodedUtterance, grammar: DecodedUtterance?): Boolean {
        if (normalize(grammar?.alternatives?.firstOrNull().orEmpty()) != "jarvis" || grammar == null) return false
        val confidence = grammar.wordConfidences
        if (confidence.isEmpty() || confidence.any { !it.isFinite() || it !in 0.55..1.0 } || confidence.average() < 0.78) return false
        return parser.parse(free.alternatives.firstOrNull().orEmpty()).type in setOf(IntentType.UNKNOWN, IntentType.HELP)
    }
    fun choose(free: DecodedUtterance, grammar: DecodedUtterance?): List<String> {
        val original = free.alternatives
        val first = parser.parse(original.firstOrNull().orEmpty())
        val protected = setOf(IntentType.CALL, IntentType.MESSAGE, IntentType.REMEMBER, IntentType.RECALL,
            IntentType.CONFIRM, IntentType.CANCEL)
        if (first.type in protected && first.type != IntentType.RECALL) return original
        val grammarText = grammar?.alternatives?.firstOrNull() ?: return original
        if (normalize(stripAssistantPrefix(grammarText)) !in allowed) return original
        if (grammarText.contains("[unk]") || grammar.wordConfidences.isEmpty()) return original
        if (grammar.wordConfidences.any { !it.isFinite() || it !in 0.0..1.0 || it < 0.55 } || grammar.wordConfidences.average() < 0.78) return original
        val parsed = parser.parse(grammarText)
        if (parsed.confidence < 0.7 || parsed.type in protected + setOf(IntentType.UNKNOWN, IntentType.HELP)) return original
        val firstIsKnown = first.confidence >= 0.7 && first.type !in setOf(IntentType.UNKNOWN, IntentType.RECALL, IntentType.HELP)
        val failedAppName = first.type == IntentType.OPEN_APPLICATION && ApplicationMatcher.find(first.argument,
            appLabels.mapIndexed { i, label -> NamedApplication(i.toString(), label) }) == null
        if (firstIsKnown && !failedAppName) return original
        return (listOf(grammarText) + original).distinct().take(5)
    }
}
