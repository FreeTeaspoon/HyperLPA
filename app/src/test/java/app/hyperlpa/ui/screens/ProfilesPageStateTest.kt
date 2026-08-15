package app.hyperlpa.ui.screens

import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import app.hyperlpa.ui.components.PageStateKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilesPageStateTest {
    @Test
    fun loadedProfilesStayVisibleWhileOptionalArtworkLoads() {
        val reader = ReaderInfo(
            id = "reader",
            name = "Reader",
            kind = ReaderKind.OMAPI,
        )
        val profile = ProfileInfo(
            iccid = "profile",
            state = ProfileState.ENABLED,
            name = "Profile",
            nickname = "",
            providerName = "Provider",
            isdPAid = "",
            profileClass = ProfileClass.OPERATIONAL,
        )
        val lpa = LpaRepositoryState(
            readers = listOf(reader),
            selectedReaderId = reader.id,
            profiles = listOf(profile),
            operation = LpaOperation.Idle,
            initialized = true,
        )

        assertEquals(PageStateKind.CONTENT, profilesPageState(lpa, listOf(profile)))
    }

    @Test
    fun firstPresentationWaitsForArtworkWithoutChangingLoadedStateRules() {
        val reader = ReaderInfo(
            id = "reader",
            name = "Reader",
            kind = ReaderKind.OMAPI,
        )
        val profile = ProfileInfo(
            iccid = "profile",
            state = ProfileState.ENABLED,
            name = "Profile",
            nickname = "",
            providerName = "Provider",
            isdPAid = "",
            profileClass = ProfileClass.OPERATIONAL,
        )
        val lpa = LpaRepositoryState(
            readers = listOf(reader),
            selectedReaderId = reader.id,
            profiles = listOf(profile),
            operation = LpaOperation.Idle,
            initialized = true,
        )

        assertEquals(
            PageStateKind.LOADING,
            profilesPageState(lpa, listOf(profile), awaitInitialArtwork = true),
        )
    }
}
