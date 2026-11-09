package dev.jarvis

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import dev.jarvis.assistant.speech.*
import dev.jarvis.core.common.AssistantSettings
import dev.jarvis.core.common.assistantSettingsFrom
import org.junit.Assert.*
import org.junit.Test

class GoogleRecognitionPolicyTest {
    private val google = RecognitionServiceCandidate("com.google.android.googlequicksearchbox", "GoogleRecognitionService")
    private val speechServices = RecognitionServiceCandidate("com.google.android.tts", "GoogleSpeechService")
    @Test fun `Google app is chosen explicitly ahead of speech services and arbitrary default`() {
        val other = RecognitionServiceCandidate("com.example.recognizer", "DefaultRecognitionService")
        assertEquals(google, RecognitionPolicy.googleProvider(listOf(other, speechServices, google)))
        assertEquals(RecognitionBackend.ON_DEVICE, RecognitionPolicy.choose("en-PH", true, true, true))
    }
    @Test fun `disabled Google app falls back to enabled Google speech services`() {
        assertEquals(speechServices, RecognitionPolicy.googleProvider(listOf(google.copy(enabled = false), speechServices)))
        assertEquals(speechServices, RecognitionPolicy.googleProvider(listOf(google.copy(applicationEnabled = false), speechServices)))
    }
    @Test fun `Google Go is eligible only when it exposes a real enabled service`() {
        val go = RecognitionServiceCandidate("com.google.android.apps.searchlite", "InstalledRecognitionService")
        assertEquals(go, RecognitionPolicy.googleProvider(listOf(go)))
        assertEquals("Google Go", RecognitionPolicy.providerName(go))
        assertNull(RecognitionPolicy.googleProvider(listOf(go.copy(enabled = false))))
        assertNull(RecognitionPolicy.googleProvider(listOf(go.copy(className = ""))))
    }
    @Test fun `private or disabled services and Google lookalike packages are ineligible`() {
        assertNull(RecognitionPolicy.googleProvider(listOf(google.copy(exported = false), speechServices.copy(enabled = false),
            google.copy(packageName = "com.google.android.googlequicksearchbox.fake"))))
    }
    @Test fun `absent Google never selects an arbitrary cloud provider`() {
        val available = RecognitionPolicy.googleProvider(listOf(RecognitionServiceCandidate("com.other.voice", "Recognition"))) != null
        assertEquals(RecognitionBackend.OFFLINE_COMMANDS, RecognitionPolicy.choose("en-PH", true, false, available))
        assertEquals(RecognitionBackend.ON_DEVICE, RecognitionPolicy.choose("fil-PH", true, true, available))
    }
    @Test fun `English and other languages prefer native offline speech when available`() {
        assertEquals(RecognitionBackend.ON_DEVICE, RecognitionPolicy.choose("en-PH", false, true, true))
        assertEquals(RecognitionBackend.ON_DEVICE, RecognitionPolicy.choose("fil-PH", false, true, true))
    }
    @Test fun `Google failure can use on-device speech and then local commands`() {
        assertEquals(RecognitionBackend.ON_DEVICE, RecognitionPolicy.fallback(RecognitionBackend.PHONE_SERVICE, true))
        assertEquals(RecognitionBackend.OFFLINE_COMMANDS, RecognitionPolicy.fallback(RecognitionBackend.PHONE_SERVICE, false))
        assertEquals(RecognitionBackend.OFFLINE_COMMANDS, RecognitionPolicy.fallback(RecognitionBackend.ON_DEVICE, true))
    }
    @Test fun `app inventory is never used for Google cloud or confirmation bias`() {
        assertFalse(RecognitionPolicy.usesDeviceBias(RecognitionBackend.PHONE_SERVICE, false))
        assertFalse(RecognitionPolicy.usesDeviceBias(RecognitionBackend.PHONE_SERVICE, true))
        assertFalse(RecognitionPolicy.usesDeviceBias(RecognitionBackend.OFFLINE_COMMANDS, false))
        assertFalse(RecognitionPolicy.usesDeviceBias(RecognitionBackend.ON_DEVICE, true))
        assertTrue(RecognitionPolicy.usesDeviceBias(RecognitionBackend.ON_DEVICE, false))
        assertTrue(RecognitionPolicy.phoneBias(true).isEmpty())
        assertTrue(RecognitionPolicy.phoneBias(false).contains("battery percentage"))
        assertFalse(RecognitionPolicy.phoneBias(false).any { it.contains("com.") || it.contains("My Private App") })
    }
    @Test fun `new installs and upgrades never authorize network speech`() {
        assertFalse(AssistantSettings().networkSpeech)
        assertFalse(assistantSettingsFrom(emptyPreferences()).networkSpeech)
        assertFalse(assistantSettingsFrom(preferencesOf(booleanPreferencesKey("network") to false)).networkSpeech)
        assertFalse(assistantSettingsFrom(preferencesOf(booleanPreferencesKey("network") to true)).networkSpeech)
        for (language in listOf("en-PH", "fil-PH")) for (available in listOf(true, false)) {
            assertNotEquals(RecognitionBackend.PHONE_SERVICE, RecognitionPolicy.choose(language, true, available, true))
        }
    }
    @Test fun `local AI defaults and explicit opt outs map to appended preferences`() {
        val defaults = assistantSettingsFrom(emptyPreferences())
        assertFalse(defaults.localAi); assertTrue(defaults.conversation)
        assertTrue(assistantSettingsFrom(preferencesOf(booleanPreferencesKey("local_ai") to true)).localAi)
        val disabled = assistantSettingsFrom(preferencesOf(booleanPreferencesKey("local_ai") to false,
            booleanPreferencesKey("conversation") to false))
        assertFalse(disabled.localAi); assertFalse(disabled.conversation)
        assertTrue(disabled.wakeWord); assertTrue(disabled.history)
    }
    @Test fun `notification content and announcements require separate explicit opt ins`() {
        val defaults = assistantSettingsFrom(emptyPreferences())
        assertFalse(defaults.notificationReading)
        assertFalse(defaults.notificationAnnouncements)
        val enabled = assistantSettingsFrom(preferencesOf(booleanPreferencesKey("notification_reading") to true,
            booleanPreferencesKey("notification_announcements") to true))
        assertTrue(enabled.notificationReading)
        assertTrue(enabled.notificationAnnouncements)
        assertFalse(enabled.notifications)
        val metadataOnly = assistantSettingsFrom(preferencesOf(booleanPreferencesKey("notifications") to true))
        assertFalse(metadataOnly.notificationReading)
        assertFalse(metadataOnly.notificationAnnouncements)
    }
}
