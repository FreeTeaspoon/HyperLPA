package app.hyperlpa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRequestTest {
    @Test
    fun parsesFullActivationCode() {
        val request = DownloadRequest.parse(
            rawValue = "LPA:1\$smdp.example.com\$matching-id\$1.2.840.113549\$1",
            defaultImei = "123456789012345",
        )

        assertEquals("smdp.example.com", request.smdpAddress)
        assertEquals("matching-id", request.matchingId)
        assertEquals("1.2.840.113549", request.smdpOid)
        assertTrue(request.confirmationCodeRequired)
        assertNull(request.confirmationCode)
        assertFalse(request.hasRequiredConfirmationCode)
        assertEquals("123456789012345", request.imei)
    }

    @Test
    fun confirmationCodeIsSuppliedSeparately() {
        val request = DownloadRequest.parse("LPA:1\$smdp.example.com\$matching-id\$\$1")
            .withConfirmationCode(" 1234 ")

        assertNull(request.smdpOid)
        assertEquals("1234", request.confirmationCode)
        assertTrue(request.hasRequiredConfirmationCode)
    }

    @Test
    fun oidIsNeverTreatedAsConfirmationCode() {
        val request = DownloadRequest.parse("LPA:1\$smdp.example.com\$matching-id\$1.2.3")

        assertEquals("1.2.3", request.smdpOid)
        assertFalse(request.confirmationCodeRequired)
        assertNull(request.confirmationCode)
        assertTrue(request.hasRequiredConfirmationCode)
    }

    @Test
    fun preservesZeroLengthMandatoryMatchingId() {
        val request = DownloadRequest.parse("LPA:1\$smdp.example.com\$\$1.02.3")

        assertEquals("", request.matchingId)
        assertEquals("1.2.3", request.smdpOid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedSmdpOid() {
        DownloadRequest.parse("LPA:1\$smdp.example.com\$matching\$1.not-an-oid.3")
    }

    @Test
    fun acceptsBareSmdpAddress() {
        val request = DownloadRequest.parse("smdp.example.com")

        assertEquals("smdp.example.com", request.smdpAddress)
        assertNull(request.matchingId)
        assertNull(request.smdpOid)
        assertFalse(request.confirmationCodeRequired)
        assertNull(request.confirmationCode)
    }

    @Test
    fun acceptsManuallyEnteredActivationCodeWithoutQrPrefix() {
        val request = DownloadRequest.parse("1\$smdp.example.com\$matching-id")

        assertEquals("smdp.example.com", request.smdpAddress)
        assertEquals("matching-id", request.matchingId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedActivationCodeVersion() {
        DownloadRequest.parse("LPA:2\$smdp.example.com")
    }

    @Test
    fun normalizesRspServerAddress() {
        assertEquals("smdp.example.com:8443", normalizeRspServerAddress(" smdp.example.com:8443 "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRspServerUrlInsteadOfAddress() {
        normalizeRspServerAddress("https://smdp.example.com/path")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialBearingRspAddress() {
        normalizeRspServerAddress("secret@smdp.example.com")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidRspPort() {
        normalizeRspServerAddress("smdp.example.com:99999")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedActivationCodeBeforeSplitting() {
        DownloadRequest.parse("LPA:1\$smdp.example\$${"x".repeat(4_096)}")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownConfirmationCodeFlag() {
        DownloadRequest.parse("LPA:1\$smdp.example\$matching-id\$\$yes")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsExplicitFalseConfirmationCodeFlag() {
        DownloadRequest.parse("LPA:1\$smdp.example\$matching-id\$\$0")
    }

    @Test
    fun ignoresFutureExtensionFields() {
        val request = DownloadRequest.parse(
            "LPA:1\$smdp.example\$matching-id\$1.2.3\$1\$future-extension",
        )

        assertEquals("matching-id", request.matchingId)
        assertEquals("1.2.3", request.smdpOid)
        assertTrue(request.confirmationCodeRequired)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedConfirmationCode() {
        DownloadRequest.parse("LPA:1\$smdp.example\$matching-id\$\$1")
            .withConfirmationCode("1".repeat(129))
    }

    @Test
    fun nicknameLimitCountsUnicodeCodePointsWithoutSplittingSurrogates() {
        val value = "a".repeat(63) + "😀" + "tail"
        val truncated = value.takeUnicodeCodePoints(64)

        assertEquals("a".repeat(63) + "😀", truncated)
        assertEquals(64, truncated.codePointCount(0, truncated.length))
    }
}
