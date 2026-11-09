package dev.jarvis

import dev.jarvis.assistant.agent.AgentCore
import dev.jarvis.assistant.agent.AgentKnowledge
import dev.jarvis.assistant.parser.IntentType
import dev.jarvis.assistant.parser.SpeechSelection
import dev.jarvis.assistant.response.AndroidNativeProvider
import dev.jarvis.core.common.AssistantSession
import dev.jarvis.core.common.assistantSettingsFrom
import dev.jarvis.core.security.PrivateCommandPolicy
import androidx.datastore.preferences.core.emptyPreferences
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class NativeCompanionIntegrationTest {
    @Test fun `voice alternatives preserve personal goals and negation`() {
        val selection = SpeechSelection()
        for (utterance in listOf("I need to talk to someone", "my phone is getting slow", "remind me to call Mom in ten minutes", "don't open Messenger")) {
            assertEquals(utterance, selection.choose(listOf(utterance, "open Messenger")))
        }
    }
    @Test fun `native communication and device goals work with optional model disabled`() {
        assertFalse(assistantSettingsFrom(emptyPreferences()).localAi)
        val agent = AgentCore()
        val question = agent.handle("Jarvis, I need to talk to someone.")
        assertTrue(question.awaitingReply)
        assertTrue(question.response!!.contains("Who"))
        assertTrue(agent.handle("Alex").awaitingReply)
        val action = agent.handle("call")
        assertEquals(IntentType.CALL, action.action!!.type)
        assertEquals("Alex", action.action!!.argument)
        assertTrue(action.isPrivate)
        assertEquals(IntentType.DEVICE_HEALTH, agent.handle("my phone is getting slow").action!!.type)
        assertEquals(IntentType.OPEN_APPLICATION, agent.handle("can you show me Messenger?",
            knowledge = AgentKnowledge(appLabels = listOf("Messenger"))).action!!.type)
    }

    @Test fun `cleared session rejects an old result and keeps conversation bounded`() {
        val session = AssistantSession()
        val revision = session.conversationRevision.value
        session.recordTurn("Private question", "Private answer", revision)
        session.clearConversation()
        session.recordTurn("Old work", "Stale answer", revision)
        assertTrue(session.conversation.value.isEmpty())
        repeat(60) { session.recordTurn("Q$it", "A$it") }
        assertEquals(40, session.conversation.value.size)
        assertEquals("Q20", session.conversation.value.first().user)
    }

    @Test fun `private abilities are gated but Android authentication remains available when locked`() {
        listOf(IntentType.DEVICE_HEALTH, IntentType.PREPARE_PHONE, IntentType.REMINDER_CREATE,
            IntentType.REMINDER_LIST, IntentType.MEMORY_LIST, IntentType.MEMORY_FORGET,
            IntentType.RECENT_FILES, IntentType.FILE_SEARCH, IntentType.CALL, IntentType.MESSAGE)
            .forEach { assertTrue("$it must be gated", PrivateCommandPolicy.privateAbility(it)) }
        assertFalse(PrivateCommandPolicy.privateAbility(IntentType.UNLOCK_PHONE))
        assertFalse(AgentCore().handle("unlock my phone").isPrivate)
    }

    @Test fun `native answers use the device clock without a model`() {
        val clock = Clock.fixed(Instant.parse("2026-11-09T01:30:00Z"), ZoneId.of("Asia/Manila"))
        val provider = AndroidNativeProvider()
        assertTrue(provider.reply("what time is it", clock).contains("9:30"))
        assertTrue(provider.reply("what is the date", clock).contains("2026"))
        assertTrue(provider.reply("thank you", clock).contains("welcome"))
    }
}
