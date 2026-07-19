package app.hyperlpa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRequestTest {
    @Test
    fun parsesFullActivationCode() {
        val request = DownloadRequest.parse(
            rawValue = "LPA:1\$smdp.example.com\$matching-id\$1234",
            defaultImei = "123456789012345",
        )

        assertEquals("smdp.example.com", request.smdpAddress)
        assertEquals("matching-id", request.matchingId)
        assertEquals("1234", request.confirmationCode)
        assertEquals("123456789012345", request.imei)
    }

    @Test
    fun acceptsBareSmdpAddress() {
        val request = DownloadRequest.parse("smdp.example.com")

        assertEquals("smdp.example.com", request.smdpAddress)
        assertNull(request.matchingId)
        assertNull(request.confirmationCode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedActivationCodeVersion() {
        DownloadRequest.parse("LPA:2\$smdp.example.com")
    }
}

