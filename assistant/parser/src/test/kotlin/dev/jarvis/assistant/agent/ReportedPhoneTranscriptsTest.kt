package dev.jarvis.assistant.agent

import dev.jarvis.assistant.parser.*
import org.junit.Assert.*
import org.junit.Test

class ReportedPhoneTranscriptsTest {
    private val knowledge = AgentKnowledge(appLabels = listOf("YT Music", "Spotify", "Instagram"))

    @Test fun `YouTube Music resolves the installed YT Music label without manual commands`() {
        assertEquals("com.music", ApplicationMatcher.find("YouTube music", listOf(NamedApplication("com.music", "YT Music"))))
        assertEquals("YT Music", AgentCore().handle("open YouTube music", knowledge = knowledge).action!!.argument)
        assertTrue("open you tube music" in CommandLexicon.phrases(listOf("YT Music")))
        assertFalse(CommandLexicon.phrases(listOf("Music")).contains("open you tube music"))
    }

    @Test fun `unclear app slot asks a real follow up and accepts an installed alias`() {
        val agent = AgentCore()
        val first = agent.handle("open your job", knowledge = knowledge)
        assertNull(first.action)
        assertTrue(first.awaitingReply)
        val reply = agent.handle("YouTube Music", knowledge = knowledge)
        assertEquals(IntentType.OPEN_APPLICATION, reply.action!!.type)
        assertEquals("YT Music", reply.action!!.argument)
    }

    @Test fun `read versus ready notification transcription remains a private ability`() {
        val parser = CommandParser()
        assertEquals(IntentType.NOTIFICATIONS, parser.parse("ready my notifications").type)
        assertEquals(IntentType.NOTIFICATIONS, parser.parse("ready my notifications on Discord").type)
        assertTrue(AgentCore().handle("ready my notifications").isPrivate)
        assertEquals(IntentType.REMEMBER, parser.parse("remember ready my notifications is a phrase").type)
        assertEquals(IntentType.MESSAGE, parser.parse("text Mom saying ready my notifications").type)
    }

    @Test fun `existing personal queries in screenshots have native abilities`() {
        assertEquals(IntentType.CREATOR, AgentCore().handle("who made you").action!!.type)
        assertEquals(IntentType.NOTIFICATIONS, AgentCore().handle("read my notifications").action!!.type)
    }
}
