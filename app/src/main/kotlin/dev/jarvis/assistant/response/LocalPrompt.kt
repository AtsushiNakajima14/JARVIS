package dev.jarvis.assistant.response

import java.util.Locale

data class ChatTurn(val user: String, val assistant: String)

/** The app owner's requested attribution; these are not device access credentials. */
object AssistantPersona {
    const val CREATOR_NAME = "Cyril Matt O. Encenso"
    const val CREATOR_ALIAS = "CyCy"
    const val FRIENDLY_NAME = CREATOR_ALIAS
}

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
    const val MAX_PROMPT_BYTES = 1350
    const val MAX_COMPACT_PROMPT_BYTES = 440
    private fun clean(value: String, bytes: Int) = utf8Prefix(
        value.replace("<|", "‹|").replace("|>", "|›").replace("\u0000", "").trim(), bytes)

    /** Truncate at a Unicode code point, never halfway through an emoji/surrogate pair. */
    private fun utf8Prefix(value: String, budget: Int): String {
        var used = 0
        var end = 0
        while (end < value.length) {
            val point = Character.codePointAt(value, end)
            val size = when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
            if (used + size > budget) break
            used += size
            end += Character.charCount(point)
        }
        return value.substring(0, end)
    }

    private fun message(role: String, text: String) = "<|im_start|>$role\n$text<|im_end|>\n"
    private fun byteSize(value: String) = value.toByteArray(Charsets.UTF_8).size
    private const val RESPONSE_PREFIX = "<|im_start|>assistant\n"

    /** Small context for a 3 GB phone. Personal text stays entirely in this local prompt. */
    fun build(name: String, question: String, facts: String, memory: String?, history: List<ChatTurn>): String {
        val system = "You are ${clean(name, 30)}, ${AssistantPersona.FRIENDLY_NAME}'s personal assistant. " +
            "Your creator is ${AssistantPersona.CREATOR_NAME} (${AssistantPersona.CREATOR_ALIAS}). " +
            "Be warm and concise in English. Use supplied facts and recent conversation; say when unsure. " +
            "Do not claim to open apps, call, send or change settings. " +
            "You cannot see private app screens or complete chat history.\nFacts: ${clean(facts, 144)}" +
            (memory?.takeIf { it.isNotBlank() }?.let { "\nSaved preference: ${clean(it, 96)}" } ?: "")
        val prefix = message("system", system)
        val suffix = message("user", clean(question, 360)) + RESPONSE_PREFIX
        var remaining = minOf(320, MAX_PROMPT_BYTES - byteSize(prefix) - byteSize(suffix))
        val previous = ArrayDeque<String>()
        // Short exchanges can retain two turns. Longer ones prioritize the newest
        // turn instead of exceeding the small model's context with old transcripts.
        for (turn in history.takeLast(2).asReversed()) {
            if (remaining < 100) break
            val user = clean(turn.user, minOf(80, remaining / 3))
            val assistant = clean(turn.assistant, minOf(140, remaining - byteSize(user) - 61))
            val exchange = message("user", user) + message("assistant", assistant)
            if (byteSize(exchange) <= remaining) {
                previous.addFirst(exchange)
                remaining -= byteSize(exchange)
            }
        }
        return prefix + previous.joinToString("") + suffix
    }

    /**
     * Retry only if native tokenization rejects an unusually dense prompt. The
     * byte bound leaves space for 64 output tokens and automatically added tokens
     * even when Unicode text cannot benefit from ordinary English BPE merges.
     */
    fun buildCompact(name: String, question: String, facts: String): String {
        var system = "You are ${clean(name, 16)}, ${AssistantPersona.FRIENDLY_NAME}'s helpful local assistant, " +
            "created by ${AssistantPersona.CREATOR_NAME} (${AssistantPersona.CREATOR_ALIAS}). " +
            "Reply briefly in English. Admit uncertainty; private app data is unseen. Phone skills execute actions."
        val suffix = message("user", clean(question, 144)) + RESPONSE_PREFIX
        val factBudget = minOf(48, MAX_COMPACT_PROMPT_BYTES - byteSize(message("system", system) + suffix) - 8)
        if (factBudget > 0) clean(facts, factBudget).takeIf { it.isNotBlank() }?.let { system += "\nFacts: $it" }
        return message("system", system) + suffix
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
