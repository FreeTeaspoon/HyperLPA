package app.hyperlpa.data.metadata

import app.hyperlpa.reminders.normalizeReminderInstant
import java.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMetadataStoreTest {
    @Test
    fun tagsAreTrimmedAndDeduplicatedIgnoringCase() {
        val tags = normalizeProfileTags(listOf(" Travel ", "travel", "Work", ""))

        assertEquals(linkedSetOf("Travel", "Work"), tags)
    }

    @Test
    fun tagsRespectStorageLimits() {
        val tags = normalizeProfileTags((1..20).map { "tag-$it-${"x".repeat(40)}" })

        assertEquals(16, tags.size)
        assertEquals(true, tags.all { it.length <= 32 })
    }

    @Test
    fun persistedReminderIdentitiesIncludeDisconnectedMetadata() {
        val stored = linkedMapOf(
            "connected" to StoredProfileMetadata(reminderEpochMillis = 100L),
            "disconnected" to StoredProfileMetadata(reminderEpochMillis = 200L),
            "without-reminder" to StoredProfileMetadata(),
        )

        assertEquals(linkedSetOf("connected", "disconnected"), stored.reminderIccids())
    }

    @Test
    fun repairSchedulesIncludeDisconnectedMetadataAndUsePrivateFallback() {
        val schedules = buildPersistedReminderSchedules(
            metadata = linkedMapOf(
                "connected" to StoredProfileMetadata(
                    reminderEpochMillis = 100L,
                    reminderLabel = " Connected plan ",
                ),
                "disconnected-secret-iccid" to StoredProfileMetadata(reminderEpochMillis = 200L),
                "without-reminder" to StoredProfileMetadata(reminderLabel = "Unused"),
            ),
            fallbackLabel = "Saved profile",
        )

        assertEquals(listOf("connected", "disconnected-secret-iccid"), schedules.map { it.iccid })
        assertEquals(listOf("Connected plan", "Saved profile"), schedules.map { it.label })
        assertTrue(schedules.none { it.label.contains("disconnected-secret-iccid") })
    }

    @Test
    fun repairSchedulesNormalizePersistedReminderTimesToDates() {
        val source = Instant.parse("2026-08-15T16:45:00Z")

        val schedule = buildPersistedReminderSchedules(
            metadata = mapOf("profile" to StoredProfileMetadata(reminderEpochMillis = source.toEpochMilli())),
            fallbackLabel = "Saved profile",
        ).single()

        assertEquals(source.normalizeReminderInstant(), schedule.reminderAt)
    }

    @Test
    fun reminderLabelsAreTrimmedBoundedAndBlankSafe() {
        assertNull(normalizeReminderLabel("   "))
        assertEquals("Travel", normalizeReminderLabel("  Travel  "))
        assertEquals(128, normalizeReminderLabel("x".repeat(256))?.length)
    }

    @Test
    fun euiccNamesAreBoundToValidEidsAndBlankNamesAreRemoved() {
        val eid = "89049032000000000000000000000000"
        val named = applyEuiccNameMutation(emptyMap(), eid, "  Travel card  ")

        assertEquals(mapOf(eid to "Travel card"), named)
        assertEquals(emptyMap<String, String>(), applyEuiccNameMutation(named, eid, "  "))
        assertEquals(named, applyEuiccNameMutation(named, "not-an-eid", "Other card"))
        assertEquals(64, normalizeEuiccName("x".repeat(128))?.length)
    }

    @Test
    fun providerIconAndProfileOverridesMutateTogether() {
        val mutation = applyProviderIconMutation(
            metadata = mapOf(
                "one" to StoredProfileMetadata(tags = setOf("Travel"), iconUri = "file:///one"),
                "two-disconnected" to StoredProfileMetadata(
                    iconUri = "file:///two",
                    providerKey = "carrier",
                    isProviderIconHidden = true,
                ),
                "other" to StoredProfileMetadata(
                    iconUri = "file:///other",
                    providerKey = "different-carrier",
                ),
            ),
            providerIcons = mapOf("carrier" to "file:///old-provider"),
            providerKey = "carrier",
            iconUri = "file:///new-provider",
            // Only the connected legacy profile is supplied. The disconnected profile must be
            // found through its persisted provider key.
            profileIccids = listOf("one", "missing"),
        )

        assertEquals("file:///new-provider", mutation.providerIcons["carrier"])
        assertEquals("file:///old-provider", mutation.result.previousProviderIconUri)
        assertEquals(
            linkedSetOf("file:///one", "file:///two"),
            mutation.result.removedProfileIconUris,
        )
        assertNull(mutation.metadata.getValue("one").iconUri)
        assertEquals(setOf("Travel"), mutation.metadata.getValue("one").tags)
        assertNull(mutation.metadata.getValue("two-disconnected").iconUri)
        assertEquals("carrier", mutation.metadata.getValue("two-disconnected").providerKey)
        assertFalse(mutation.metadata.getValue("two-disconnected").isProviderIconHidden)
        assertEquals("file:///other", mutation.metadata.getValue("other").iconUri)
    }

    @Test
    fun profileIconVisibilityIsScopedToOneProfile() {
        val metadata = mapOf(
            "one" to StoredProfileMetadata(
                iconUri = "file:///one",
                providerKey = "carrier",
            ),
            "other" to StoredProfileMetadata(
                iconUri = "file:///other",
                providerKey = "carrier",
            ),
        )

        val hidden = applyProfileIconVisibility(
            metadata = metadata,
            iccid = "one",
            hidden = true,
            providerKey = "carrier",
        )

        assertNull(hidden.getValue("one").iconUri)
        assertEquals("carrier", hidden.getValue("one").providerKey)
        assertTrue(hidden.getValue("one").isProviderIconHidden)
        assertEquals(metadata.getValue("other"), hidden.getValue("other"))

        val restored = applyProfileIconVisibility(
            metadata = hidden,
            iccid = "one",
            hidden = false,
            providerKey = "carrier",
        )
        assertFalse(restored.getValue("one").isProviderIconHidden)
    }

    @Test
    fun repositoryRefreshPersistsProviderIdentityWithoutDiscardingMetadata() {
        val updated = applyProviderIdentityUpdates(
            metadata = mapOf(
                "existing" to StoredProfileMetadata(
                    tags = setOf("Travel"),
                    providerKey = "old-carrier",
                ),
                "now-unknown" to StoredProfileMetadata(providerKey = "old-carrier"),
            ),
            providerKeysByIccid = mapOf(
                "existing" to "new-carrier",
                "new" to "new-carrier",
                "now-unknown" to null,
            ),
        )

        assertEquals(setOf("Travel"), updated.getValue("existing").tags)
        assertEquals("new-carrier", updated.getValue("existing").providerKey)
        assertEquals("new-carrier", updated.getValue("new").providerKey)
        assertNull(updated.getValue("now-unknown").providerKey)
    }

    @Test
    fun recoverySnapshotIsJsonSerializable() {
        val snapshot = ProfileMetadataSnapshot(
            metadata = mapOf(
                "8901" to StoredProfileMetadata(
                    reminderEpochMillis = 123L,
                    reminderLabel = "Travel plan",
                    reminderDeliveryClaimToken = "claim",
                    providerKey = "carrier",
                    isPinned = true,
                    isProviderIconHidden = true,
                ),
            ),
            providerIcons = mapOf("carrier" to "file:///provider"),
            euiccNames = mapOf("89049032000000000000000000000000" to "Travel card"),
        )

        val restored = Json.decodeFromString<ProfileMetadataSnapshot>(Json.encodeToString(snapshot))

        assertEquals(snapshot, restored)
    }
}
