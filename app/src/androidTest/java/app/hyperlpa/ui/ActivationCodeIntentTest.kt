package app.hyperlpa.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivationCodeIntentTest {
    @Test
    fun opaqueUriIsIgnoredWithoutThrowing() {
        assertNull(extractActivationCode("mailto:user@example.com"))
    }

    @Test
    fun hierarchicalWrapperExtractsActivationCode() {
        assertEquals(
            "LPA:1\$smdp.example\$matching-id",
            extractActivationCode(
                "https://example.invalid/install?activationCode=" +
                    "LPA%3A1%24smdp.example%24matching-id",
            ),
        )
    }
}
