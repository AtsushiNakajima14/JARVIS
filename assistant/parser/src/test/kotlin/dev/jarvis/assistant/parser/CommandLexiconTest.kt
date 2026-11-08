package dev.jarvis.assistant.parser

import org.junit.Assert.*
import org.junit.Test

class CommandLexiconTest {
    @Test fun `reported commands and wake combined commands are in grammar`() {
        val phrases = CommandLexicon.phrases(listOf("Discord", "Facebook", "YouTube", "Google Chrome"))
        listOf("jarvis", "[unk]", "battery percentage", "jarvis battery percentage", "open discord",
            "jarvis open discord", "open face book", "open you tube", "open chrome").forEach {
            assertTrue("Missing $it", it in phrases)
        }
    }
    @Test fun `grammar is bounded deterministic and deduplicated`() {
        val labels = (1..1000).map { "Tool $it" }
        val phrases = CommandLexicon.phrases(labels + labels)
        assertTrue(phrases.size <= CommandLexicon.MAX_PHRASES)
        assertEquals(phrases.distinct(), phrases)
        assertEquals(phrases, CommandLexicon.phrases(labels + labels))
    }
    @Test fun `grammar never forces contact memory or confirmation actions`() {
        val forbidden = setOf(IntentType.UNKNOWN, IntentType.RECALL, IntentType.CALL, IntentType.MESSAGE,
            IntentType.CONFIRM, IntentType.CANCEL, IntentType.REMEMBER, IntentType.HELP)
        val parser = CommandParser()
        val phrases = CommandLexicon.phrases(listOf("Call Mom", "Remember Me", "Discord", "Battery", "Flashlight"))
        phrases.filter { it !in setOf("jarvis", "[unk]") }.forEach {
            val result = parser.parse(it)
            assertFalse("Forced unsupported or sensitive phrase: $it", result.type in forbidden)
            assertTrue("Low-confidence grammar phrase: $it", result.confidence >= 0.7)
        }
        assertEquals(IntentType.OPEN_APPLICATION, parser.parse("open battery").type)
        assertEquals(IntentType.OPEN_APPLICATION, parser.parse("open flashlight").type)
    }
    @Test fun `spoken aliases and grammar use the same application meanings`() {
        val apps = listOf(NamedApplication("discord", "Discord"), NamedApplication("chat", "ChatGPT"), NamedApplication("mail", "Gmail"))
        assertEquals("discord", ApplicationMatcher.find("dis cord", apps))
        assertEquals("chat", ApplicationMatcher.find("chat gee pee tee", apps))
        assertEquals("mail", ApplicationMatcher.find("gee mail", apps))
        assertNull(ApplicationMatcher.find("this guy", apps))
    }
    @Test fun `duplicate app labels cannot create forced ambiguous launches`() {
        val phrases = CommandLexicon.phrases(listOf("Discord", "Chat", "chat"))
        assertTrue("open discord" in phrases)
        assertFalse("open chat" in phrases)
    }
    @Test fun `absent branded apps do not resolve to a shorter unrelated label`() {
        assertNull(ApplicationMatcher.find("g mail", listOf(NamedApplication("mail", "Mail"))))
        assertNull(ApplicationMatcher.find("this guy", listOf(NamedApplication("discord", "Discord"))))
        assertNull(ApplicationMatcher.find("chrome", listOf(NamedApplication("one", "Google Chrome"), NamedApplication("two", "Chrome Beta"))))
    }
    @Test fun `app suffix does not erase a real app name`() {
        val apps = listOf(NamedApplication("wa", "WhatsApp"), NamedApplication("fb", "Facebook"), NamedApplication("mine", "MyApp"))
        val parser = CommandParser()
        assertEquals("wa", ApplicationMatcher.find(parser.parse("open whats app").argument, apps))
        assertEquals("fb", ApplicationMatcher.find(parser.parse("please open Facebook app").argument, apps))
        assertEquals("mine", ApplicationMatcher.find(parser.parse("open my app").argument, apps))
        assertTrue("open whats app" in CommandLexicon.phrases(listOf("WhatsApp")))
        assertTrue("open my app" in CommandLexicon.phrases(listOf("MyApp")))
    }
}
