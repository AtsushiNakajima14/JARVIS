package dev.jarvis

import dev.jarvis.core.security.PrivateCommandPolicy
import org.junit.Assert.*
import org.junit.Test

class PrivateCommandPolicyTest {
    @Test fun `credential disclosures and storage requests never become chat text`() {
        for (text in listOf("my PIN is 3816", "Jarvis remember my password is secret-word",
            "remember that my passcode is 4817", "use my unlock code 3816",
            "my phone PIN is", "what is my pin", "tell me my password", "unlock my phone using 3816")) {
            assertTrue(text, PrivateCommandPolicy.isCredentialRequest(text))
            assertEquals("[Credential omitted]", PrivateCommandPolicy.transcript(text))
        }
    }
    @Test fun `ordinary unlock and general security questions remain usable`() {
        for (text in listOf("unlock my phone", "what is a PIN", "how do I change my password",
            "why are passwords important", "open Discord", "remember that I prefer dark mode")) {
            assertFalse(text, PrivateCommandPolicy.isCredentialRequest(text))
            assertEquals(text, PrivateCommandPolicy.transcript(text))
        }
    }
}
