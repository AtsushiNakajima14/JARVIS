package dev.jarvis.assistant.wakeword

import dev.jarvis.assistant.speech.DecodedUtterance

/** Only use for a confirmed wake activation, never for manually dictated personal text. */
object WakeTranscript {
    private val wakeWord = Regex("\\bjarvis\\b", RegexOption.IGNORE_CASE)
    fun trim(text: String): String = wakeWord.find(text)?.let { text.substring(it.range.first) } ?: text
    fun trim(decoded: DecodedUtterance): DecodedUtterance {
        val index = decoded.words.indexOfFirst { it.equals("jarvis", true) }
        return decoded.copy(alternatives = decoded.alternatives.map(::trim),
            wordConfidences = if (index >= 0) decoded.wordConfidences.drop(index) else decoded.wordConfidences,
            words = if (index >= 0) decoded.words.drop(index) else decoded.words)
    }
}
