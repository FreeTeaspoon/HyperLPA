package app.hyperlpa.data

import app.hyperlpa.domain.model.ProfileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LpaRepositoryReconciliationTest {
    @Test
    fun initialNotificationDeliveryRequiresAutomaticSendAndValidatedInternet() {
        assertFalse(shouldAttemptInitialNotificationDelivery(notificationAutoSend = false, hasValidatedInternet = true))
        assertFalse(shouldAttemptInitialNotificationDelivery(notificationAutoSend = true, hasValidatedInternet = false))
        assertTrue(shouldAttemptInitialNotificationDelivery(notificationAutoSend = true, hasValidatedInternet = true))
    }

    @Test
    fun initialNotificationWorkIsDeferredOnlyWhenConnectionHasPendingNotifications() {
        assertFalse(
            shouldScheduleInitialNotificationDelivery(
                notificationInitialLoad = false,
                notificationAutoSend = true,
                pendingNotificationCount = 1,
            ),
        )
        assertFalse(
            shouldScheduleInitialNotificationDelivery(
                notificationInitialLoad = true,
                notificationAutoSend = false,
                pendingNotificationCount = 1,
            ),
        )
        assertFalse(
            shouldScheduleInitialNotificationDelivery(
                notificationInitialLoad = true,
                notificationAutoSend = true,
                pendingNotificationCount = 0,
            ),
        )
        assertTrue(
            shouldScheduleInitialNotificationDelivery(
                notificationInitialLoad = true,
                notificationAutoSend = true,
                pendingNotificationCount = 1,
            ),
        )
    }

    @Test
    fun postSwitchNotificationDeliveryRequiresSwitchPolicyAutoSendAndValidatedInternet() {
        assertFalse(
            shouldAttemptPostSwitchNotificationDelivery(
                notificationAfterSwitch = false,
                notificationAutoSend = true,
                hasValidatedInternet = true,
            ),
        )
        assertFalse(
            shouldAttemptPostSwitchNotificationDelivery(
                notificationAfterSwitch = true,
                notificationAutoSend = false,
                hasValidatedInternet = true,
            ),
        )
        assertFalse(
            shouldAttemptPostSwitchNotificationDelivery(
                notificationAfterSwitch = true,
                notificationAutoSend = true,
                hasValidatedInternet = false,
            ),
        )
        assertTrue(
            shouldAttemptPostSwitchNotificationDelivery(
                notificationAfterSwitch = true,
                notificationAutoSend = true,
                hasValidatedInternet = true,
            ),
        )
    }

    @Test
    fun selectedReaderNeverFallsThroughToAnotherAvailableReader() {
        assertEquals(
            listOf("selected"),
            readerReconnectCandidateIds(
                selectedReaderId = "selected",
                preferredReaderId = "preferred",
                availableReaderIds = listOf("preferred", "other", "selected"),
            ),
        )
        assertEquals(
            emptyList<String>(),
            readerReconnectCandidateIds(
                selectedReaderId = "selected",
                preferredReaderId = "preferred",
                availableReaderIds = listOf("preferred", "other"),
            ),
        )
    }

    @Test
    fun initialReaderSelectionStillUsesPreferredThenAvailableOrder() {
        assertEquals(
            listOf("preferred", "other"),
            readerReconnectCandidateIds(
                selectedReaderId = null,
                preferredReaderId = "preferred",
                availableReaderIds = listOf("other", "preferred"),
            ),
        )
    }

    @Test
    fun deletionReconciliationRequiresTheSameReaderAndEid() {
        val expected = ReaderAffinity("usb:1:0", "eid-one")

        assertEquals(
            ProfileDeletionReconciliation.ReaderMismatch,
            classifyProfileDeletionReconciliation(
                expectedAffinity = expected,
                observedAffinity = ReaderAffinity("usb:2:0", "eid-one"),
                targetIccid = "8901",
                profilesAfterReconnect = emptySet(),
            ),
        )
        assertEquals(
            ProfileDeletionReconciliation.ReaderMismatch,
            classifyProfileDeletionReconciliation(
                expectedAffinity = expected,
                observedAffinity = ReaderAffinity("usb:1:0", "eid-two"),
                targetIccid = "8901",
                profilesAfterReconnect = emptySet(),
            ),
        )
    }

    @Test
    fun deletionIsConfirmedOnlyWhenTargetIsAbsentOnBoundEuicc() {
        val affinity = ReaderAffinity("usb:1:0", "eid-one")

        assertEquals(
            ProfileDeletionReconciliation.Deleted,
            classifyProfileDeletionReconciliation(
                expectedAffinity = affinity,
                observedAffinity = affinity,
                targetIccid = "8901",
                profilesAfterReconnect = setOf("8902"),
            ),
        )
        assertEquals(
            ProfileDeletionReconciliation.NotDeleted,
            classifyProfileDeletionReconciliation(
                expectedAffinity = affinity,
                observedAffinity = affinity,
                targetIccid = "8901",
                profilesAfterReconnect = setOf("8901", "8902"),
            ),
        )
    }

    @Test
    fun activeProfileIsDisabledBeforeDeletion() {
        assertEquals(true, requiresDisableBeforeDeletion(ProfileState.ENABLED))
        assertEquals(false, requiresDisableBeforeDeletion(ProfileState.DISABLED))
    }

    @Test
    fun cancellationDuringAmbiguousReconciliationRequiresTheCorrectRefreshes() {
        assertEquals(
            ReconciliationRefreshRequirements(
                mutationRefreshRequired = true,
                downloadRefreshRequired = false,
            ),
            reconciliationCancellationRequirements(ReconciliationOperation.PROFILE_SWITCH),
        )
        assertEquals(
            ReconciliationRefreshRequirements(
                mutationRefreshRequired = true,
                downloadRefreshRequired = false,
            ),
            reconciliationCancellationRequirements(ReconciliationOperation.MEMORY_RESET),
        )
        assertEquals(
            ReconciliationRefreshRequirements(
                mutationRefreshRequired = true,
                downloadRefreshRequired = true,
            ),
            reconciliationCancellationRequirements(ReconciliationOperation.PROFILE_DOWNLOAD),
        )
    }

    @Test
    fun switchReconciliationRequiresTheSameReaderAndEid() {
        val expected = ReaderAffinity("usb:1:0", "eid-one")
        val observedStates = mapOf("8901" to ProfileState.ENABLED)

        assertEquals(
            ProfileSwitchReconciliation.Unverified,
            classifyProfileSwitchReconciliation(
                expectedAffinity = expected,
                observedAffinity = ReaderAffinity("usb:2:0", "eid-one"),
                targetIccid = "8901",
                profileStatesAfterReconnect = observedStates,
            ),
        )
        assertEquals(
            ProfileSwitchReconciliation.Unverified,
            classifyProfileSwitchReconciliation(
                expectedAffinity = expected,
                observedAffinity = ReaderAffinity("usb:1:0", "eid-two"),
                targetIccid = "8901",
                profileStatesAfterReconnect = observedStates,
            ),
        )
        assertEquals(
            ProfileSwitchReconciliation.Observed(ProfileState.ENABLED),
            classifyProfileSwitchReconciliation(
                expectedAffinity = expected,
                observedAffinity = expected,
                targetIccid = "8901",
                profileStatesAfterReconnect = observedStates,
            ),
        )
    }

    @Test
    fun switchPreflightAcceptsTheBoundEuiccAndRejectsAReplacement() {
        val expected = ReaderAffinity("usb:1:0", "eid-one")

        assertEquals(
            ProfileSwitchPreflight.Ready(expected),
            classifyProfileSwitchPreflight(
                expectedAffinity = expected,
                observedAffinity = expected,
            ),
        )
        assertEquals(
            ProfileSwitchPreflight.ReaderMismatch,
            classifyProfileSwitchPreflight(
                expectedAffinity = expected,
                observedAffinity = ReaderAffinity("usb:1:0", "eid-two"),
            ),
        )
        assertEquals(
            ProfileSwitchPreflight.ReaderMismatch,
            classifyProfileSwitchPreflight(
                expectedAffinity = expected,
                observedAffinity = ReaderAffinity("usb:2:0", "eid-one"),
            ),
        )
    }

    @Test
    fun switchPreflightCanEstablishMissingAffinityButNotMissingIdentity() {
        val observed = ReaderAffinity("usb:1:0", "eid-one")

        assertEquals(
            ProfileSwitchPreflight.Ready(observed),
            classifyProfileSwitchPreflight(
                expectedAffinity = null,
                observedAffinity = observed,
            ),
        )
        assertEquals(
            ProfileSwitchPreflight.Unverified,
            classifyProfileSwitchPreflight(
                expectedAffinity = observed,
                observedAffinity = null,
            ),
        )
    }

    @Test
    fun resetReconciliationRequiresTheSameReaderAndEid() {
        val expected = ReaderAffinity("usb:1:0", "eid-one")

        assertEquals(
            MemoryResetReconciliation.Unverified,
            classifyMemoryResetReconciliation(
                expectedAffinity = expected,
                observedAffinity = ReaderAffinity("usb:2:0", "eid-one"),
                affectedIccids = setOf("8901"),
                profilesAfterReconnect = emptySet(),
            ),
        )
        assertEquals(
            MemoryResetReconciliation.Unverified,
            classifyMemoryResetReconciliation(
                expectedAffinity = expected,
                observedAffinity = ReaderAffinity("usb:1:0", "eid-two"),
                affectedIccids = setOf("8901"),
                profilesAfterReconnect = emptySet(),
            ),
        )
        assertEquals(
            MemoryResetReconciliation.Reset,
            classifyMemoryResetReconciliation(
                expectedAffinity = expected,
                observedAffinity = expected,
                affectedIccids = setOf("8901"),
                profilesAfterReconnect = emptySet(),
            ),
        )
    }

    @Test
    fun resetCannotBeProvedWithoutAnAffectedProfileOrWhenOneRemains() {
        val affinity = ReaderAffinity("usb:1:0", "eid-one")

        assertEquals(
            MemoryResetReconciliation.Unverified,
            classifyMemoryResetReconciliation(
                expectedAffinity = affinity,
                observedAffinity = affinity,
                affectedIccids = emptySet(),
                profilesAfterReconnect = emptySet(),
            ),
        )
        assertEquals(
            MemoryResetReconciliation.Unverified,
            classifyMemoryResetReconciliation(
                expectedAffinity = affinity,
                observedAffinity = affinity,
                affectedIccids = setOf("8901", "8902"),
                profilesAfterReconnect = setOf("8902"),
            ),
        )
    }

    @Test
    fun expectedNewIccidConfirmsInstallation() {
        val affinity = ReaderAffinity("usb:1:0", "eid-one")

        assertEquals(
            DownloadReconciliation.Installed("8902"),
            classifyDownloadReconciliation(
                expectedAffinity = affinity,
                observedAffinity = affinity,
                profilesBeforeDownload = setOf("8901"),
                profilesAfterReconnect = setOf("8901", "8902"),
                expectedIccid = "8902",
            ),
        )
    }

    @Test
    fun onlyNewIccidConfirmsInstallationWithoutMetadata() {
        val affinity = ReaderAffinity("usb:1:0", "eid-one")

        assertEquals(
            DownloadReconciliation.Installed("8902"),
            classifyDownloadReconciliation(
                expectedAffinity = affinity,
                observedAffinity = affinity,
                profilesBeforeDownload = setOf("8901"),
                profilesAfterReconnect = setOf("8901", "8902"),
                expectedIccid = null,
            ),
        )
    }

    @Test
    fun authoritativeUnchangedListConfirmsNoInstallation() {
        val affinity = ReaderAffinity("usb:1:0", "eid-one")

        assertEquals(
            DownloadReconciliation.NotInstalled,
            classifyDownloadReconciliation(
                expectedAffinity = affinity,
                observedAffinity = affinity,
                profilesBeforeDownload = setOf("8901"),
                profilesAfterReconnect = setOf("8901"),
                expectedIccid = "8902",
            ),
        )
    }

    @Test
    fun differentNewIccidCannotSatisfyKnownExpectedProfile() {
        val affinity = ReaderAffinity("usb:1:0", "eid-one")

        assertEquals(
            DownloadReconciliation.Unverified,
            classifyDownloadReconciliation(
                expectedAffinity = affinity,
                observedAffinity = affinity,
                profilesBeforeDownload = setOf("8901"),
                profilesAfterReconnect = setOf("8901", "9999"),
                expectedIccid = "8902",
            ),
        )
    }

    @Test
    fun preexistingExpectedOrMultipleUnknownProfilesStayUnverified() {
        val affinity = ReaderAffinity("usb:1:0", "eid-one")

        assertEquals(
            DownloadReconciliation.Unverified,
            classifyDownloadReconciliation(
                expectedAffinity = affinity,
                observedAffinity = affinity,
                profilesBeforeDownload = setOf("8901"),
                profilesAfterReconnect = setOf("8901"),
                expectedIccid = "8901",
            ),
        )
        assertEquals(
            DownloadReconciliation.Unverified,
            classifyDownloadReconciliation(
                expectedAffinity = affinity,
                observedAffinity = affinity,
                profilesBeforeDownload = setOf("8901"),
                profilesAfterReconnect = setOf("8901", "8902", "8903"),
                expectedIccid = null,
            ),
        )
    }

    @Test
    fun downloadReconciliationRequiresTheSameReaderAndEid() {
        val expected = ReaderAffinity("usb:1:0", "eid-one")

        assertEquals(
            DownloadReconciliation.Unverified,
            classifyDownloadReconciliation(
                expectedAffinity = expected,
                observedAffinity = ReaderAffinity("usb:2:0", "eid-one"),
                profilesBeforeDownload = setOf("8901"),
                profilesAfterReconnect = setOf("8901", "8902"),
                expectedIccid = "8902",
            ),
        )
        assertEquals(
            DownloadReconciliation.Unverified,
            classifyDownloadReconciliation(
                expectedAffinity = expected,
                observedAffinity = ReaderAffinity("usb:1:0", "eid-two"),
                profilesBeforeDownload = setOf("8901"),
                profilesAfterReconnect = setOf("8901", "8902"),
                expectedIccid = null,
            ),
        )
    }
}
