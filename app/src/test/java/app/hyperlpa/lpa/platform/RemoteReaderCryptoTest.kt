package app.hyperlpa.lpa.platform

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteReaderCryptoTest {
    private val key = RemoCardLegacyCbc.key("test-only-token")

    @Test
    fun roundTripsLegacyEnvelope() {
        val plaintext = """{"response":"9000"}"""
        val encoded = RemoCardLegacyCbc.encrypt(plaintext, key)

        assertEquals(plaintext, RemoCardLegacyCbc.decrypt(encoded, key))
    }

    @Test
    fun ivTamperingCannotMasqueradeAsTheExpectedJsonMessage() {
        val plaintext = """{"response":"9000"}"""
        val combined = Base64.getDecoder().decode(RemoCardLegacyCbc.encrypt(plaintext, key))
        combined[0] = (combined[0].toInt() xor 1).toByte()

        val tampered = RemoCardLegacyCbc.decrypt(Base64.getEncoder().encodeToString(combined), key)
        assertNotEquals(plaintext, tampered)
        assertTrue(runCatching { JSONObject(tampered).getString("response") }.isFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedCiphertext() {
        RemoCardLegacyCbc.decrypt(Base64.getEncoder().encodeToString(ByteArray(16)), key)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedCiphertextBeforeDecoding() {
        RemoCardLegacyCbc.decrypt("A".repeat(2_800_000), key)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidKeyLength() {
        RemoCardLegacyCbc.encrypt("{}", ByteArray(16))
    }
}
