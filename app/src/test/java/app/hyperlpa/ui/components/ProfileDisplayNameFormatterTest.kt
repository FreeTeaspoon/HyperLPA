package app.hyperlpa.ui.components

import app.hyperlpa.data.settings.PhoneFormatStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDisplayNameFormatterTest {
    @Test
    fun formatsInternationalNumberWithGroupedSpacing() {
        val result = formatProfileDisplayName(
            rawName = "amaysim 03 +61 493621666",
            strategy = PhoneFormatStrategy.INTERNATIONAL_ONLY,
            mcc = "505",
            iccid = "8961026025407240300",
        )

        assertEquals("amaysim 03 +61 493 621 666", result.fullText)
        assertEquals("amaysim 03", result.nameText)
        assertEquals("+61 493 621 666", result.phoneText)
        assertTrue(result.hasPhoneNumber)
    }

    @Test
    fun mobileStrategyFormatsNationalMobileNumber() {
        val result = formatProfileDisplayName(
            rawName = "amaysim 0493621666",
            strategy = PhoneFormatStrategy.INTERNATIONAL_AND_MOBILE,
            mcc = "505",
        )

        assertEquals("amaysim +61 493 621 666", result.fullText)
        assertEquals("amaysim", result.nameText)
        assertEquals("+61 493 621 666", result.phoneText)
    }

    @Test
    fun internationalOnlyLeavesNationalNumberUntouched() {
        val result = formatProfileDisplayName(
            rawName = "amaysim 0493621666",
            strategy = PhoneFormatStrategy.INTERNATIONAL_ONLY,
            mcc = "505",
        )

        assertEquals("amaysim 0493621666", result.fullText)
        assertFalse(result.hasPhoneNumber)
    }

    @Test
    fun allNumbersStrategyFormatsNationalFixedLine() {
        val result = formatProfileDisplayName(
            rawName = "Office 03 9123 4567",
            strategy = PhoneFormatStrategy.INTERNATIONAL_AND_ALL,
            mcc = "505",
        )

        assertEquals("Office +61 3 9123 4567", result.fullText)
        assertEquals("Office", result.nameText)
        assertEquals("+61 3 9123 4567", result.phoneText)
    }

    @Test
    fun offLeavesPhoneNumberUntouched() {
        val rawName = "amaysim +61 493621666"
        val result = formatProfileDisplayName(
            rawName = rawName,
            strategy = PhoneFormatStrategy.OFF,
            mcc = "505",
        )

        assertEquals(rawName, result.fullText)
        assertFalse(result.hasPhoneNumber)
    }

    @Test
    fun iccidCountryCodeProvidesRegionWhenMccIsMissing() {
        val result = formatProfileDisplayName(
            rawName = "amaysim 0493621666",
            strategy = PhoneFormatStrategy.INTERNATIONAL_AND_MOBILE,
            iccid = "8961026025407240300",
        )

        assertEquals("amaysim +61 493 621 666", result.fullText)
    }
}
