package dev.jarvis.skills

import android.content.Intent
import dev.jarvis.assistant.parser.ParsedCommand

data class SkillResult(val response: String, val launch: Intent? = null, val confirmation: String? = null)
interface AssistantSkill {
    val name: String
    val description: String
    val aliases: Set<String>
    fun canHandle(command: ParsedCommand): Boolean
    suspend fun execute(command: ParsedCommand): SkillResult
}

