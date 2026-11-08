package dev.jarvis.assistant.parser

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class SpeechActivityTest {
    @Test fun `silence and quiet background noise do not delay alerts`() {
        assertFalse(SpeechActivity.isActive(ShortArray(3200)))
        assertFalse(SpeechActivity.isActive(ShortArray(3200) { if (it % 2 == 0) 200 else -200 }))
        assertFalse(SpeechActivity.isActive(shortArrayOf(), 0))
    }

    @Test fun `voice level energy is visible before any wake word is decoded`() {
        val voice = ShortArray(3200) { (800 * sin(2 * PI * 200 * it / 16000)).toInt().toShort() }
        assertTrue(SpeechActivity.isActive(voice))
        assertTrue(SpeechActivity.isActive(ShortArray(3200) { if (it % 2 == 0) 800 else -800 }))
    }

    @Test fun `a listening tone also defers alerts conservatively`() {
        val tone = ShortArray(1280) { (8191 * sin(2 * PI * 1000 * it / 16000)).toInt().toShort() }
        assertTrue(SpeechActivity.isActive(tone))
    }

    @Test fun `unread buffer tails are ignored`() {
        val pcm = ShortArray(3200) { if (it < 100) 100 else Short.MAX_VALUE }
        assertFalse(SpeechActivity.isActive(pcm, count = 100))
        assertTrue(SpeechActivity.isActive(pcm))
    }

    @Test fun `large buffers and threshold boundary use overflow safe energy sums`() {
        assertFalse(SpeechActivity.isActive(ShortArray(100_000) { 399 }))
        assertTrue(SpeechActivity.isActive(ShortArray(100_000) { 400 }))
        assertFalse(SpeechActivity.isActive(ShortArray(3200) { Short.MAX_VALUE }, thresholdRms = 32768))
        assertTrue(SpeechActivity.isActive(ShortArray(3200) { Short.MIN_VALUE }, thresholdRms = 32768))
    }

    @Test fun `invalid sample bounds and thresholds fail before reading memory`() {
        for (count in listOf(-1, 3)) assertThrows(IllegalArgumentException::class.java) {
            SpeechActivity.isActive(shortArrayOf(1, 2), count)
        }
        for (threshold in listOf(0, -1, 32769, Int.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
            SpeechActivity.isActive(shortArrayOf(1, 2), thresholdRms = threshold)
        }
    }
}
