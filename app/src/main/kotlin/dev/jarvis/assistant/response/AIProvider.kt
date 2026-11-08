package dev.jarvis.assistant.response

/** Chat providers do not execute actions; sensitive actions remain in confirmed native skills. */
interface AIProvider {
    val name: String
    suspend fun answer(question: String): String
}
