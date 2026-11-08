package dev.jarvis
import dev.jarvis.assistant.parser.*
import dev.jarvis.skills.*
import org.junit.Assert.*
import org.junit.Test
class SkillMatchingTest {
    private fun skill(type: IntentType) = object : AssistantSkill {
        override val name = type.name; override val description = "test"; override val aliases = emptySet<String>()
        override fun canHandle(command: ParsedCommand) = command.type == type
        override suspend fun execute(command: ParsedCommand) = SkillResult("executed")
    }
    @Test fun `dispatches only the intended skill`() {
        val battery = skill(IntentType.BATTERY_STATUS)
        assertSame(battery, SkillMatcher.match(listOf(skill(IntentType.OPEN_APPLICATION), battery), CommandParser().parse("battery percentage")))
    }
    @Test fun `ambiguous registrations fail closed`() {
        assertNull(SkillMatcher.match(listOf(skill(IntentType.CALL), skill(IntentType.CALL)), ParsedCommand(IntentType.CALL)))
    }
    @Test fun `low confidence cannot execute a device action`() {
        assertNull(SkillMatcher.match(listOf(skill(IntentType.TIMER)), CommandParser().parse("set a timer")))
    }
}

