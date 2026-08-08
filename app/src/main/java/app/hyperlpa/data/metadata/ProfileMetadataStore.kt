package app.hyperlpa.data.metadata

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.await
import androidx.work.WorkManager
import app.hyperlpa.R
import app.hyperlpa.domain.model.takeUnicodeCodePoints
import app.hyperlpa.reminders.ProfileReminderWorker
import app.hyperlpa.reminders.reminderWorkIdentityTag
import app.hyperlpa.reminders.scheduleProfileReminder
import app.hyperlpa.reminders.withProfileReminderIsolation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.util.Locale

private val Context.profileMetadataDataStore by preferencesDataStore(name = "profile_metadata")

@Serializable
data class StoredProfileMetadata(
    val tags: Set<String> = emptySet(),
    val reminderEpochMillis: Long? = null,
    val reminderLabel: String? = null,
    val reminderDeliveryClaimToken: String? = null,
    val iconUri: String? = null,
    val smdpAddress: String? = null,
    val installedBytes: Long? = null,
    val installedEid: String? = null,
    /** Last normalized provider identity observed for this ICCID. */
    val providerKey: String? = null,
    val isPinned: Boolean = false,
)

data class ProfileMetadata(
    val tags: Set<String> = emptySet(),
    val reminderAt: Instant? = null,
    val reminderLabel: String? = null,
    val iconUri: String? = null,
    val smdpAddress: String? = null,
    val installedBytes: Long? = null,
    val installedEid: String? = null,
    val providerKey: String? = null,
    val isPinned: Boolean = false,
)

internal data class ProfileReminderDeliveryRecord(
    val epochMillis: Long,
    val label: String,
)

data class ProviderIconMutationResult(
    val previousProviderIconUri: String?,
    val removedProfileIconUris: Set<String>,
)

@Serializable
data class ProfileMetadataSnapshot(
    val metadata: Map<String, StoredProfileMetadata>,
    val providerIcons: Map<String, String>,
    val euiccNames: Map<String, String> = emptyMap(),
)

