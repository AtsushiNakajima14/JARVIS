package dev.jarvis

import dev.jarvis.assistant.parser.CommandParser
import dev.jarvis.assistant.parser.IntentType
import dev.jarvis.assistant.parser.SpeechSelection
import dev.jarvis.assistant.speech.DecodedUtterance
import dev.jarvis.assistant.speech.OfflineSpeechSelection
import dev.jarvis.assistant.speech.VoskResultReader
import dev.jarvis.skills.time.ClockAnswer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONArray
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TimeSpeechTest {
    private val selection = OfflineSpeechSelection(listOf("Messenger"))
    private fun grammar(text: String, confidence: Double = .98) =
        DecodedUtterance(listOf(text), List(text.split(' ').size) { confidence })

    @Test fun `confident whole time question can rescue unrelated free transcription`() {
        val free = VoskResultReader.read("""{"alternatives":[{"text":"what is the thing","confidence":22.1}]}""")
        val constrained = VoskResultReader.read("""{"text":"what is the time","result":[{"word":"what","conf":0.99},{"word":"is","conf":0.99},{"word":"the","conf":0.99},{"word":"time","conf":0.98}]}""")
        val result = selection.choose(free, constrained)
        assertEquals("what is the time", result.first())
        assertEquals(IntentType.TIME_STATUS, CommandParser().parse(result.first()).type)
    }

    @Test fun `actual native clock fixtures resolve across two local synthetic voices`() {
        val fixtures = JSONArray(requireNotNull(javaClass.getResourceAsStream("/native-clock-regression.json"))
            .bufferedReader().use { it.readText() })
        assertEquals(10, fixtures.length())
        repeat(fixtures.length()) { index ->
            val fixture = fixtures.getJSONObject(index)
            val expected = CommandParser().parse(fixture.getString("expected")).type
            assertTrue(expected in setOf(IntentType.TIME_STATUS, IntentType.DATE_STATUS))
            val candidates = selection.choose(VoskResultReader.read(fixture.getJSONObject("free").toString()),
                VoskResultReader.read(fixture.getJSONObject("grammar").toString()))
            assertEquals(fixture.getString("voice") + ": " + fixture.getString("expected"), expected,
                CommandParser().parse(candidates.first()).type)
        }
    }

    @Test fun `time grammar cannot overwrite private data negation or a different natural goal`() {
        listOf("text Mom saying what is the time", "remember my preferred time is morning",
            "don't tell me the time", "I need to talk to someone", "what should I do today")
            .forEach { assertEquals(listOf(it), selection.choose(DecodedUtterance(listOf(it)), grammar("what is the time"))) }
    }

    @Test fun `a complete thyme clock question is corrected without touching dictated herbs`() {
        assertEquals("what is the time", selection.choose(DecodedUtterance(listOf("what is the thyme")), grammar("what is the time")).first())
        assertEquals("Jarvis what is the time", SpeechSelection().choose(listOf("Jarvis what is the thyme")))
        for (text in listOf("text Mom saying what is the thyme", "remember thyme is a herb", "what is thyme used for", "what is time travel"))
            assertEquals(listOf(text), selection.choose(DecodedUtterance(listOf(text)), grammar("what is the time")))
    }

    @Test fun `missing or weak clock confidence keeps original speech`() {
        val original = DecodedUtterance(listOf("unclear words"))
        assertEquals(original.alternatives, selection.choose(original, grammar("what is the time", .5)))
        assertEquals(original.alternatives, selection.choose(original, DecodedUtterance(listOf("what is the time"))))
        assertEquals(original.alternatives, selection.choose(original, grammar("time battery")))
    }

    @Test fun `platform alternatives can rescue one clock goal but never choose between time and date`() {
        val alternatives = SpeechSelection()
        assertEquals("what is the time", alternatives.choose(listOf("unclear words", "what is the time")))
        assertEquals("unclear words", alternatives.choose(listOf("unclear words", "what is the time", "what is the date")))
    }

    @Test fun `time replies use the phone zone with no model or network`() {
        val clock = Clock.fixed(Instant.parse("2026-11-09T01:30:00Z"), ZoneId.of("Asia/Manila"))
        assertTrue(ClockAnswer.reply(IntentType.TIME_STATUS, clock).contains("9:30"))
        assertTrue(ClockAnswer.reply(IntentType.DATE_STATUS, clock).contains("2026"))
        assertFalse(ClockAnswer.reply(IntentType.TIME_STATUS, clock).contains("1:30"))
    }
}
