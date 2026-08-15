package app.hyperlpa.ui.screens

import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.domain.model.ProfileClass
import app.hyperlpa.domain.model.ProfileInfo
import app.hyperlpa.domain.model.ProfileState
import app.hyperlpa.domain.model.ReaderInfo
import app.hyperlpa.domain.model.ReaderKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDetailsStateTest {
    private val reader = ReaderInfo(
        id = "reader",
        name = "Reader",
        kind = ReaderKind.OMAPI,
    )
    private val profile = ProfileInfo(
        iccid = "profile",
        state = ProfileState.ENABLED,
        name = "Profile",
        nickname = "",
        providerName = "Provider",
        isdPAid = "",
        profileClass = ProfileClass.OPERATIONAL,
    )

    @Test
    fun missingProfileWhileReaderStateIsBeingRebuiltStaysLoading() {
        assertTrue(
            isProfileDetailsLoading(
                profile = null,
                lpa = LpaRepositoryState(
                    readers = listOf(reader),
                    selectedReaderId = reader.id,
                    operation = LpaOperation.Connecting(reader.name),
                    initialized = true,
                ),
            ),
        )
    }

    @Test
    fun missingProfileBeforeInitialDiscoveryStaysLoading() {
        assertTrue(isProfileDetailsLoading(profile = null, lpa = LpaRepositoryState()))
    }

    @Test
    fun missingProfileAfterRefreshCompletesIsUnavailable() {
        assertFalse(
            isProfileDetailsLoading(
                profile = null,
                lpa = LpaRepositoryState(
                    readers = listOf(reader),
                    selectedReaderId = reader.id,
                    operation = LpaOperation.Idle,
                    initialized = true,
                ),
            ),
        )
    }

    @Test
    fun existingProfileRemainsContentDuringRefresh() {
        assertFalse(
            isProfileDetailsLoading(
                profile = profile,
                lpa = LpaRepositoryState(
                    readers = listOf(reader),
                    selectedReaderId = reader.id,
                    operation = LpaOperation.Refreshing("Reading profiles"),
                    initialized = true,
                ),
            ),
        )
    }
}
