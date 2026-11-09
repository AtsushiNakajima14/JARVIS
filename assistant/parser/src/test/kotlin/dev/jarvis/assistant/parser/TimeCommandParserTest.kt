package dev.jarvis.assistant.parser

import dev.jarvis.assistant.agent.AgentCore
import org.junit.Assert.*
import org.junit.Test

class TimeCommandParserTest {
    private val parser = CommandParser()

    @Test fun `everyday clock questions have a native time intent`() {
        listOf("what is the time", "What time is it?", "Jarvis, what is the time now?",
            "can you tell me the current time", "what is the current time", "current time", "anong oras na", "what's the time please")
            .forEach { assertEquals(it, IntentType.TIME_STATUS, parser.parse(it).type) }
    }

    @Test fun `date questions are distinct from tasks for today`() {
        listOf("what is the date", "what is today's date", "what day is it", "what day is today",
            "tell me the date", "anong petsa ngayon")
            .forEach {
                assertEquals(it, IntentType.DATE_STATUS, parser.parse(it).type)
                assertEquals(it, IntentType.DATE_STATUS, AgentCore().handle(it).action!!.type)
            }
        assertEquals(IntentType.REMINDER_LIST, AgentCore().handle("what should I do today").action!!.type)
    }

    @Test fun `only a complete clock question permits the thyme homophone`() {
        assertEquals(IntentType.TIME_STATUS, parser.parse("what is the thyme").type)
        assertEquals(IntentType.TIME_STATUS, parser.parse("what's the thyme now").type)
        listOf("thyme", "what is thyme used for", "what is time travel", "time to call Mom")
            .forEach { assertNotEquals(it, IntentType.TIME_STATUS, parser.parse(it).type) }
    }

    @Test fun `personal payloads and negation never become time checks`() {
        assertEquals(IntentType.CALL, parser.parse("call Time").type)
        assertEquals(IntentType.REMEMBER, parser.parse("remember what is the time").type)
        val message = parser.parse("text Mom saying What is the time?")
        assertEquals(IntentType.MESSAGE, message.type)
        assertEquals("What is the time?", message.body)
        assertNull(AgentCore().handle("don't tell me the time").action)
        assertNull(AgentCore().handle("do not check the date").action)
    }

    @Test fun `offline grammar contains complete time and date phrases`() {
        val grammar = CommandLexicon.phrases(emptyList())
        listOf("what is the time", "what time is it", "what is the date", "what day is it").forEach {
            assertTrue(it, grammar.contains(it))
            assertTrue(it, grammar.contains("jarvis $it"))
        }
        assertFalse(grammar.contains("what is the thyme"))
    }
}
