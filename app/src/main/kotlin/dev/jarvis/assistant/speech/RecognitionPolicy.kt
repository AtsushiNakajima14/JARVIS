package dev.jarvis.assistant.speech

enum class RecognitionBackend { OFFLINE_COMMANDS, ON_DEVICE, PHONE_SERVICE }

data class RecognitionServiceCandidate(
    val packageName: String,
    val className: String,
    val enabled: Boolean = true,
    val applicationEnabled: Boolean = true,
    val exported: Boolean = true
)

object RecognitionPolicy {
    // Match complete package names, never a prefix or the arbitrary default provider.
    private val googlePackages = listOf(
        "com.google.android.googlequicksearchbox", "com.google.android.tts",
        "com.google.android.apps.searchlite", "com.google.android.voicesearch"
    )
    fun googleProvider(services: List<RecognitionServiceCandidate>): RecognitionServiceCandidate? = services
        .filter { it.packageName in googlePackages && it.className.isNotBlank() &&
            it.enabled && it.applicationEnabled && it.exported }
        .minWithOrNull(compareBy<RecognitionServiceCandidate> { googlePackages.indexOf(it.packageName) }.thenBy { it.className })
    fun providerName(provider: RecognitionServiceCandidate): String = when (provider.packageName) {
        "com.google.android.googlequicksearchbox" -> "Google app"
        "com.google.android.tts" -> "Speech Services by Google"
        "com.google.android.apps.searchlite" -> "Google Go"
        else -> "Google Voice Search"
    }

    /** The companion never falls back to a network recognizer, including after upgrading old settings. */
    @Suppress("UNUSED_PARAMETER")
    fun choose(language: String, networkAllowed: Boolean, onDeviceAvailable: Boolean, phoneAvailable: Boolean): RecognitionBackend = when {
        onDeviceAvailable -> RecognitionBackend.ON_DEVICE
        else -> RecognitionBackend.OFFLINE_COMMANDS
    }
    fun fallback(failed: RecognitionBackend, onDeviceAvailable: Boolean): RecognitionBackend =
        if (failed == RecognitionBackend.PHONE_SERVICE && onDeviceAvailable) RecognitionBackend.ON_DEVICE
        else RecognitionBackend.OFFLINE_COMMANDS
    fun usesDeviceBias(backend: RecognitionBackend, confirmation: Boolean): Boolean =
        backend == RecognitionBackend.ON_DEVICE && !confirmation
    /** Fixed public phrases only; never derive Google's hints from the user's app list. */
    fun phoneBias(confirmation: Boolean): List<String> = if (confirmation) emptyList() else listOf(
        "battery percentage", "battery status", "turn on flashlight", "turn off flashlight",
        "increase volume", "decrease volume", "open camera", "set a timer", "pause music", "play music",
        "next song", "previous song", "show device information", "check storage", "check memory usage",
        "read my notifications", "who messaged me", "give me a briefing", "who made you", "unlock my phone"
    )
}
