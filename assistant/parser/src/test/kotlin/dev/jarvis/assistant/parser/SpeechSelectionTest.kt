package dev.jarvis.assistant.parser

import org.junit.Assert.*
import org.junit.Test

class SpeechSelectionTest {
    private val selection = SpeechSelection()
    @Test fun `usable alternate rescues unclear speech`() {
        assertEquals("battery percentage", selection.choose(listOf("better percentage", "battery percentage")))
        assertEquals("open Facebook", selection.choose(listOf("oh pen face book", "open Facebook")))
    }
    @Test fun `ambiguous alternatives cannot pick an action`() {
        assertEquals("unclear", selection.choose(listOf("unclear", "turn on flashlight", "turn off flashlight")))
    }
    @Test fun `secondary hypotheses never authorize calls messages memory or confirmations`() {
        listOf("confirm", "call Mom", "text Mom saying Hello", "remember my secret").forEach {
            assertEquals("unclear", selection.choose(listOf("unclear", it)))
        }
        assertEquals("no", selection.choose(listOf("no", "confirm"), confirmation = true))
    }
    @Test fun `clear first choice is preserved`() {
        assertEquals("turn off flashlight", selection.choose(listOf("turn off flashlight", "turn on flashlight")))
    }
    @Test fun `spoken app names and spaces match installed labels`() {
        val apps = listOf(NamedApplication("fb", "Facebook"), NamedApplication("yt", "YouTube"), NamedApplication("tt", "TikTok"), NamedApplication("chrome", "Google Chrome"))
        assertEquals("fb", ApplicationMatcher.find("face book", apps))
        assertEquals("yt", ApplicationMatcher.find("you tube", apps))
        assertEquals("tt", ApplicationMatcher.find("tick tock", apps))
        assertEquals("chrome", ApplicationMatcher.find("chrome", apps))
    }
    @Test fun `equally named apps do not produce an arbitrary launch`() {
        assertNull(ApplicationMatcher.find("chat", listOf(NamedApplication("one", "Chat"), NamedApplication("two", "Chat"))))
    }
    @Test fun `wake pre roll retains copied frames and clears audio`() {
        val buffer = TransientPcmBuffer(2)
        val input = shortArrayOf(1, 2, 3)
        buffer.add(input, 3); input.fill(9)
        buffer.add(shortArrayOf(4, 5), 2)
        val seen = mutableListOf<ShortArray>()
        buffer.replay { seen += it }
        assertArrayEquals(shortArrayOf(1, 2, 3), seen.first())
        buffer.add(shortArrayOf(6), 1)
        assertTrue(seen.first().all { it == 0.toShort() })
        buffer.clear()
        assertTrue(seen.last().all { it == 0.toShort() })
        buffer.replay { fail("Audio remained after clearing") }
    }
}
