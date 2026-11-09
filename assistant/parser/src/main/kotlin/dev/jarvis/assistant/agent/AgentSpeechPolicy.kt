package dev.jarvis.assistant.agent

import dev.jarvis.assistant.parser.normalize
import dev.jarvis.assistant.parser.stripAssistantPrefix

/** Do not reinterpret a natural goal, dictated data or a refusal as a secondary phone action. */
object AgentSpeechPolicy {
    private val nlu = LocalNlu()
    fun preservePrimary(text: String, assistantName: String = "Jarvis"): Boolean {
        val value = normalize(stripAssistantPrefix(text, assistantName))
        return nlu.classify(value).goal != LocalNlu.Goal.NONE ||
            Regex("^(?:don't|do not|never|remember|forget|remind me|set (?:a )?reminder|create (?:a )?reminder|add (?:a )?reminder|find (?:my |the |a )?(?:files?|documents?)|show my (?:memories|tasks|reminders)|mark .+ (?:done|complete))\\b")
                .containsMatchIn(value)
    }
}
