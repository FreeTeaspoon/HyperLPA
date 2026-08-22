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
            profile = profile(name = "giffgaff 1 +44 7719 227207", providerName = "giffgaff"),
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

    @Test
    fun countryFlagUsesProfileMccRegion() {
        assertEquals("🇦🇺", profileCountryFlag(mcc = "505", mnc = "02", iccid = null))
    }

    @Test
    fun countryFlagUsesIccidCountryWhenMccIsMissing() {
        assertEquals(
            "🇦🇺",
            profileCountryFlag(mcc = null, mnc = null, iccid = "8961026025407240300"),
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
