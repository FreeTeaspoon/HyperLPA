package app.hyperlpa.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts remote-reader bearer tokens with a non-exportable Android Keystore key. */
internal class RemoteCredentialCipher(context: Context) {
    private val alias = "${context.packageName}.remote-reader-credentials.v1"

    fun encrypt(endpoint: String, token: String): String {
        val nonce = ByteArray(NonceBytes).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TagBits, nonce))
        cipher.updateAAD(endpoint.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(nonce + encrypted)
    }

    fun decrypt(endpoint: String, encoded: String): String? = runCatching {
        require(encoded.length <= MaxEncodedTokenCharacters)
        val combined = Base64.getDecoder().decode(encoded)
        require(combined.size > NonceBytes + TagBits / 8)
        val nonce = combined.copyOfRange(0, NonceBytes)
        val ciphertext = combined.copyOfRange(NonceBytes, combined.size)
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TagBits, nonce))
        cipher.updateAAD(endpoint.toByteArray(StandardCharsets.UTF_8))
        String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
            .takeIf(::isValidRemoteReaderToken)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val Transformation = "AES/GCM/NoPadding"
        const val NonceBytes = 12
        const val TagBits = 128
        const val MaxEncodedTokenCharacters = 8_192
    }
}
