package dev.jarvis
import dev.jarvis.core.security.LocalCipher
import org.junit.Assert.*
import org.junit.Test
class KeystoreTest {
    @Test fun encryptionIsRandomizedAndAuthenticated() {
        val cipher = LocalCipher()
        val first = cipher.encrypt("private contact")
        val second = cipher.encrypt("private contact")
        assertNotEquals(first, second)
        assertEquals("private contact", cipher.decrypt(first))
        val bytes = android.util.Base64.decode(first.removePrefix("v1:"), android.util.Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = "v1:" + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        assertThrows(Exception::class.java) { cipher.decrypt(tampered) }
    }
}
