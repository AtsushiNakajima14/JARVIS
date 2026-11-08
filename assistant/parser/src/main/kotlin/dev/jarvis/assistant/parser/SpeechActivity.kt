package dev.jarvis.assistant.parser

/** Broad audio-energy gate for deferring alerts, not a speech or wake-word recognizer. */
object SpeechActivity {
    const val DEFAULT_RMS_THRESHOLD = 400

    /** Reads only valid PCM samples and compares squared RMS without allocating or taking a square root. */
    fun isActive(samples: ShortArray, count: Int = samples.size, thresholdRms: Int = DEFAULT_RMS_THRESHOLD): Boolean {
        require(count in 0..samples.size) { "PCM count must stay within the supplied buffer" }
        require(thresholdRms in 1..32768) { "PCM threshold must be within the signed 16-bit amplitude range" }
        if (count == 0) return false
        val minimumEnergy = thresholdRms.toLong() * thresholdRms * count
        var energy = 0L
        for (index in 0 until count) {
            val sample = samples[index].toLong()
            energy += sample * sample
            // Remaining terms cannot reduce energy. Long arithmetic also covers
            // full-scale PCM and buffers large enough to overflow an Int sum.
            if (energy >= minimumEnergy) return true
        }
        return false
    }
}
