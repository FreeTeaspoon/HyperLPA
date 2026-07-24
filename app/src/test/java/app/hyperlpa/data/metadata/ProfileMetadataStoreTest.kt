package app.hyperlpa.data.metadata

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    fun reminderLabelsAreTrimmedBoundedAndBlankSafe() {
        assertNull(normalizeReminderLabel("   "))
        assertEquals("Travel", normalizeReminderLabel("  Travel  "))
        assertEquals(128, normalizeReminderLabel("x".repeat(256))?.length)
    }

    @Test
    fun providerIconAndProfileOverridesMutateTogether() {
        val mutation = applyProviderIconMutation(
            metadata = mapOf(
                "one" to StoredProfileMetadata(tags = setOf("Travel"), iconUri = "file:///one"),
                "two-disconnected" to StoredProfileMetadata(
                    iconUri = "file:///two",
                    providerKey = "carrier",
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
        assertEquals("file:///other", mutation.metadata.getValue("other").iconUri)
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
                ),
            ),
            providerIcons = mapOf("carrier" to "file:///provider"),
        )

        val restored = Json.decodeFromString<ProfileMetadataSnapshot>(Json.encodeToString(snapshot))

        assertEquals(snapshot, restored)
    }
}
