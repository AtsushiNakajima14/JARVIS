package dev.jarvis.assistant.parser

/** A narrowly scoped ASR homophone correction, applied only to a complete clock question. */
object ClockSpeechCorrection {
    fun correct(text: String, assistantName: String = "Jarvis"): String {
        val query = normalize(stripAssistantPrefix(text, assistantName))
        if (!Regex("\\bthyme\\b").containsMatchIn(query) || TimeCommandParser.parse(query)?.type != IntentType.TIME_STATUS)
            return text
        return text.replace(Regex("\\bthyme\\b", RegexOption.IGNORE_CASE), "time")
    }
}
