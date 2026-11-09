package dev.jarvis

import dev.jarvis.assistant.speech.OfflineLanguagePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineLanguagePolicyTest {
    @Test fun `English phone locale can use an installed English offline pack`() {
        assertEquals("en-US", OfflineLanguagePolicy.installedVariant("en-PH", listOf("fil-PH", "en-US")))
        assertEquals("en-PH", OfflineLanguagePolicy.installedVariant("en-PH", listOf("en-US", "en-PH")))
    }
    @Test fun `different languages and unsupported availability never silently change user choice`() {
        assertEquals("fil-PH", OfflineLanguagePolicy.installedVariant("fil-PH", listOf("en-US")))
        assertEquals("en-PH", OfflineLanguagePolicy.installedVariant("en-PH", emptyList()))
    }
}
