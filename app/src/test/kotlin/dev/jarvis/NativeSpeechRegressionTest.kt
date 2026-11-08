package dev.jarvis

import android.app.Application
import dev.jarvis.assistant.parser.*
import dev.jarvis.assistant.speech.*
import dev.jarvis.assistant.wakeword.WakeTranscript
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeSpeechRegressionTest {
    @Test fun `actual native decoder results reach the intended Kotlin command without stale audio`() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("speech/native-regressions.json")!!.bufferedReader().use { it.readText() }
        val data = JSONObject(fixture)
        val names = data.getJSONArray("appLabels").let { array -> (0 until array.length()).map { array.getString(it) } }
        val cases = data.getJSONArray("cases")
        assertEquals(8, cases.length())
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val rawFree = VoskResultReader.read(case.getJSONObject("free").toString())
            val rawGrammar = VoskResultReader.read(case.getJSONObject("grammar").toString())
            val fromWake = case.getBoolean("fromWake")
            val free = if (fromWake) WakeTranscript.trim(rawFree) else rawFree
            val grammar = if (fromWake) WakeTranscript.trim(rawGrammar) else rawGrammar
            val selected = OfflineSpeechSelection(names).choose(free, grammar)
            val text = SpeechSelection().choose(selected)
            val command = CommandParser().parse(text)
            assertEquals(case.getString("name"), case.getString("expectedType"), command.type.name)
            if (case.has("expectedArgument")) assertEquals(case.getString("name"), case.getString("expectedArgument"), command.argument)
        }
    }
}
