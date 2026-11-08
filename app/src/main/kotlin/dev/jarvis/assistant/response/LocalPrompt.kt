package dev.jarvis.assistant.response

import java.util.Locale

data class ChatTurn(val user: String, val assistant: String)

/** Process-local conversational context, fenced against a clear while work is in flight. */
class ConversationContext(private val maximumTurns: Int = 4) {
    data class Snapshot(val revision: Long, val turns: List<ChatTurn>)
    private val lock = Any()
    private val turns = ArrayDeque<ChatTurn>()
    private var revision = 0L
    init { require(maximumTurns > 0) }
    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(revision, turns.toList()) }
    fun currentRevision(): Long = synchronized(lock) { revision }
    fun recordTurn(question: String, response: String, expectedRevision: Long = currentRevision()): Boolean = synchronized(lock) {
        if (expectedRevision != revision || question.isBlank() || response.isBlank()) return@synchronized false
        val turn = ChatTurn(question.take(360), response.take(700))
        // answer() records its own turn. An accidental duplicate service callback must
        // not consume the small context window a second time.
        if (turns.lastOrNull() != turn) {
            turns.addLast(turn)
            while (turns.size > maximumTurns) turns.removeFirst()
        }
        true
    }
    fun clear() = synchronized(lock) { revision++; turns.clear() }
}

object LocalPrompt {
    private fun clean(value: String, limit: Int) = value.replace("<|", "‹|").replace("|>", "|›").trim().take(limit)
    /** Small context for a 3 GB phone. Personal text stays entirely in this local prompt. */
    fun build(name: String, question: String, facts: String, memory: String?, history: List<ChatTurn>): String {
        val system = "You are ${clean(name, 30)}, my friendly personal assistant. Reply briefly in English. " +
            "Do not claim to open apps, call, send or change settings. Phone skills perform actions. " +
            "Use the facts given. Say when you do not know.\n" + clean(facts, 180) +
            (memory?.let { "\nSaved preference: ${clean(it, 120)}" } ?: "")
        return buildString {
            append("<|im_start|>system\n$system<|im_end|>\n")
            history.takeLast(1).forEach { turn ->
                append("<|im_start|>user\n${clean(turn.user, 100)}<|im_end|>\n")
                append("<|im_start|>assistant\n${clean(turn.assistant, 140)}<|im_end|>\n")
            }
            append("<|im_start|>user\n${clean(question, 360)}<|im_end|>\n<|im_start|>assistant\n")
        }
    }
    fun cleanReply(value: String): String {
        var text = value.substringBefore("<|im_end|>").substringBefore("<|im_start|>")
            .substringBefore("<|endoftext|>").substringBefore("</s>")
            .trim().replaceFirst(Regex("^assistant:\\s*", RegexOption.IGNORE_CASE), "").take(700)
        val nextRole = Regex("(?im)^\\s*(?:user|human|system|assistant):").find(text)
        if (nextRole != null) text = text.take(nextRole.range.first).trim()
        if (text.none { it.isLetterOrDigit() }) return ""

        val abbreviations = setOf("mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.", "st.", "vs.", "e.g.", "i.e.", "etc.")
        val endings = Regex("[.!?][\\\"'’”)]*(?=\\s|$)").findAll(text).filter { match ->
            val lastWord = text.take(match.range.last + 1).trimEnd('"', '\'', '’', '”', ')')
                .substringAfterLast(' ').substringAfterLast('\n').lowercase(Locale.ROOT)
            lastWord !in abbreviations && !Regex("^(?:[a-z]\\.){1,5}$").matches(lastWord)
        }.map { it.range.last + 1 }.toList()
        val sentences = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var start = 0
        for (end in endings) {
            val sentence = text.substring(start, end).trim()
            val key = sentence.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            if (key.isNotBlank() && !seen.add(key)) break
            if (sentence.isNotBlank()) sentences += sentence
            start = end
        }
        // A complete generated sentence is safer to speak than the unfinished tail
        // of a 64-token response. No replacement facts or fallback answers are added.
        if (sentences.isNotEmpty()) return sentences.joinToString(" ")

        val words = Regex("\\S+").findAll(text).toList()
        val normalized = words.map { it.value.lowercase(Locale.ROOT).trim('.', ',', '!', '?', ':', ';') }
        for (index in normalized.indices) {
            for (length in 1..minOf(12, (normalized.size - index) / 3)) {
                val repeats = if (length == 1) 4 else 3
                if (index + length * repeats > normalized.size) continue
                val pattern = normalized.subList(index, index + length)
                if (pattern.all { it.isBlank() }) continue
                if ((1 until repeats).all { repeat -> normalized.subList(index + length * repeat, index + length * (repeat + 1)) == pattern }) {
                    return text.take(words[index + length - 1].range.last + 1).trim()
                }
            }
        }
        return text.trim()
    }
}
