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
    fun firstDiscoveryDoesNotRevealAnEarlyReaderResult() {
        val reader = ReaderInfo("reader", "Reader", ReaderKind.OMAPI)
        val profile = profile()
        val lpa = LpaRepositoryState(
            readers = listOf(reader),
            selectedReaderId = reader.id,
            profiles = listOf(profile),
            operation = LpaOperation.DiscoveringReaders("Looking for readers"),
            initialized = true,
        )

        assertEquals(
            PageStateKind.LOADING,
            profilesPageState(lpa, lpa.profiles, awaitInitialContent = true),
        )
        assertEquals(
            PageStateKind.CONTENT,
            profilesPageState(lpa.copy(operation = LpaOperation.Idle), lpa.profiles, awaitInitialContent = true),
        )
    }

    @Test
    fun uncachedReaderWaitsOnlyForConnection() {
        val reader = ReaderInfo("reader", "Reader", ReaderKind.OMAPI)
        val lpa = LpaRepositoryState(
            readers = listOf(reader),
            selectedReaderId = reader.id,
            profiles = listOf(profile()),
            operation = LpaOperation.Connecting(reader.name),
            initialized = true,
        )

        assertEquals(PageStateKind.LOADING, profilesPageState(lpa, lpa.profiles, awaitInitialContent = true))
        val connected = lpa.copy(operation = LpaOperation.Idle)
        assertEquals(PageStateKind.CONTENT, profilesPageState(connected, connected.profiles, awaitInitialContent = true))
    }

    @Test
    fun confirmedCachedCardStaysVisibleUntilConnectionReturnsIdle() {
        val reader = ReaderInfo("reader", "Reader", ReaderKind.OMAPI)
        val lpa = LpaRepositoryState(
            readers = listOf(reader),
            selectedReaderId = reader.id,
            profiles = listOf(profile()),
            operation = LpaOperation.Connecting(reader.name),
            initialized = true,
            readerSnapshotPendingRefresh = false,
        )

        assertEquals(PageStateKind.CONTENT, profilesPageState(lpa, lpa.profiles))
    }

    @Test
    fun emptyUncachedReaderWaitsForConnectionToFinish() {
        val reader = ReaderInfo("reader", "Reader", ReaderKind.OMAPI)
        val lpa = LpaRepositoryState(
            readers = listOf(reader),
            selectedReaderId = reader.id,
            operation = LpaOperation.Connecting(reader.name),
            initialized = true,
        )

        assertEquals(PageStateKind.LOADING, profilesPageState(lpa, emptyList(), awaitInitialContent = true))
        assertEquals(
            PageStateKind.EMPTY,
            profilesPageState(lpa.copy(operation = LpaOperation.Idle), emptyList(), awaitInitialContent = true),
        )
    }

    private fun profile() = ProfileInfo(
        iccid = "profile", state = ProfileState.ENABLED, name = "Profile", nickname = "",
        providerName = "Provider", isdPAid = "", profileClass = ProfileClass.OPERATIONAL,
    )

    @Test
    fun connectingWithoutATargetSnapshotUsesTheLoadingState() {
        val reader = ReaderInfo("reader", "Reader", ReaderKind.OMAPI)
        val lpa = LpaRepositoryState(
            readers = listOf(reader),
            operation = LpaOperation.Connecting(reader.name),
            initialized = true,
        )

        assertEquals(PageStateKind.LOADING, profilesPageState(lpa, emptyList()))
    }

    @Test
    fun connectingWithTheTargetsSnapshotKeepsCardsVisible() {
        val reader = ReaderInfo("reader", "Reader", ReaderKind.OMAPI)
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
            operation = LpaOperation.Connecting(reader.name),
            initialized = true,
            readerSnapshotPendingRefresh = true,
        )

        assertEquals(PageStateKind.CONTENT, profilesPageState(lpa, listOf(profile)))
    }

    @Test
    fun refreshingAnEmptyListDoesNotShowTheEmptyState() {
        val reader = ReaderInfo("reader", "Reader", ReaderKind.OMAPI)
        val lpa = LpaRepositoryState(
            readers = listOf(reader),
            selectedReaderId = reader.id,
            operation = LpaOperation.Refreshing("Reading profiles"),
            initialized = true,
        )

        assertEquals(PageStateKind.CONTENT, profilesPageState(lpa, emptyList()))
    }

    @Test
    fun pullingWithoutAReaderLeavesDiscoveryProgressToTheRefreshIndicator() {
        val lpa = LpaRepositoryState(
            operation = LpaOperation.DiscoveringReaders("Looking for readers"),
            initialized = true,
        )

        assertEquals(PageStateKind.CONTENT, profilesPageState(lpa, emptyList(), refreshPending = true))
    }

    @Test
    fun automaticDiscoveryWithoutProfilesShowsTheLoadingState() {
        val lpa = LpaRepositoryState(
            operation = LpaOperation.DiscoveringReaders("Looking for readers"),
            initialized = true,
        )

        assertEquals(PageStateKind.LOADING, profilesPageState(lpa, emptyList()))
    }

    @Test
    fun remoteReaderWithNoProfilesYetShowsLoadingWhileConnecting() {
        val reader = ReaderInfo("remote", "Remote reader", ReaderKind.REMOTE, deviceId = "phone")
        val connecting = LpaRepositoryState(
            readers = listOf(reader), selectedReaderId = reader.id,
            operation = LpaOperation.Connecting(reader.name), initialized = true,
            readerSnapshotPendingRefresh = true,
        )
        assertEquals(PageStateKind.LOADING, profilesPageState(connecting, emptyList()))
        assertEquals(
            PageStateKind.EMPTY,
            profilesPageState(connecting.copy(operation = LpaOperation.Idle), emptyList()),
        )
    }

    @Test
    fun idleEmptyListShowsTheEmptyState() {
        val reader = ReaderInfo("reader", "Reader", ReaderKind.OMAPI)
        val lpa = LpaRepositoryState(
            readers = listOf(reader),
            selectedReaderId = reader.id,
            operation = LpaOperation.Idle,
            initialized = true,
        )

        assertEquals(PageStateKind.EMPTY, profilesPageState(lpa, emptyList()))
    }

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
    fun firstPresentationDoesNotWaitForOptionalArtwork() {
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
            PageStateKind.CONTENT,
            profilesPageState(lpa, listOf(profile), awaitInitialContent = true),
        )
    }
}
