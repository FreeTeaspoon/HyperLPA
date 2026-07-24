package app.hyperlpa.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppSettingsValidationTest {
    @Test
    fun aidsAreNormalizedDeduplicatedAndBoundedByIsoLength() {
        val valid = "a0000005591010ffffffff8900000100"
        val normalized = normalizeIsdrAids(
            listOf(
                "  $valid  ",
                valid.uppercase(),
                "A0000001", // Four bytes: too short.
                "A0000005591010FFFFFFFF890000010011", // Seventeen bytes: too long.
                "not-hex",
            ),
        )

        assertEquals(listOf(valid.uppercase()), normalized)
    }

    @Test
    fun atMostSixteenAidCandidatesAreRetained() {
        val candidates = (0 until 20).map { index -> "A00000055910%04X".format(index) }

        assertEquals(16, normalizeIsdrAids(candidates).size)
    }

    @Test
    fun authoredAidListsAreRejectedInsteadOfSilentlyDroppingEntries() {
        assertEquals(
            listOf("A00000055910"),
            validateIsdrAids(listOf(" a00000055910 ")),
        )
        val invalid = assertThrows(IsdrAidValidationException::class.java) {
            validateIsdrAids(listOf("A0000001"))
        }
        assertEquals(IsdrAidValidationError.INVALID_AID, invalid.reason)
        assertEquals(1, invalid.lineNumber)

        val duplicate = assertThrows(IsdrAidValidationException::class.java) {
            validateIsdrAids(listOf("A00000055910", "a00000055910"))
        }
        assertEquals(IsdrAidValidationError.DUPLICATE_AID, duplicate.reason)

        val excess = assertThrows(IsdrAidValidationException::class.java) {
            validateIsdrAids((0..MaximumAidCandidates).map { index -> "A0000005%04X".format(index) })
        }
        assertEquals(IsdrAidValidationError.TOO_MANY, excess.reason)
    }

    @Test
    fun remoteReaderCredentialsAreRemovedFromCanonicalUrl() {
        val parsed = parseRemoteReaderSetting("https://reader-token@Example.COM:8443/remocard/")

        assertEquals("https://example.com:8443/remocard", parsed.endpointUrl)
        assertEquals("reader-token", parsed.bearerToken)
    }

    @Test
    fun remoteReaderCredentialPreservesLiteralAndPercentEncodedPlus() {
        val literal = parseRemoteReaderSetting("https://token+part@example.com")
        val encoded = parseRemoteReaderSetting("https://token%2Bpart@example.com")

        assertEquals("token+part", literal.bearerToken)
        assertEquals("token+part", encoded.bearerToken)
    }

    @Test
    fun remoteReaderCredentialLimitIsMeasuredInUtf8Bytes() {
        val oversized = "€".repeat(1_366)

        assertThrows(IllegalArgumentException::class.java) {
            parseRemoteReaderSetting("https://${oversized}@example.com")
        }
    }

    @Test
    fun cleanRemoteReaderUrlHasNoCredential() {
        val parsed = parseRemoteReaderSetting("https://example.com")

        assertEquals("https://example.com", parsed.endpointUrl)
        assertNull(parsed.bearerToken)
    }

    @Test
    fun insecureOrAmbiguousRemoteReaderUrlsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            parseRemoteReaderSetting("http://example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRemoteReaderSetting("https://example.com/path?token=secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRemoteReaderSetting("https://token%0Ainjected@example.com")
        }
    }

    @Test
    fun authoredRemoteReaderListsAreRejectedInsteadOfSilentlyDroppingEntries() {
        val invalid = assertThrows(RemoteReaderSettingsValidationException::class.java) {
            validateRemoteReaderSettings(listOf("https://one.example", "http://two.example"))
        }
        assertEquals(RemoteReaderSettingsValidationError.INVALID_ENDPOINT, invalid.reason)
        assertEquals(2, invalid.lineNumber)

        val duplicate = assertThrows(RemoteReaderSettingsValidationException::class.java) {
            validateRemoteReaderSettings(listOf("https://EXAMPLE.com/", "https://example.com"))
        }
        assertEquals(RemoteReaderSettingsValidationError.DUPLICATE_ENDPOINT, duplicate.reason)
        assertEquals(2, duplicate.lineNumber)

        val excess = assertThrows(RemoteReaderSettingsValidationException::class.java) {
            validateRemoteReaderSettings(
                (0..MaximumRemoteReaderEndpoints).map { index -> "https://$index.example" },
            )
        }
        assertEquals(RemoteReaderSettingsValidationError.TOO_MANY, excess.reason)
    }

    @Test
    fun bearerTokenUsesTheRfcB64TokenAlphabet() {
        listOf(
            "abc.DEF-123_~+/==",
            "eyJhbGciOiJIUzI1NiJ9.payload.signature",
        ).forEach { token -> assertEquals(true, isValidRemoteReaderToken(token)) }

        listOf(
            "",
            "has space",
            "unicode-€",
            "control\n",
            "=",
            "===",
            "padding=inside=value",
            "delete\u007f",
        ).forEach { token -> assertEquals(false, isValidRemoteReaderToken(token)) }
    }
}
