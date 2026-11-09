package dev.jarvis.core.security

import dev.jarvis.assistant.parser.IntentType
import dev.jarvis.assistant.parser.normalize
import dev.jarvis.assistant.parser.stripAssistantPrefix

/** Screen-lock secrets never belong in chat prompts, saved memory or command history. */
object PrivateCommandPolicy {
    private const val SECRET = "(?:pin|passcode|password|unlock code|security code|otp|one time password)"
    private val disclosure = Regex("\\bmy\\s+(?:(?:phone|screen|lock|screen lock)\\s+)?$SECRET\\s*(?:is\\b|equals\\b|[:=])")
    private val incomplete = Regex("^(?:please\\s+)?my\\s+(?:(?:phone|screen|lock|screen lock)\\s+)?$SECRET\\s*$")
    private val storage = Regex("^(?:please\\s+)?(?:remember|save|store|enter|type|use)\\b.{0,80}\\b$SECRET\\b")
    private val retrieval = Regex("^(?:(?:what(?: is| s)?|tell me|recall)\\s+).{0,30}\\bmy\\s+(?:(?:phone|screen|lock)\\s+)?$SECRET\\b")
    private val unlockValue = Regex("\\bunlock\\b.{0,40}\\b(?:with|using|code|pin)\\s+\\d{3,12}\\b")

    fun isCredentialRequest(text: String): Boolean {
        val value = normalize(stripAssistantPrefix(text)).replace("'", " ")
        return disclosure.containsMatchIn(value) || incomplete.matches(value) || storage.containsMatchIn(value) ||
            retrieval.containsMatchIn(value) || unlockValue.containsMatchIn(value)
    }
    fun transcript(text: String): String = if (isCredentialRequest(text)) "[Credential omitted]" else text
    fun notificationQuery(type: IntentType): Boolean = type in setOf(
        IntentType.NOTIFICATIONS, IntentType.RECENT_CONVERSATIONS, IntentType.DAILY_BRIEFING)
    fun privateAbility(type: IntentType): Boolean = notificationQuery(type) || type in setOf(
        IntentType.CALL, IntentType.MESSAGE, IntentType.REMEMBER, IntentType.RECALL,
        IntentType.MEMORY_LIST, IntentType.MEMORY_FORGET,
        IntentType.FILE_SEARCH, IntentType.RECENT_FILES,
        IntentType.REMINDER_CREATE, IntentType.REMINDER_LIST, IntentType.REMINDER_COMPLETE,
        IntentType.DEVICE_HEALTH, IntentType.PREPARE_PHONE, IntentType.MOST_USED, IntentType.RARELY_USED)
    const val CREDENTIAL_REPLY = "I don't save or enter PINs or passwords. Say unlock my phone to open Android authentication."
}
