package app.hyperlpa.data.metadata

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.hyperlpa.reminders.scheduleProfileReminder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
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
    val iconUri: String? = null,
    val smdpAddress: String? = null,
    val installedBytes: Long? = null,
    val installedEid: String? = null,
)

data class ProfileMetadata(
    val tags: Set<String> = emptySet(),
    val reminderAt: Instant? = null,
    val iconUri: String? = null,
    val smdpAddress: String? = null,
    val installedBytes: Long? = null,
    val installedEid: String? = null,
)

class ProfileMetadataStore(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.profileMetadataDataStore
    private val json = Json { ignoreUnknownKeys = true }

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

    suspend fun setTags(iccid: String, tags: Set<String>) {
        update(iccid) { copy(tags = normalizeProfileTags(tags)) }
    }

    suspend fun setReminder(
        iccid: String,
        label: String,
        reminderAt: Instant?,
        enabled: Boolean = true,
    ) {
        update(iccid) { copy(reminderEpochMillis = reminderAt?.toEpochMilli()) }
        scheduleProfileReminder(appContext, iccid, label, reminderAt.takeIf { enabled })
    }

    suspend fun markReminderDelivered(iccid: String) {
        update(iccid) { copy(reminderEpochMillis = null) }
    }

    fun syncReminders(
        reminders: Map<String, Pair<String, Instant?>>,
        enabled: Boolean,
    ) {
        reminders.forEach { (iccid, reminder) ->
            scheduleProfileReminder(
                context = appContext,
                iccid = iccid,
                label = reminder.first,
                reminderAt = reminder.second.takeIf { enabled },
            )
        }
    }

    suspend fun setIconUri(iccid: String, iconUri: String?) {
        update(iccid) { copy(iconUri = iconUri) }
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

    suspend fun clear(iccid: String) {
        dataStore.edit { preferences ->
            val current = readStored(preferences[MetadataJson]).toMutableMap()
            current.remove(iccid)
            preferences[MetadataJson] = json.encodeToString(current)
        }
        scheduleProfileReminder(appContext, iccid, iccid, null)
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

    private fun StoredProfileMetadata.toDomain(): ProfileMetadata = ProfileMetadata(
        tags = tags,
        reminderAt = reminderEpochMillis?.let(Instant::ofEpochMilli),
        iconUri = iconUri,
        smdpAddress = smdpAddress,
        installedBytes = installedBytes,
        installedEid = installedEid,
    )

    private companion object {
        val MetadataJson = stringPreferencesKey("metadata_json")
        val ProviderIconsJson = stringPreferencesKey("provider_icons_json")
    }
}

fun providerIconKey(providerName: String?): String? =
    providerName?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

internal fun normalizeProfileTags(tags: Iterable<String>): Set<String> {
    val normalized = linkedMapOf<String, String>()
    tags.forEach { rawTag ->
        val tag = rawTag.trim().take(32)
        if (tag.isNotEmpty()) normalized.putIfAbsent(tag.lowercase(Locale.ROOT), tag)
    }
    return normalized.values.take(16).toCollection(linkedSetOf())
}
