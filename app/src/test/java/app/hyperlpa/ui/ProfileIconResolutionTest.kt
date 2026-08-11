package app.hyperlpa.ui

import app.hyperlpa.data.metadata.ProfileMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileIconResolutionTest {
    @Test
    fun hiddenProfileDoesNotInheritProviderIcon() {
        assertNull(
            resolveProfileIconUri(
                metadata = ProfileMetadata(isProviderIconHidden = true),
                providerName = "Carrier",
                providerIcons = mapOf("carrier" to "file:///provider"),
            ),
        )
    }

    @Test
    fun personalIconStillTakesPriorityOverProviderIcon() {
        assertEquals(
            "file:///profile",
            resolveProfileIconUri(
                metadata = ProfileMetadata(
                    iconUri = "file:///profile",
                    isProviderIconHidden = true,
                ),
                providerName = "Carrier",
                providerIcons = mapOf("carrier" to "file:///provider"),
            ),
        )
    }

    @Test
    fun visibleProfileInheritsProviderIcon() {
        assertEquals(
            "file:///provider",
            resolveProfileIconUri(
                metadata = ProfileMetadata(),
                providerName = "Carrier",
                providerIcons = mapOf("carrier" to "file:///provider"),
            ),
        )
    }
}