class ProfileMetadataStore(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.profileMetadataDataStore
    private val json = Json { ignoreUnknownKeys = true }
    private val iconStorage = ProfileIconStorage(appContext)

    val metadata: Flow<Map<String, ProfileMetadata>> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            preferences[MetadataJson]
                ?.let { raw -> runCatching { json.decodeFromString<Map<String, StoredProfileMetadata>>(raw) }.getOrNull() }
                .orEmpty()
                .mapValues { (_, value) -> value.toDomain() }
        }

    val providerIcons: Flow<Map<String, String>> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            preferences[ProviderIconsJson]
                ?.let { raw -> runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull() }
                .orEmpty()
        }

    val euiccNames: Flow<Map<String, String>> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> readEuiccNames(preferences[EuiccNamesJson]) }

    /** Reads metadata and provider icons from one coherent DataStore preferences emission. */
    suspend fun snapshot(): ProfileMetadataSnapshot {
        val preferences = dataStore.data.first()
        return ProfileMetadataSnapshot(
            metadata = readStored(preferences[MetadataJson]),
            providerIcons = preferences[ProviderIconsJson]
                ?.let { raw ->
                    runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
                }
                .orEmpty(),
            euiccNames = readEuiccNames(preferences[EuiccNamesJson]),
        )
    }

    /** Restores a previously captured coherent snapshot in one DataStore transaction. */
    suspend fun restoreSnapshot(snapshot: ProfileMetadataSnapshot) {
        commitReminderMutation {
            dataStore.edit { preferences ->
                preferences[MetadataJson] = json.encodeToString(snapshot.metadata)
                preferences[ProviderIconsJson] = json.encodeToString(snapshot.providerIcons)
                preferences[EuiccNamesJson] = json.encodeToString(sanitizeEuiccNames(snapshot.euiccNames))
            }
        }
    }

    suspend fun setEuiccName(eid: String, name: String?) {
        dataStore.edit { preferences ->
            val current = readEuiccNames(preferences[EuiccNamesJson])
            val updated = applyEuiccNameMutation(current, eid, name)
            if (updated != current) {
                preferences[EuiccNamesJson] = json.encodeToString(updated)
            }
        }
    }

    suspend fun setTags(iccid: String, tags: Set<String>) {
        update(iccid) { copy(tags = normalizeProfileTags(tags)) }
    }

    suspend fun setPinned(iccid: String, pinned: Boolean) {
        update(iccid) { copy(isPinned = pinned) }
    }

    suspend fun setReminder(
        iccid: String,
        label: String,
        reminderAt: Instant?,
        enabled: Boolean = true,
    ) {
        commitReminderMutation {
            val persistedLabel = normalizeReminderLabel(label)
                ?: appContext.getString(R.string.profile_reminder_profile_fallback)
            update(iccid) {
                copy(
                    reminderEpochMillis = reminderAt?.toEpochMilli(),
                    reminderLabel = reminderAt?.let { persistedLabel },
                    reminderDeliveryClaimToken = null,
                )
            }
            scheduleProfileReminder(
                appContext,
                iccid,
                persistedLabel,
                reminderAt.takeIf { enabled },
            ).await()
        }
    }

    /** Returns every persisted reminder identity, including profiles not currently connected. */
    suspend fun persistedReminderIccids(): Set<String> {
        val stored = readStored(dataStore.data.first()[MetadataJson])
        return stored.reminderIccids()
    }

    /** Cancels the unique WorkManager request for each supplied persisted reminder identity. */
    suspend fun cancelReminders(iccids: Iterable<String>) {
        commitReminderMutation {
            collectReminderOperationFailures(iccids.toSet()) { iccid ->
                scheduleProfileReminder(appContext, iccid, "", null).await()
            }?.let { throw it }
        }
    }

    /** Reads the authoritative persisted schedule used to validate a running reminder worker. */
    internal suspend fun reminderDeliveryRecord(iccid: String): ProfileReminderDeliveryRecord? =
        readStored(dataStore.data.first()[MetadataJson])[iccid]?.let { metadata ->
            val epochMillis = metadata.reminderEpochMillis ?: return@let null
            val label = normalizeReminderLabel(metadata.reminderLabel) ?: return@let null
            ProfileReminderDeliveryRecord(epochMillis, label)
        }

    /**
     * Atomically claims a reminder only when it still matches the WorkManager request attempting
     * delivery. A replaced/removed reminder or a different in-flight worker cannot be claimed.
     */
    suspend fun claimReminderDelivery(
        iccid: String,
        expectedEpochMillis: Long,
        expectedLabel: String,
        claimToken: String,
    ): Boolean {
        return commitReminderMutation {
            var claimed = false
            dataStore.edit { preferences ->
                val current = readStored(preferences[MetadataJson]).toMutableMap()
                val existing = current[iccid]
                if (
                    existing?.reminderEpochMillis == expectedEpochMillis &&
                    normalizeReminderLabel(existing.reminderLabel) == expectedLabel &&
                    (existing.reminderDeliveryClaimToken == null ||
                        existing.reminderDeliveryClaimToken == claimToken)
                ) {
                    current[iccid] = existing.copy(reminderDeliveryClaimToken = claimToken)
                    preferences[MetadataJson] = json.encodeToString(current)
                    claimed = true
                }
            }
            claimed
        }
    }

    /** Confirms that neither the reminder nor its delivery claim changed after it was claimed. */
    suspend fun isReminderDeliveryClaimCurrent(
        iccid: String,
        expectedEpochMillis: Long,
        expectedLabel: String,
        claimToken: String,
    ): Boolean {
        val existing = readStored(dataStore.data.first()[MetadataJson])[iccid]
        return existing?.reminderEpochMillis == expectedEpochMillis &&
            normalizeReminderLabel(existing.reminderLabel) == expectedLabel &&
            existing.reminderDeliveryClaimToken == claimToken
    }

    suspend fun completeReminderDelivery(
        iccid: String,
        expectedEpochMillis: Long,
        claimToken: String,
    ) {
        commitReminderMutation {
            updateReminderClaim(iccid, expectedEpochMillis, claimToken) { existing ->
                existing.copy(
                    reminderEpochMillis = null,
                    reminderLabel = null,
                    reminderDeliveryClaimToken = null,
                )
            }
        }
    }

    suspend fun releaseReminderDeliveryClaim(
        iccid: String,
        expectedEpochMillis: Long,
        claimToken: String,
    ) {
        commitReminderMutation {
            updateReminderClaim(iccid, expectedEpochMillis, claimToken) { existing ->
                existing.copy(reminderDeliveryClaimToken = null)
            }
        }
    }

    private suspend fun updateReminderClaim(
        iccid: String,
        expectedEpochMillis: Long,
        claimToken: String,
        transform: (StoredProfileMetadata) -> StoredProfileMetadata,
    ) {
        dataStore.edit { preferences ->
            val current = readStored(preferences[MetadataJson]).toMutableMap()
            val existing = current[iccid] ?: return@edit
            if (
                existing.reminderEpochMillis == expectedEpochMillis &&
                existing.reminderDeliveryClaimToken == claimToken
            ) {
                current[iccid] = transform(existing)
                preferences[MetadataJson] = json.encodeToString(current)
            }
        }
    }

    suspend fun syncReminders(
        reminders: Map<String, Pair<String, Instant?>>,
        enabled: Boolean,
    ) {
        commitReminderMutation {
            // Persisted metadata is the source of truth. Caller-supplied entries provide fresher
            // display labels only; they must never exclude a disconnected or search-hidden
            // profile from repair.
            val fallbackLabel = appContext.getString(R.string.profile_reminder_profile_fallback)
            var stored = emptyMap<String, StoredProfileMetadata>()
            dataStore.edit { preferences ->
                val current = readStored(preferences[MetadataJson]).toMutableMap()
                var changed = false
                current.entries.forEach { (iccid, existing) ->
                    val requestedLabel = reminders[iccid]
                        ?.first
                        ?.let(::normalizeReminderLabel)
                    val nextLabel = if (existing.reminderEpochMillis != null) {
                        requestedLabel ?: normalizeReminderLabel(existing.reminderLabel) ?: fallbackLabel
                    } else {
                        null
                    }
                    if (
                        existing.reminderDeliveryClaimToken != null ||
                        existing.reminderLabel != nextLabel
                    ) {
                        current[iccid] = existing.copy(
                            reminderLabel = nextLabel,
                            reminderDeliveryClaimToken = null,
                        )
                        changed = true
                    }
                }
                if (changed) preferences[MetadataJson] = json.encodeToString(current)
                stored = current
            }

            val schedules = buildPersistedReminderSchedules(stored, fallbackLabel)
            val schedulingFailure = collectReminderOperationFailures(schedules) { schedule ->
                scheduleProfileReminder(
                    context = appContext,
                    iccid = schedule.iccid,
                    label = schedule.label,
                    reminderAt = schedule.reminderAt.takeIf { enabled },
                ).await()
            }

            // New requests are tagged, which lets repair remove work whose metadata was deleted
            // during a crash. Older untagged requests remain fail-closed because the worker
            // validates their timestamp against the same persisted source of truth.
            try {
                val activeIdentityTags = schedules
                    .mapTo(mutableSetOf()) { schedule -> reminderWorkIdentityTag(schedule.iccid) }
                withContext(Dispatchers.IO) {
                    WorkManager.getInstance(appContext)
                        .getWorkInfosByTag(ProfileReminderWorker.WorkTag)
                        .get()
                }
                    .filter { info ->
                        !enabled || info.tags.none(activeIdentityTags::contains)
                    }
                    .forEach { info ->
                        WorkManager.getInstance(appContext).cancelWorkById(info.id).await()
                    }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Enqueued work still validates persisted metadata at delivery time. A later
                // startup repair can retry this best-effort orphan cleanup.
            }
            schedulingFailure?.let { throw it }
        }
    }

    suspend fun setIconUri(
        iccid: String,
        iconUri: String?,
        providerName: String? = null,
    ) {
        val observedProviderKey = providerIconKey(providerName)
        update(iccid) {
            copy(
                iconUri = iconUri,
                providerKey = observedProviderKey ?: providerKey,
            )
        }
    }

    /**
     * Persists provider identities for every profile in a repository refresh in one transaction.
     * Existing metadata remains available after that reader disconnects, allowing a later
     * provider-wide icon change to include profiles that are no longer visible in the UI.
     */
    suspend fun recordProviderIdentities(providerNamesByIccid: Map<String, String>) {
        val normalized = providerNamesByIccid.mapValues { (_, providerName) ->
            providerIconKey(providerName)
        }
        if (normalized.isEmpty()) return
        dataStore.edit { preferences ->
            val current = readStored(preferences[MetadataJson])
            val updated = applyProviderIdentityUpdates(current, normalized)
            if (updated != current) preferences[MetadataJson] = json.encodeToString(updated)
        }
    }

    suspend fun setProviderIconUri(providerName: String, iconUri: String?) {
        val key = providerIconKey(providerName) ?: return
        dataStore.edit { preferences ->
            val current = preferences[ProviderIconsJson]
                ?.let { raw -> runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull() }
                .orEmpty()
                .toMutableMap()
            if (iconUri.isNullOrBlank()) {
                current.remove(key)
            } else {
                current[key] = iconUri
            }
            preferences[ProviderIconsJson] = json.encodeToString(current)
        }
    }

    /**
     * Commits a provider icon and removes overrides for every persisted profile last observed with
     * that provider. [profileIccids] additionally covers currently connected legacy entries that
     * predate persisted provider identities. Both changes happen in one DataStore transaction.
     */
    suspend fun setProviderIconAndClearProfileOverrides(
        providerName: String,
        iconUri: String?,
        profileIccids: Collection<String>,
    ): ProviderIconMutationResult {
        val providerKey = requireNotNull(providerIconKey(providerName)) {
            "A provider identity is required"
        }
        lateinit var result: ProviderIconMutationResult
        dataStore.edit { preferences ->
            val mutation = applyProviderIconMutation(
                metadata = readStored(preferences[MetadataJson]),
                providerIcons = preferences[ProviderIconsJson]
                    ?.let { raw ->
                        runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
                    }
                    .orEmpty(),
                providerKey = providerKey,
                iconUri = iconUri,
                profileIccids = profileIccids,
            )
            preferences[MetadataJson] = json.encodeToString(mutation.metadata)
            preferences[ProviderIconsJson] = json.encodeToString(mutation.providerIcons)
            result = mutation.result
        }
        return result
    }

    /** Clears per-profile icon overrides so a shared provider icon can take effect. */
    suspend fun clearProfileIconUris(iccids: Collection<String>): List<String> {
        if (iccids.isEmpty()) return emptyList()
        val removed = mutableListOf<String>()
        dataStore.edit { preferences ->
            val current = readStored(preferences[MetadataJson]).toMutableMap()
            iccids.forEach { iccid ->
                val existing = current[iccid] ?: return@forEach
                existing.iconUri?.let(removed::add)
                if (existing.iconUri != null) {
                    current[iccid] = existing.copy(iconUri = null)
                }
            }
            preferences[MetadataJson] = json.encodeToString(current)
        }
        return removed
    }

    /**
     * Removes incomplete staging files and live icons not referenced by a coherent DataStore
     * snapshot. Malformed metadata fails closed: staging is still safe to clear, but live artwork
     * is retained rather than treating a decode error as an empty reference set.
     */
    suspend fun cleanupOrphanedIconFiles() {
        withContext(Dispatchers.IO) { iconStorage.deleteStagedImports() }
        val preferences = dataStore.data.first()
        val storedMetadata = readStoredStrict(preferences[MetadataJson]) ?: return
        val storedProviderIcons = readProviderIconsStrict(preferences[ProviderIconsJson]) ?: return
        val referencedUris = storedMetadata.values.mapNotNull(StoredProfileMetadata::iconUri) +
            storedProviderIcons.values
        withContext(Dispatchers.IO) {
            iconStorage.deleteUnreferencedLiveIcons(referencedUris)
        }
    }

    suspend fun setCloudData(
        iccid: String,
        smdpAddress: String?,
        installedBytes: Long?,
        eid: String?,
    ) {
        update(iccid) {
            copy(
                smdpAddress = smdpAddress?.takeIf(String::isNotBlank) ?: this.smdpAddress,
                installedBytes = installedBytes?.takeIf { it > 0 } ?: this.installedBytes,
                installedEid = if (installedBytes != null && installedBytes > 0) {
                    eid?.takeIf(String::isNotBlank)
                } else {
                    this.installedEid
                },
            )
        }
    }

    suspend fun clear(iccid: String): ProfileMetadata? = commitReminderMutation {
        var removed: StoredProfileMetadata? = null
        dataStore.edit { preferences ->
            val current = readStored(preferences[MetadataJson]).toMutableMap()
            removed = current.remove(iccid)
            preferences[MetadataJson] = json.encodeToString(current)
        }
        val schedulingFailure = collectReminderOperationFailures(listOf(iccid)) { removedIccid ->
            scheduleProfileReminder(appContext, removedIccid, "", null).await()
        }
        cleanupOrphanedIconFiles()
        schedulingFailure?.let { throw it }
        removed?.toDomain()
    }

    /**
     * Removes only metadata associated with the eUICC being reset. Entries for
     * other cards are intentionally retained.
     */
    suspend fun clearForEuicc(
        eid: String?,
        knownIccids: Collection<String>,
    ): Map<String, ProfileMetadata> = commitReminderMutation {
        val iccidSet = knownIccids.toSet()
        val removed = linkedMapOf<String, StoredProfileMetadata>()
        dataStore.edit { preferences ->
            val current = readStored(preferences[MetadataJson]).toMutableMap()
            current.entries.removeAll { (iccid, metadata) ->
                val matches = iccid in iccidSet ||
                    (!eid.isNullOrBlank() && metadata.installedEid == eid)
                if (matches) removed[iccid] = metadata
                matches
            }
            preferences[MetadataJson] = json.encodeToString(current)
        }
        val schedulingFailure = collectReminderOperationFailures(removed.keys) { iccid ->
            scheduleProfileReminder(appContext, iccid, "", null).await()
        }
        cleanupOrphanedIconFiles()
        schedulingFailure?.let { throw it }
        removed.mapValues { (_, metadata) -> metadata.toDomain() }
    }

    suspend fun replaceAll(
        metadata: Map<String, ProfileMetadata>,
        providerIcons: Map<String, String>,
        euiccNames: Map<String, String> = emptyMap(),
    ) {
        commitReminderMutation {
            val storedMetadata = metadata.mapValues { (_, value) -> value.toStored() }
            dataStore.edit { preferences ->
                preferences[MetadataJson] = json.encodeToString(storedMetadata)
                preferences[ProviderIconsJson] = json.encodeToString(providerIcons)
                preferences[EuiccNamesJson] = json.encodeToString(sanitizeEuiccNames(euiccNames))
            }
        }
    }

    /** Once a cross-system reminder mutation begins, finish its DataStore/WorkManager repair. */
    private suspend fun <T> commitReminderMutation(block: suspend () -> T): T =
        withProfileReminderIsolation {
            withContext(NonCancellable) { block() }
        }

    private suspend fun <T> collectReminderOperationFailures(
        items: Iterable<T>,
        operation: suspend (T) -> Unit,
    ): Exception? {
        var firstFailure: Exception? = null
        items.forEach { item ->
            try {
                operation(item)
            } catch (error: Exception) {
                if (firstFailure == null) {
                    firstFailure = error
                } else {
                    firstFailure.addSuppressed(error)
                }
            }
        }
        return firstFailure
    }

    private suspend fun update(
        iccid: String,
        transform: StoredProfileMetadata.() -> StoredProfileMetadata,
    ) {
        dataStore.edit { preferences ->
            val current = readStored(preferences[MetadataJson]).toMutableMap()
            current[iccid] = (current[iccid] ?: StoredProfileMetadata()).transform()
            preferences[MetadataJson] = json.encodeToString(current)
        }
    }

    private fun readStored(raw: String?): Map<String, StoredProfileMetadata> = raw
        ?.let { value -> runCatching { json.decodeFromString<Map<String, StoredProfileMetadata>>(value) }.getOrNull() }
        .orEmpty()

    private fun readStoredStrict(raw: String?): Map<String, StoredProfileMetadata>? = when (raw) {
        null -> emptyMap()
        else -> runCatching {
            json.decodeFromString<Map<String, StoredProfileMetadata>>(raw)
        }.getOrNull()
    }

    private fun readProviderIconsStrict(raw: String?): Map<String, String>? = when (raw) {
        null -> emptyMap()
        else -> runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
    }

    private fun readEuiccNames(raw: String?): Map<String, String> = sanitizeEuiccNames(
        raw?.let { value -> runCatching { json.decodeFromString<Map<String, String>>(value) }.getOrNull() }
            .orEmpty(),
    )

    private fun StoredProfileMetadata.toDomain(): ProfileMetadata = ProfileMetadata(
        tags = tags,
        reminderAt = reminderEpochMillis?.let(Instant::ofEpochMilli),
        reminderLabel = normalizeReminderLabel(reminderLabel),
        iconUri = iconUri,
        smdpAddress = smdpAddress,
        installedBytes = installedBytes,
        installedEid = installedEid,
        providerKey = providerKey,
        isPinned = isPinned,
    )

    private fun ProfileMetadata.toStored(): StoredProfileMetadata = StoredProfileMetadata(
        tags = normalizeProfileTags(tags),
        reminderEpochMillis = reminderAt?.toEpochMilli(),
        reminderLabel = reminderAt?.let { normalizeReminderLabel(reminderLabel) },
        iconUri = iconUri,
        smdpAddress = smdpAddress,
        installedBytes = installedBytes?.takeIf { it > 0 },
        installedEid = installedEid,
        providerKey = providerIconKey(providerKey),
        isPinned = isPinned,
    )

    private companion object {
        val MetadataJson = stringPreferencesKey("metadata_json")
        val ProviderIconsJson = stringPreferencesKey("provider_icons_json")
        val EuiccNamesJson = stringPreferencesKey("euicc_names_json")
    }
}

