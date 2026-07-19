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

private val Context.profileMetadataDataStore by preferencesDataStore(name = "profile_metadata")

@Serializable
data class StoredProfileMetadata(
    val tags: Set<String> = emptySet(),
    val reminderEpochMillis: Long? = null,
    val iconUri: String? = null,
)

data class ProfileMetadata(
    val tags: Set<String> = emptySet(),
    val reminderAt: Instant? = null,
    val iconUri: String? = null,
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

    suspend fun setTags(iccid: String, tags: Set<String>) {
        update(iccid) { copy(tags = tags.map(String::trim).filter(String::isNotEmpty).toSet()) }
    }

    suspend fun setReminder(iccid: String, label: String, reminderAt: Instant?) {
        update(iccid) { copy(reminderEpochMillis = reminderAt?.toEpochMilli()) }
        scheduleProfileReminder(appContext, iccid, label, reminderAt)
    }

    suspend fun setIconUri(iccid: String, iconUri: String?) {
        update(iccid) { copy(iconUri = iconUri) }
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
    )

    private companion object {
        val MetadataJson = stringPreferencesKey("metadata_json")
    }
}
