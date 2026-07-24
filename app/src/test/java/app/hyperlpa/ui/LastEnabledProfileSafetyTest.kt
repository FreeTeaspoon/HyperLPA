package app.hyperlpa.ui

import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastEnabledProfileSafetyTest {
    @Test
    fun `disabling sole enabled target requires confirmation`() {
        val profiles = listOf(
            profile("enabled", ProfileState.ENABLED),
            profile("disabled", ProfileState.DISABLED),
        )

        assertTrue(
            requiresLastEnabledProfileConfirmation(
                profiles = profiles,
                targetIccid = "enabled",
                requestedEnabled = false,
            ),
        )
    }

    @Test
    fun `MEP with another enabled profile does not require confirmation`() {
        val profiles = listOf(
            profile("one", ProfileState.ENABLED),
            profile("two", ProfileState.ENABLED),
        )

        assertFalse(requiresLastEnabledProfileConfirmation(profiles, "one", false))
    }

    @Test
    fun `enabling and redundant disable are never intercepted`() {
        val profiles = listOf(
            profile("enabled", ProfileState.ENABLED),
            profile("disabled", ProfileState.DISABLED),
        )

        assertFalse(requiresLastEnabledProfileConfirmation(profiles, "disabled", true))
        assertFalse(requiresLastEnabledProfileConfirmation(profiles, "disabled", false))
    }

    @Test
    fun `profile switch preview immediately replaces sole enabled profile`() {
        val profiles = listOf(
            profile("old", ProfileState.ENABLED),
            profile("new", ProfileState.DISABLED),
        )

        val preview = profilesWithOptimisticSwitch(
            profiles,
            LpaOperation.Switching(iccid = "new", enable = true),
        ).associate { it.iccid to it.state }

        assertEquals(ProfileState.DISABLED, preview["old"])
        assertEquals(ProfileState.ENABLED, preview["new"])
    }

    @Test
    fun `profile switch preview keeps old profile off after target state updates`() {
        val intermediateRepositoryState = listOf(
            profile("old", ProfileState.ENABLED),
            profile("new", ProfileState.ENABLED),
        )

        val preview = profilesWithOptimisticSwitch(
            intermediateRepositoryState,
            LpaOperation.Switching(iccid = "new", enable = true),
        ).associate { it.iccid to it.state }

        assertEquals(ProfileState.DISABLED, preview["old"])
        assertEquals(ProfileState.ENABLED, preview["new"])
    }

    @Test
    fun `profile switch preview preserves other profiles on multi-enabled euicc`() {
        val profiles = listOf(
            profile("one", ProfileState.ENABLED),
            profile("two", ProfileState.ENABLED),
            profile("new", ProfileState.DISABLED),
        )

        val preview = profilesWithOptimisticSwitch(
            profiles,
            LpaOperation.Switching(iccid = "new", enable = true),
        ).associate { it.iccid to it.state }

        assertEquals(ProfileState.ENABLED, preview["one"])
        assertEquals(ProfileState.ENABLED, preview["two"])
        assertEquals(ProfileState.ENABLED, preview["new"])
    }

    private fun profile(iccid: String, state: ProfileState) = ProfileInfo(
        iccid = iccid,
        state = state,
        name = "",
        nickname = "",
        providerName = "",
        isdPAid = "",
        profileClass = ProfileClass.UNKNOWN,
    )
}