fun providerIconKey(providerName: String?): String? =
    providerName?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)

internal data class ProviderIconMutation(
    val metadata: Map<String, StoredProfileMetadata>,
    val providerIcons: Map<String, String>,
    val result: ProviderIconMutationResult,
)

internal fun applyProviderIconMutation(
    metadata: Map<String, StoredProfileMetadata>,
    providerIcons: Map<String, String>,
    providerKey: String,
    iconUri: String?,
    profileIccids: Collection<String>,
): ProviderIconMutation {
    val nextMetadata = metadata.toMutableMap()
    val nextProviderIcons = providerIcons.toMutableMap()
    val previousProviderIconUri = nextProviderIcons[providerKey]
    if (iconUri.isNullOrBlank()) {
        nextProviderIcons.remove(providerKey)
    } else {
        nextProviderIcons[providerKey] = iconUri
    }

    val removedProfileIconUris = linkedSetOf<String>()
    val matchingPersistedIccids = nextMetadata
        .filterValues { metadata -> metadata.providerKey == providerKey }
        .keys
    (profileIccids + matchingPersistedIccids).toSet().forEach { iccid ->
        val existing = nextMetadata[iccid] ?: StoredProfileMetadata()
        existing.iconUri?.let(removedProfileIconUris::add)
        val updated = existing.copy(iconUri = null, providerKey = providerKey)
        if (updated != existing || iccid !in nextMetadata) {
            nextMetadata[iccid] = updated
        }
    }
    return ProviderIconMutation(
        metadata = nextMetadata,
        providerIcons = nextProviderIcons,
        result = ProviderIconMutationResult(
            previousProviderIconUri = previousProviderIconUri,
            removedProfileIconUris = removedProfileIconUris,
        ),
    )
}

