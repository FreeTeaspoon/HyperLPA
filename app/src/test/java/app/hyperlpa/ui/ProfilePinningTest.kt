package app.hyperlpa.ui

import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.data.metadata.ProfileMetadata
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.ProfileSort
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilePinningTest {
    @Test
    fun pinnedProfilesStayFirstWithAscendingSort() {
        val state = state(sortAscending = true)

        assertEquals(
            listOf("alpha", "zulu", "omega"),
            state.profiles.map(ProfileInfo::iccid),
        )
    }

    @Test
    fun pinnedProfilesStayFirstWithDescendingSort() {
        val state = state(sortAscending = false)

        assertEquals(
            listOf("zulu", "alpha", "omega"),
            state.profiles.map(ProfileInfo::iccid),
        )
    }

    private fun state(sortAscending: Boolean) = HyperLpaUiState(
        settings = AppSettings(
            profileSort = ProfileSort.NAME,
            sortAscending = sortAscending,
        ),
        lpa = LpaRepositoryState(
            profiles = listOf(
                profile(iccid = "omega", name = "Omega"),
                profile(iccid = "zulu", name = "Zulu"),
                profile(iccid = "alpha", name = "Alpha"),
            ),
        ),
        metadata = mapOf(
            "zulu" to ProfileMetadata(isPinned = true),
            "alpha" to ProfileMetadata(isPinned = true),
        ),
    )

    private fun profile(iccid: String, name: String) = ProfileInfo(
        iccid = iccid,
        state = ProfileState.DISABLED,
        name = name,
        nickname = "",
        providerName = "",
        isdPAid = "",
        profileClass = ProfileClass.UNKNOWN,
    )
}
