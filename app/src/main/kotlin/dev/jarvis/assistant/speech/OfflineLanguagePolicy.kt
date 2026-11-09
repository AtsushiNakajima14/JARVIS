package dev.jarvis.assistant.speech

import java.util.Locale

/** Prefer an already installed language pack; never send the request to a network recognizer. */
object OfflineLanguagePolicy {
    fun installedVariant(requested: String, installed: List<String>): String {
        val exact = installed.firstOrNull { it.equals(requested, ignoreCase = true) }
        if (exact != null) return exact
        val language = Locale.forLanguageTag(requested).language
        return installed.firstOrNull { Locale.forLanguageTag(it).language.equals(language, ignoreCase = true) }
            ?: requested
    }
}