internal fun applyProviderIdentityUpdates(
    metadata: Map<String, StoredProfileMetadata>,
    providerKeysByIccid: Map<String, String?>,
): Map<String, StoredProfileMetadata> {
    if (providerKeysByIccid.isEmpty()) return metadata
    val updated = metadata.toMutableMap()
    providerKeysByIccid.forEach { (iccid, providerKey) ->
        val existing = updated[iccid] ?: StoredProfileMetadata()
        if (existing.providerKey != providerKey) {
            updated[iccid] = existing.copy(providerKey = providerKey)
        }
    }
    return updated
}

internal fun Map<String, StoredProfileMetadata>.reminderIccids(): Set<String> = entries
    .asSequence()
    .filter { (_, metadata) -> metadata.reminderEpochMillis != null }
    .mapTo(linkedSetOf()) { (iccid, _) -> iccid }

internal data class PersistedReminderSchedule(
    val iccid: String,
    val label: String,
    val reminderAt: Instant,
)

internal fun buildPersistedReminderSchedules(
    metadata: Map<String, StoredProfileMetadata>,
    fallbackLabel: String,
): List<PersistedReminderSchedule> = metadata.mapNotNull { (iccid, stored) ->
    stored.reminderEpochMillis?.let { epochMillis ->
        PersistedReminderSchedule(
            iccid = iccid,
            label = normalizeReminderLabel(stored.reminderLabel)
                ?: normalizeReminderLabel(fallbackLabel)
                ?: "Profile",
            reminderAt = Instant.ofEpochMilli(epochMillis),
        )
    }
}

