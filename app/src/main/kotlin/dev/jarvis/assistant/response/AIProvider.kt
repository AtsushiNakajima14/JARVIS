package dev.jarvis.assistant.response

/** Optional future extension. No provider, API key, or cloud request is required by JARVIS. */
interface AIProvider {
    val name: String
    suspend fun answer(question: String): String
}
