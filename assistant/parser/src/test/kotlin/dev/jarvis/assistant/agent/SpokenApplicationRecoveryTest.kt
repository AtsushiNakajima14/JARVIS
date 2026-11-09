package dev.jarvis.assistant.agent

import dev.jarvis.assistant.parser.ApplicationMatcher
import dev.jarvis.assistant.parser.CommandLexicon
import dev.jarvis.assistant.parser.IntentType
import dev.jarvis.assistant.parser.NamedApplication
import dev.jarvis.assistant.parser.SpokenApplicationNames
import org.junit.Assert.*
import org.junit.Test

class SpokenApplicationRecoveryTest {
    private val phone = AgentKnowledge(appLabels = listOf("Spotify", "Google Photos", "Discord"))

    @Test fun `reported fragmented Spotify name resolves against installed labels`() {
        val apps = listOf(NamedApplication("com.spotify.music", "Spotify"), NamedApplication("photos", "Google Photos"))
        for (heard in listOf("spo te fi", "spot if i", "spot of i", "spot i fi")) {
            assertEquals(heard, "spotify", SpokenApplicationNames.canonical(heard))
            assertEquals(heard, "com.spotify.music", ApplicationMatcher.find(heard, apps))
            assertEquals(heard, "Spotify", AgentCore().handle("open $heard", knowledge = phone).action!!.argument)
        }
        assertNull(ApplicationMatcher.find("spo te fi", listOf(NamedApplication("photos", "Google Photos"))))
    }

    @Test fun `Spotify grammar includes bounded aliases only for visible installed apps`() {
        val aliases = SpokenApplicationNames.aliases("Spotify")
        assertEquals(listOf("spotify", "spot if i", "spot of i", "spo te fi", "spot i fi"), aliases)
        val grammar = CommandLexicon.phrases(listOf("Spotify"))
        for (alias in aliases) {
            assertTrue("open $alias" in grammar)
            assertTrue("jarvis open $alias" in grammar)
        }
        assertFalse("open spo te fi" in CommandLexicon.phrases(listOf("Discord")))
        assertTrue(aliases.size <= 5)
    }

    @Test fun `reported unclear verb asks before opening Spotify`() {
        val core = AgentCore()
        val question = core.handle("Jarvis bathing spo te fi", knowledge = phone, nowMillis = 1000)
        assertNull(question.action)
        assertTrue(question.awaitingReply)
        assertTrue(question.response!!.contains("Did you mean open Spotify?"))
        val confirmed = core.handle("yes", knowledge = phone, nowMillis = 2000)
        assertEquals(IntentType.OPEN_APPLICATION, confirmed.action!!.type)
        assertEquals("Spotify", confirmed.action!!.argument)
    }

    @Test fun `refusing an unclear app suggestion never opens it`() {
        val core = AgentCore()
        assertTrue(core.handle("bathing spo te fi", knowledge = phone).awaitingReply)
        assertNull(core.handle("no", knowledge = phone).action)
        assertFalse(core.hasPendingReply())
        assertEquals(IntentType.CONFIRM, core.handle("yes", knowledge = phone).action!!.type)
    }

    @Test fun `unknown fragments cannot suggest an absent or ambiguous app as one choice`() {
        val absent = AgentCore().handle("bathing spo te fi", knowledge = AgentKnowledge(appLabels = listOf("Discord")))
        assertFalse(absent.awaitingReply)
        assertEquals(IntentType.UNKNOWN, absent.action!!.type)
        val ambiguous = AgentCore().handle("bathing spo te fi", knowledge = AgentKnowledge(appLabels = listOf("Spotify", "Spotifi")))
        assertNull(ambiguous.action)
        assertTrue(ambiguous.awaitingReply)
        assertTrue(ambiguous.response!!.contains("Spotify, Spotifi"))
        assertFalse("open spo te fi" in CommandLexicon.phrases(listOf("Spotify", "Spotifi")))
    }

    @Test fun `split labels work generically and the longest installed name wins`() {
        for (heard in listOf("bathing google pho tos", "google pho tos")) {
            val question = AgentCore().handle(heard, knowledge = phone)
            assertNull(question.action)
            assertTrue(question.response!!.contains("open Google Photos?"))
        }
        val longest = AgentCore().handle("bathing google photos", knowledge = AgentKnowledge(appLabels = listOf("Photos", "Google Photos")))
        assertTrue(longest.response!!.contains("open Google Photos?"))
        assertEquals("Google Photos", AgentCore().handle("show me google pho tos", knowledge = phone).action!!.argument)
    }

    @Test fun `questions opinions and negation do not become app suggestions`() {
        for (text in listOf("what is Spotify", "I hate Spotify", "I use Spotify", "don't open spo te fi",
            "bathing not spo te fi", "my Google Photos", "tell me about Spotify")) {
            val result = AgentCore().handle(text, knowledge = phone)
            assertFalse(text, result.awaitingReply)
            assertNotEquals(text, IntentType.OPEN_APPLICATION, result.action?.type)
        }
    }

    @Test fun `dictated messages and approved memories preserve fragmented app text as data`() {
        val remembered = AgentCore().handle("remember bathing spo te fi", knowledge = phone)
        assertEquals(IntentType.REMEMBER, remembered.action!!.type)
        assertEquals("bathing spo te fi", remembered.action!!.argument)
        val core = AgentCore()
        core.handle("text Mom", knowledge = phone)
        val message = core.handle("bathing spo te fi", knowledge = phone)
        assertEquals(IntentType.MESSAGE, message.action!!.type)
        assertEquals("bathing spo te fi", message.action!!.body)
    }

    @Test fun `unclear app suggestions expire and cannot resurrect after reset`() {
        val core = AgentCore()
        core.handle("bathing spo te fi", knowledge = phone, nowMillis = 1000)
        assertEquals(IntentType.CONFIRM, core.handle("yes", knowledge = phone, nowMillis = 1000 + AgentCore.CONTEXT_TTL).action!!.type)
        core.handle("bathing spo te fi", knowledge = phone, nowMillis = 200_000)
        core.reset()
        assertEquals(IntentType.CONFIRM, core.handle("yes", knowledge = phone, nowMillis = 201_000).action!!.type)
    }
}
