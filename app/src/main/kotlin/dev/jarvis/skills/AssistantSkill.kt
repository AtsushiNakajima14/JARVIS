package dev.jarvis.skills

import android.content.Intent
import dev.jarvis.assistant.parser.ParsedCommand

data class SkillResult(val response: String, val launch: Intent? = null, val confirmation: String? = null)
/** An Android capability selected by the agent after resolving a goal and its missing details. */
interface AssistantAbility {
    val name: String
    val description: String
    val aliases: Set<String>
    fun canHandle(command: ParsedCommand): Boolean
    suspend fun execute(command: ParsedCommand): SkillResult
}

/** Compatibility for the existing, tested Android executors. */
typealias AssistantSkill = AssistantAbility

