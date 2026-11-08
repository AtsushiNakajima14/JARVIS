package dev.jarvis.assistant.wakeword

/** Partial hypotheses can briefly say Jarvis for unrelated speech; wait for stability. */
class StableWakeDetector {
    private var matches = 0
    fun observe(text: String, complete: Boolean): Boolean {
        val hasWake = Regex("\\bjarvis\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        matches = if (hasWake) matches + 1 else 0
        return matches >= 3 || (complete && text.trim().equals("jarvis", true))
    }
}
