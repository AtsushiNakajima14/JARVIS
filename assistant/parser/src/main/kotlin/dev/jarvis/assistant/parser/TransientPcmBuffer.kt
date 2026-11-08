package dev.jarvis.assistant.parser

/** Bounded pre-roll in RAM only; retain the beginning of a command during wake detection. */
class TransientPcmBuffer(private val maxFrames: Int = 8) {
    private val frames = ArrayDeque<ShortArray>()
    init { require(maxFrames > 0) }
    fun add(samples: ShortArray, count: Int) {
        require(count in 0..samples.size)
        if (frames.size == maxFrames) frames.removeFirst().fill(0)
        frames.addLast(samples.copyOf(count))
    }
    fun replay(consume: (ShortArray) -> Unit) { frames.forEach(consume) }
    fun clear() { frames.forEach { it.fill(0) }; frames.clear() }
}
