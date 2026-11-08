package dev.jarvis.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Every encryption uses a new IV. Key material never leaves Android Keystore. */
interface TextCipher {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}
@Singleton
class LocalCipher @Inject constructor() : TextCipher {
    private var cachedKey: SecretKey? = null
    @Synchronized private fun key(): SecretKey {
        cachedKey?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("jarvis.local.v1", null) as? SecretKey)?.let { cachedKey = it; return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("jarvis.local.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey().also { cachedKey = it }
    }
    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return "v1:" + Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    override fun decrypt(value: String): String {
        require(value.startsWith("v1:")) { "Unsupported encrypted value" }
        val data = Base64.decode(value.removePrefix("v1:"), Base64.NO_WRAP)
        require(data.size >= 28)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        }.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
    }
}

