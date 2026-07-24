package app.hyperlpa.lpa.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NBridgeTrustPolicyTest {
    @Test
    fun genuineEnabledProviderWithPinnedSignerIsAccepted() {
        assertTrue(
            matchesNBridgeTrustPolicy(
                packageName = "ee.nekoko.nbridge",
                authorities = "secondary.authority;ee.nekoko.nbridge.provider",
                exported = true,
                providerEnabled = true,
                applicationEnabled = true,
                trustedSigner = true,
            ),
        )
    }

    @Test
    fun packageNameImpostorWithoutPinnedSignerIsRejected() {
        assertFalse(
            matchesNBridgeTrustPolicy(
                packageName = "ee.nekoko.nbridge",
                authorities = "ee.nekoko.nbridge.provider",
                exported = true,
                providerEnabled = true,
                applicationEnabled = true,
                trustedSigner = false,
            ),
        )
    }

    @Test
    fun wrongAuthorityOrDisabledProviderIsRejected() {
        assertFalse(
            matchesNBridgeTrustPolicy(
                packageName = "ee.nekoko.nbridge",
                authorities = "attacker.provider",
                exported = true,
                providerEnabled = true,
                applicationEnabled = true,
                trustedSigner = true,
            ),
        )
        assertFalse(
            matchesNBridgeTrustPolicy(
                packageName = "ee.nekoko.nbridge",
                authorities = "ee.nekoko.nbridge.provider",
                exported = true,
                providerEnabled = false,
                applicationEnabled = true,
                trustedSigner = true,
            ),
        )
    }
}