internal fun normalizeReminderLabel(label: String?): String? =
    label?.trim()?.take(MaxReminderLabelLength)?.takeIf(String::isNotEmpty)

internal fun normalizeEuiccEid(eid: String?): String? = eid
    ?.trim()
    ?.takeIf { value -> value.length == 32 && value.all(Char::isDigit) }

internal fun normalizeEuiccName(name: String?): String? = name
    ?.trim()
    ?.takeUnicodeCodePoints(MaxEuiccNameLength)
    ?.takeIf(String::isNotEmpty)

internal fun sanitizeEuiccNames(names: Map<String, String>): Map<String, String> = names.mapNotNull { (eid, name) ->
    val normalizedEid = normalizeEuiccEid(eid) ?: return@mapNotNull null
    val normalizedName = normalizeEuiccName(name) ?: return@mapNotNull null
    normalizedEid to normalizedName
}.toMap()

internal fun applyEuiccNameMutation(
    names: Map<String, String>,
    eid: String,
    name: String?,
): Map<String, String> {
    val normalizedEid = normalizeEuiccEid(eid) ?: return names
    val updated = names.toMutableMap()
    val normalizedName = normalizeEuiccName(name)
    if (normalizedName == null) {
        updated.remove(normalizedEid)
    } else {
        updated[normalizedEid] = normalizedName
    }
    return updated
}

internal fun normalizeProfileTags(tags: Iterable<String>): Set<String> {
    val normalized = linkedMapOf<String, String>()
    tags.forEach { rawTag ->
        val tag = rawTag.trim().take(32)
        if (tag.isNotEmpty()) normalized.putIfAbsent(tag.lowercase(Locale.ROOT), tag)
    }
    return normalized.values.take(16).toCollection(linkedSetOf())
}

private const val MaxReminderLabelLength = 128
private const val MaxEuiccNameLength = 64
