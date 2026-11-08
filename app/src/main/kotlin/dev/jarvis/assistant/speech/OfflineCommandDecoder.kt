package dev.jarvis.assistant.speech

import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer
import dev.jarvis.assistant.wakeword.WakeTranscript

/** Both decoders consume the same microphone frames and share one immutable model. */
class OfflineCommandDecoder(model: Model, phrases: List<String>, appLabels: List<String>, constrained: Boolean = true,
    private val fromWake: Boolean = false) : AutoCloseable {
    private val free = Recognizer(model, 16000f).apply { setMaxAlternatives(5) }
    private val grammar = if (constrained) try {
        Recognizer(model, 16000f, JSONArray(phrases).toString()).apply { setWords(true) }
    } catch (error: Exception) { free.close(); throw error } else null
    private val selection = OfflineSpeechSelection(appLabels)
    private val grammarParts = mutableListOf<DecodedUtterance>()
    fun accept(samples: ShortArray, count: Int): SpeechResult? {
        val completed = free.acceptWaveForm(samples, count)
        if (grammar?.acceptWaveForm(samples, count) == true) grammarParts += VoskResultReader.read(grammar.result)
        return if (completed) finish(VoskResultReader.read(free.result)) else null
    }
    fun partial(): String = VoskResultReader.partial(free.partialResult)
    fun finish(): SpeechResult = finish(VoskResultReader.read(free.finalResult))
    private fun finish(rawFreeText: DecodedUtterance): SpeechResult {
        grammar?.let { grammarParts += VoskResultReader.read(it.finalResult) }
        val rawGrammar = if (grammar == null) null else DecodedUtterance(
            listOf(grammarParts.mapNotNull { it.alternatives.firstOrNull() }.joinToString(" ").trim()),
            grammarParts.flatMap { it.wordConfidences }, grammarParts.flatMap { it.words })
        val freeText = if (fromWake) WakeTranscript.trim(rawFreeText) else rawFreeText
        val joinedGrammar = if (fromWake && rawGrammar != null) WakeTranscript.trim(rawGrammar) else rawGrammar
        val selected = selection.choose(freeText, joinedGrammar)
        grammarParts.clear()
        return SpeechResult(selected, "Offline English · device commands", selection.isWakeOnly(freeText, joinedGrammar))
    }
    override fun close() { try { grammar?.close() } finally { free.close() } }
}
