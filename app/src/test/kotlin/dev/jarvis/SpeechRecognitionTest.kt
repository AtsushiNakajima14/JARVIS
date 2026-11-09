package dev.jarvis

import dev.jarvis.assistant.speech.*
import dev.jarvis.assistant.wakeword.StableWakeDetector
import dev.jarvis.assistant.wakeword.WakeReplay
import dev.jarvis.assistant.wakeword.WakeTranscript
import dev.jarvis.assistant.parser.TransientPcmBuffer
import org.junit.Assert.*
import org.junit.Test

class SpeechRecognitionTest {
    private val selection = OfflineSpeechSelection(listOf("Discord", "Facebook", "YouTube"))
    private fun confident(text: String) = DecodedUtterance(listOf(text), listOf(0.96, 0.99))

    @Test fun `reported discord mistranscription is rescued by actual command decoder`() {
        val result = selection.choose(DecodedUtterance(listOf("this guy")), confident("open discord"))
        assertEquals("open discord", result.first())
        assertEquals("this guy", result[1])
    }
    @Test fun `grammar fragments cannot invent an app action`() {
        assertEquals(listOf("this guy"), selection.choose(DecodedUtterance(listOf("this guy")), confident("discord")))
        assertEquals(listOf("some words"), selection.choose(DecodedUtterance(listOf("some words")), confident("[unk] open discord")))
    }
    @Test fun `only supported complete grammar phrases are accepted`() {
        assertEquals(listOf("some words"), selection.choose(DecodedUtterance(listOf("some words")), confident("battery open discord")))
        assertEquals(listOf("some words"), selection.choose(DecodedUtterance(listOf("some words")), confident("open uninstalled app")))
    }
    @Test fun `low or missing word confidence cannot rescue a command`() {
        val original = DecodedUtterance(listOf("unclear"))
        listOf(emptyList(), listOf(0.4, 0.9), listOf(Double.NaN), listOf(100.0), listOf(0.75, 0.75)).forEach {
            assertEquals(original.alternatives, selection.choose(original, DecodedUtterance(listOf("open discord"), it)))
        }
    }
    @Test fun `contact message memory and confirmation primary text is preserved`() {
        listOf("call Mom", "text Mom saying Hello", "remember that I use Discord for school", "confirm", "cancel").forEach { text ->
            assertEquals(listOf(text), selection.choose(DecodedUtterance(listOf(text)), confident("open discord")))
        }
    }
    @Test fun `grammar never supplies a sensitive action`() {
        val original = DecodedUtterance(listOf("unclear"))
        listOf("call Mom", "text Mom saying Hello", "remember this", "confirm").forEach {
            assertEquals(original.alternatives, selection.choose(original, confident(it)))
        }
    }
    @Test fun `clear command and direction are not overwritten`() {
        assertEquals(listOf("turn off flashlight"), selection.choose(DecodedUtterance(listOf("turn off flashlight")), confident("turn on flashlight")))
        assertEquals(listOf("open facebook"), selection.choose(DecodedUtterance(listOf("open facebook")), confident("open discord")))
    }
    @Test fun `incorrect app slot can use a recognized installed app phrase`() {
        assertEquals("open discord", selection.choose(DecodedUtterance(listOf("open this guy")), confident("open discord")).first())
    }
    @Test fun `native offline speech is preferred and bundled English remains a fallback`() {
        assertEquals(RecognitionBackend.ON_DEVICE, RecognitionPolicy.choose("en-PH", false, true, true))
        assertEquals(RecognitionBackend.OFFLINE_COMMANDS, RecognitionPolicy.choose("en-US", false, false, true))
    }
    @Test fun `legacy network preference cannot enable cloud recognition`() {
        assertEquals(RecognitionBackend.ON_DEVICE, RecognitionPolicy.choose("en-PH", true, true, true))
        assertEquals(RecognitionBackend.OFFLINE_COMMANDS, RecognitionPolicy.choose("en-PH", true, false, false))
        assertEquals(RecognitionBackend.ON_DEVICE, RecognitionPolicy.choose("fil-PH", false, true, true))
    }
    @Test fun `transient false wake from this guy is rejected`() {
        val wake = StableWakeDetector()
        assertFalse(wake.observe("jarvis", false))
        assertFalse(wake.observe("jarvis", false))
        assertFalse(wake.observe("[unk]", false))
        assertFalse(wake.observe("[unk]", true))
    }
    @Test fun `stable or complete wake is accepted`() {
        val wake = StableWakeDetector()
        assertFalse(wake.observe("jarvis", false))
        assertFalse(wake.observe("jarvis", false))
        assertTrue(wake.observe("jarvis", false))
        assertTrue(StableWakeDetector().observe("Jarvis", true))
        assertFalse(StableWakeDetector().observe("jarvison", true))
    }
    @Test fun `grammar can preserve a bare wake when full decoder mishears its name`() {
        assertTrue(selection.isWakeOnly(DecodedUtterance(listOf("travis")), confident("jarvis")))
        assertTrue(selection.isWakeOnly(DecodedUtterance(listOf("service")), confident("jarvis")))
        assertFalse(selection.isWakeOnly(DecodedUtterance(listOf("open discord")), confident("jarvis")))
        assertFalse(selection.isWakeOnly(DecodedUtterance(listOf("call Mom")), confident("jarvis")))
    }
    @Test fun `pre wake replay endpoint cannot execute stale speech`() {
        val buffer = TransientPcmBuffer(4)
        buffer.add(shortArrayOf(1), 1); buffer.add(shortArrayOf(2), 1)
        var frame = 0
        assertNull(WakeReplay.replay(buffer) { _, _ -> frame++; if (frame == 1) SpeechResult(listOf("open discord"), "test") else null })
        assertNull(WakeReplay.replay(buffer) { _, _ -> SpeechResult(listOf("open discord"), "test") })
    }
    @Test fun `only complete wake prefixed final replay frame can complete activation`() {
        val buffer = TransientPcmBuffer(2)
        buffer.add(shortArrayOf(1), 1)
        assertEquals("Jarvis battery percentage", WakeReplay.replay(buffer) { _, _ -> SpeechResult(listOf("Jarvis battery percentage"), "test") }?.text)
        assertTrue(WakeReplay.replay(buffer) { _, _ -> SpeechResult(listOf("travis"), "test", wakeOnly = true) }?.wakeOnly == true)
        buffer.clear()
    }
    @Test fun `merged pre wake speech cannot override the command after Jarvis`() {
        val decoded = WakeTranscript.trim(DecodedUtterance(listOf("battery Jarvis open discord"),
            listOf(0.1, 0.99, 0.98, 0.98), listOf("battery", "jarvis", "open", "discord")))
        assertEquals(listOf("Jarvis open discord"), decoded.alternatives)
        assertEquals(listOf(0.99, 0.98, 0.98), decoded.wordConfidences)
    }
    @Test fun `first activation word is kept without stripping a message body`() {
        assertEquals("Jarvis text Mom saying Tell Jarvis hello", WakeTranscript.trim("background Jarvis text Mom saying Tell Jarvis hello"))
        assertEquals("open discord", WakeTranscript.trim("open discord"))
    }
}
