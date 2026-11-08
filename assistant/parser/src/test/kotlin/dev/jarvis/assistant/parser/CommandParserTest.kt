package dev.jarvis.assistant.parser

import org.junit.Assert.*
import org.junit.Test

class CommandParserTest {
    private val parser = CommandParser()
    @Test fun `app aliases and wake prefix`() {
        listOf("Jarvis, open Discord.", "Launch Discord", "Buksan ang Discord", "ablihi ang Discord").forEach {
            assertEquals(ParsedCommand(IntentType.OPEN_APPLICATION, argument = "discord"), parser.parse(it))
        }
    }
    @Test fun `flashlight has explicit direction`() {
        assertEquals(IntentType.FLASHLIGHT_ON, parser.parse("Enable flashlight").type)
        assertEquals(IntentType.FLASHLIGHT_OFF, parser.parse("patayin ang flashlight").type)
        assertEquals(IntentType.UNKNOWN, parser.parse("flashlight").type)
    }
    @Test fun `battery questions`() { assertEquals(IntentType.BATTERY_STATUS, parser.parse("How much battery do I have?").type) }
    @Test fun `timer units compose and reject unsafe durations`() {
        assertEquals(3900L, parser.parse("Set a timer for 1 hour 5 minutes").value)
        assertEquals(600L, parser.parse("timer for ten minutes").value)
        assertEquals(0.4, parser.parse("timer for 99999 hours").confidence, 0.01)
        assertEquals(0.4, parser.parse("timer for 0 seconds").confidence, 0.01)
    }
    @Test fun `confirmation must be an entire utterance`() {
        assertEquals(IntentType.CONFIRM, parser.parse("yes").type)
        assertNotEquals(IntentType.CONFIRM, parser.parse("yes send it to everyone").type)
        assertEquals(IntentType.CANCEL, parser.parse("hindi").type)
    }
    @Test fun `contact and message slots`() {
        assertEquals("mom", parser.parse("Call Mom").argument)
        val message = parser.parse("Send a message to John saying I will be there at five")
        assertEquals(IntentType.MESSAGE, message.type)
        assertEquals("john", message.argument)
        assertEquals("I will be there at five", message.body)
    }
    @Test fun `volume and music`() {
        assertEquals(50L, parser.parse("set volume to 50 percent").value)
        assertEquals(IntentType.VOLUME_DOWN, parser.parse("decrease volume").type)
        assertEquals(IntentType.MUSIC_NEXT, parser.parse("next song").type)
    }
    @Test fun `memory and inventory`() {
        assertEquals("i use discord for school", parser.parse("Remember that I use Discord for school").argument)
        assertEquals(IntentType.RECALL, parser.parse("How do I contact my school group?").type)
        assertEquals(IntentType.SEARCH_APPLICATIONS, parser.parse("find my editing apps").type)
        assertEquals(IntentType.SEARCH_APPLICATIONS, parser.parse("show my programming apps").type)
        assertEquals(IntentType.RECENT_APPLICATIONS, parser.parse("what apps did I install recently").type)
    }
    @Test fun `fuzzy names do not overmatch short targets`() {
        assertTrue(FuzzyMatcher.score("discrod", "Discord") >= 0.7)
        assertEquals(0.0, FuzzyMatcher.score("di", "Discord"), 0.001)
        assertEquals(1.0, FuzzyMatcher.score("SPOTIFY", "Spotify"), 0.001)
    }
    @Test fun `unknown input has zero confidence`() { assertEquals(0.0, parser.parse("flibbertigibbet").confidence, 0.001) }
    @Test fun `messages retain exact casing and punctuation`() {
        assertEquals("Meet me at 5:30, John!", parser.parse("Jarvis, text Mom saying Meet me at 5:30, John!").body)
    }
    @Test fun `invalid numeric inputs do not execute`() {
        assertEquals(IntentType.UNKNOWN, parser.parse("volume 150 percent").type)
        assertEquals(0.4, parser.parse("timer for -5 minutes").confidence, 0.001)
        assertEquals(0.4, parser.parse("timer for 1.5 minutes").confidence, 0.001)
    }
    @Test fun `custom name keeps built in wake phrase and word boundaries`() {
        assertEquals(IntentType.BATTERY_STATUS, parser.parse("Jarvis, battery percentage", "Alex").type)
        assertEquals(IntentType.UNKNOWN, parser.parse("Jarvison open mail").type)
    }
    @Test fun `reported battery phrase and punctuation are understood`() {
        listOf("jarvis... battery parcentage", "Jarvis. Open Facebook.", "hey Jarvis, please open Facebook", "Can you please open Facebook app?").forEach {
            val result = parser.parse(it)
            assertTrue("Rejected: $it", result.type in setOf(IntentType.BATTERY_STATUS, IntentType.OPEN_APPLICATION))
            if (result.type == IntentType.OPEN_APPLICATION) assertTrue(result.argument in setOf("facebook", "facebook app"))
        }
    }
    @Test fun `polite message preserves recipient and body`() {
        val result = parser.parse("Jarvis... please text Mom saying Please call Jarvis, at 5!")
        assertEquals("mom", result.argument)
        assertEquals("Please call Jarvis, at 5!", result.body)
    }
    @Test fun `natural opening phrases work with arbitrary installed app names`() {
        val phrases = listOf(
            "Bring up Nebula Notebook", "Take me to Nebula Notebook", "Go to Nebula Notebook",
            "I want to use Nebula Notebook", "I want to open Nebula Notebook", "I'd like to open Nebula Notebook",
            "I would like to use Nebula Notebook", "Let's open Nebula Notebook", "Let’s launch Nebula Notebook",
            "Can you launch Nebula Notebook", "Could you please bring up Nebula Notebook", "Jarvis... please open up Nebula Notebook"
        )
        phrases.forEach { input ->
            val result = parser.parse(input)
            assertEquals(input, IntentType.OPEN_APPLICATION, result.type)
            assertEquals(input, "nebula notebook", result.argument)
        }
    }
    @Test fun `device keywords inside app labels do not hijack explicit launches`() {
        listOf("Battery Atlas", "Flashlight Notebook", "Timer Garden", "Volume Journal", "My Apps Explorer").forEach { name ->
            val result = parser.parse("bring up $name")
            assertEquals(name, IntentType.OPEN_APPLICATION, result.type)
            assertEquals(name.lowercase(), result.argument)
        }
    }
    @Test fun `message body command words never execute device actions`() {
        val body = "Open Discord, then turn off flashlight and check battery. Confirm?"
        val result = parser.parse("Could you please text Mom saying $body")
        assertEquals(IntentType.MESSAGE, result.type)
        assertEquals("mom", result.argument)
        assertEquals(body, result.body)
        val contact = parser.parse("call Battery Shop")
        assertEquals(IntentType.CALL, contact.type)
        assertEquals("battery shop", contact.argument)
    }
    @Test fun `questions negation and narrative do not become app launches`() {
        listOf("How do I open Discord?", "Don't open Discord", "Do not launch Discord", "I want to know about Discord", "I would like Discord alternatives").forEach {
            assertNotEquals(it, IntentType.OPEN_APPLICATION, parser.parse(it).type)
        }
        assertEquals(IntentType.REMEMBER, parser.parse("Remember that I want to open Discord for school").type)
        assertNotEquals(IntentType.CONFIRM, parser.parse("I want to confirm and call Mom").type)
    }
    @Test fun `natural timer start and translated torch commands retain their actions`() {
        val timer = parser.parse("Can you start a timer for five minutes?")
        assertEquals(IntentType.TIMER, timer.type)
        assertEquals(300L, timer.value)
        assertEquals(IntentType.FLASHLIGHT_ON, parser.parse("Buksan ang flashlight").type)
        assertEquals(IntentType.CAMERA, parser.parse("take me to camera").type)
        assertEquals(IntentType.SETTINGS, parser.parse("go to settings").type)
    }
}
