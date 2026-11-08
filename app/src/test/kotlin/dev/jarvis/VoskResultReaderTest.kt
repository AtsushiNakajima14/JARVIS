package dev.jarvis

import android.app.Application
import dev.jarvis.assistant.speech.VoskResultReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoskResultReaderTest {
    @Test fun `single best grammar reads word confidence`() {
        val decoded = VoskResultReader.read("""{"text":"open discord","result":[{"word":"open","conf":0.98},{"word":"discord","conf":0.95}]}""")
        assertEquals(listOf("open discord"), decoded.alternatives)
        assertEquals(listOf(0.98, 0.95), decoded.wordConfidences)
    }
    @Test fun `n best results are read instead of silently becoming empty`() {
        val decoded = VoskResultReader.read("""{"alternatives":[{"text":"this guy","confidence":140.2},{"text":"open discord","confidence":137.5}]}""")
        assertEquals(listOf("this guy", "open discord"), decoded.alternatives)
        assertTrue(decoded.wordConfidences.isEmpty())
    }
    @Test fun `likelihood and timestamps are never treated as word confidence`() {
        val decoded = VoskResultReader.read("""{"alternatives":[{"text":"open discord","confidence":195.7,"result":[{"word":"discord","start":0.2,"end":0.5}]}]}""")
        assertTrue(decoded.wordConfidences.isEmpty())
    }
    @Test fun `empty and partial results are handled separately`() {
        assertTrue(VoskResultReader.read("""{"text":""}""").alternatives.isEmpty())
        assertTrue(VoskResultReader.read("""{"alternatives":[]}""").alternatives.isEmpty())
        assertEquals("jarvis open", VoskResultReader.partial("""{"partial":"jarvis open"}"""))
    }
}
