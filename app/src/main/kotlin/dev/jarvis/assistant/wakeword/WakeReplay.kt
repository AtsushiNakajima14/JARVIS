package dev.jarvis.assistant.wakeword

import dev.jarvis.assistant.parser.TransientPcmBuffer
import dev.jarvis.assistant.speech.SpeechResult

object WakeReplay {
    fun replay(audio: TransientPcmBuffer, decode: (ShortArray, Int) -> SpeechResult?): SpeechResult? {
        var finalFrame: SpeechResult? = null
        audio.replay { finalFrame = decode(it, it.size) }
        // Earlier endpoints can be unrelated speech before Jarvis. A later partial
        // means the real request is still in progress and must keep the microphone.
        return finalFrame?.takeIf { result -> result.wakeOnly || result.alternatives.any {
            Regex("^(?:(?:hey|hi|ok|okay)\\s+)?jarvis\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.trim())
        } }
    }
}
