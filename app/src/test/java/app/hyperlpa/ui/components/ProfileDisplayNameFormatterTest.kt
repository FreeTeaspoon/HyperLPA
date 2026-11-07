package app.hyperlpa.ui.components

import app.hyperlpa.data.settings.PhoneFormatStrategy
import app.hyperlpa.data.settings.ProfileNameRedactionMode
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDisplayNameFormatterTest {
    // AU and UK phone examples use reserved fictional ranges. ICCIDs are fabricated.

    @Test
    fun providerOnlyRedactionKeepsProviderWhenItIsNotInTheDisplayName() {
        val result = formatProfileDisplayName(
            profile = profile(name = "Travel SIM", providerName = "giffgaff"),
            strategy = PhoneFormatStrategy.OFF,
            fallback = "Profile",
            redactionMode = ProfileNameRedactionMode.PROVIDER_ONLY,
        )

        assertEquals("giffgaff", result.fullText)
        assertEquals("giffgaff", result.nameText)
        assertFalse(result.hasPhoneNumber)
    }

    @Test
    fun numberRedactionUsesFixedMaskForPhoneNumbersWhenFormattingIsOff() {
        val result = formatProfileDisplayName(
            profile = profile(name = "giffgaff 1 +44 7700 900123", providerName = "giffgaff"),
            strategy = PhoneFormatStrategy.OFF,
            fallback = "Profile",
            redactionMode = ProfileNameRedactionMode.NUMBERS,
        )

        assertEquals("giffgaff ********", result.fullText)
        assertFalse(result.fullText.any { it.isDigit() || it == '+' })
        assertFalse(result.fullText.substringAfter("giffgaff ").contains(' '))
        assertFalse(result.hasPhoneNumber)
    }

    @Test
    fun numberRedactionMasksEntireNameWhenProviderIsNotPresent() {
        val result = formatProfileDisplayName(
            profile = profile(name = "EXP Vietnam 20GB 5 days", providerName = "GigSky"),
            strategy = PhoneFormatStrategy.OFF,
            fallback = "Profile",
            redactionMode = ProfileNameRedactionMode.NUMBERS,
        )

        assertEquals("********", result.fullText)
        assertEquals("********", result.nameText)
        assertFalse(result.hasPhoneNumber)
    }

    @Test
    fun formatsInternationalNumberWithGroupedSpacing() {
        val result = formatProfileDisplayName(
            rawName = "amaysim 03 +61 491570006",
            strategy = PhoneFormatStrategy.INTERNATIONAL_ONLY,
            mcc = "505",
            iccid = "8961000000000000001",
        )

        assertEquals("amaysim 03 +61 491 570 006", result.fullText)
        assertEquals("amaysim 03", result.nameText)
        assertEquals("+61 491 570 006", result.phoneText)
        assertTrue(result.hasPhoneNumber)
    }

    @Test
    fun mobileStrategyFormatsNationalMobileNumber() {
        val result = formatProfileDisplayName(
            rawName = "amaysim 0491570006",
            strategy = PhoneFormatStrategy.INTERNATIONAL_AND_MOBILE,
            mcc = "505",
        )

        assertEquals("amaysim +61 491 570 006", result.fullText)
        assertEquals("amaysim", result.nameText)
        assertEquals("+61 491 570 006", result.phoneText)
    }

    @Test
    fun internationalOnlyLeavesNationalNumberUntouched() {
        val result = formatProfileDisplayName(
            rawName = "amaysim 0491570006",
            strategy = PhoneFormatStrategy.INTERNATIONAL_ONLY,
            mcc = "505",
        )

        assertEquals("amaysim 0491570006", result.fullText)
        assertFalse(result.hasPhoneNumber)
    }

    @Test
    fun allNumbersStrategyFormatsNationalFixedLine() {
        val result = formatProfileDisplayName(
            rawName = "Office 02 5550 1234",
            strategy = PhoneFormatStrategy.INTERNATIONAL_AND_ALL,
            mcc = "505",
        )

        assertEquals("Office +61 2 5550 1234", result.fullText)
        assertEquals("Office", result.nameText)
        assertEquals("+61 2 5550 1234", result.phoneText)
    }

    @Test
    fun offLeavesPhoneNumberUntouched() {
        val rawName = "amaysim +61 491570006"
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
            rawName = "amaysim 0491570006",
            strategy = PhoneFormatStrategy.INTERNATIONAL_AND_MOBILE,
            iccid = "8961000000000000001",
        )

        assertEquals("amaysim +61 491 570 006", result.fullText)
    }

    @Test
    fun countryFlagUsesProfileMccRegion() {
        assertEquals("🇦🇺", profileCountryFlag(mcc = "505", mnc = "02", iccid = null))
    }

    @Test
    fun countryFlagUsesIccidCountryWhenMccIsMissing() {
        assertEquals(
            "🇦🇺",
            profileCountryFlag(mcc = null, mnc = null, iccid = "8961000000000000001"),
        )
    }

    @Test
    fun countryFlagIsOmittedWhenCountryCannotBeResolved() {
        assertNull(profileCountryFlag(mcc = "999", mnc = null, iccid = null))
    }

    private fun profile(name: String, providerName: String) = ProfileInfo(
        iccid = "8944000000000000000",
        state = ProfileState.DISABLED,
        name = name,
        nickname = "",
        providerName = providerName,
        isdPAid = "",
        profileClass = ProfileClass.UNKNOWN,
        mcc = "234",
        mnc = "10",
    )
}
