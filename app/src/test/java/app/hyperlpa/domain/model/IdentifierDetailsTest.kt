package app.hyperlpa.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentifierDetailsTest {
    @Test
    fun `analyzes a valid ICCID`() {
        val details = analyzeIccid("89000000000000000004")

        assertEquals(true, details.checksumValid)
        assertEquals("8900000", details.issuerPrefix)
    }

    @Test
    fun `reports an invalid ICCID checksum`() {
        val details = analyzeIccid("89000000000000000005")

        assertEquals(false, details.checksumValid)
    }

    @Test
    fun `does not infer details from nonnumeric values`() {
        val details = analyzeIccid("not-an-iccid")

        assertNull(details.checksumValid)
        assertNull(details.issuerPrefix)
    }
}
