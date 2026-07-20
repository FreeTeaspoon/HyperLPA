package app.hyperlpa.data.backup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.hyperlpa.data.metadata.ProfileMetadata
import app.hyperlpa.data.metadata.ProfileMetadataStore
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.AppSettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HyperLpaBackupManager(
    context: Context,
    private val settingsStore: AppSettingsStore,
    private val metadataStore: ProfileMetadataStore,
) {
    private val appContext = context.applicationContext

    fun createBackup(
        settings: AppSettings,
        metadata: Map<String, ProfileMetadata>,
        providerIcons: Map<String, String>,
    ): String {
        val backup = HyperLpaBackup(
            createdAtEpochMillis = System.currentTimeMillis(),
            settings = settings,
            profiles = metadata.mapValues { (_, value) ->
                BackupProfileMetadata(
                    tags = value.tags,
                    reminderEpochMillis = value.reminderAt?.toEpochMilli(),
                    smdpAddress = value.smdpAddress,
                    installedBytes = value.installedBytes,
                    installedEid = value.installedEid,
                    iconBase64 = value.iconUri?.let(::encodeIcon),
                )
            },
            providerIcons = providerIcons.mapValues { (_, uri) -> encodeIcon(uri) },
        )
        return encodeBackup(backup)
    }

    suspend fun restoreBackup(
        rawBackup: String,
        previousMetadata: Map<String, ProfileMetadata>,
        previousProviderIcons: Map<String, String>,
    ): RestoredHyperLpaBackup {
        val backup = decodeBackup(rawBackup)
        val iconsDir = File(appContext.filesDir, IconsDirectory).apply {
            check(mkdirs() || isDirectory) { "Could not create the profile icon directory" }
        }
        val restoreId = System.nanoTime().toString()
        val createdFiles = mutableListOf<File>()

        return try {
            val restoredMetadata = backup.profiles.entries.mapIndexed { index, (iccid, value) ->
                val iconUri = value.iconBase64?.let { encoded ->
                    restoreIcon(
                        encoded = encoded,
                        destination = File(iconsDir, "restored_profile_${restoreId}_$index.img"),
                        createdFiles = createdFiles,
                    )
                }
                iccid to ProfileMetadata(
                    tags = value.tags,
                    reminderAt = value.reminderEpochMillis?.let(java.time.Instant::ofEpochMilli),
                    iconUri = iconUri,
                    smdpAddress = value.smdpAddress,
                    installedBytes = value.installedBytes?.takeIf { it > 0 },
                    installedEid = value.installedEid,
                )
            }.toMap()
            val restoredProviderIcons = backup.providerIcons.entries
                .mapIndexed { index, (provider, encoded) ->
                    provider to restoreIcon(
                        encoded = encoded,
                        destination = File(iconsDir, "restored_provider_${restoreId}_$index.img"),
                        createdFiles = createdFiles,
                    )
                }
                .toMap()

            settingsStore.replaceSettings(backup.settings)
            metadataStore.replaceAll(restoredMetadata, restoredProviderIcons)

            val retainedUris = restoredMetadata.values.mapNotNull(ProfileMetadata::iconUri).toSet() +
                restoredProviderIcons.values
            (previousMetadata.values.mapNotNull(ProfileMetadata::iconUri) + previousProviderIcons.values)
                .filterNot(retainedUris::contains)
                .forEach { uri -> deleteStoredIcon(uri, iconsDir) }

            RestoredHyperLpaBackup(
                settings = backup.settings,
                metadata = restoredMetadata,
            )
        } catch (error: Throwable) {
            createdFiles.forEach(File::delete)
            throw error
        }
    }

    private fun encodeIcon(uri: String): String {
        val input = appContext.contentResolver.openInputStream(Uri.parse(uri))
            ?: error("Could not read a saved profile icon")
        val bytes = input.use { it.readLimited(MaxIconBytes) }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun restoreIcon(
        encoded: String,
        destination: File,
        createdFiles: MutableList<File>,
    ): String {
        require(encoded.length <= MaxEncodedIconCharacters) { "A backed-up icon is too large" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MaxIconBytes) { "A backed-up icon is too large" }
        destination.outputStream().use { output -> output.write(bytes) }
        createdFiles += destination
        return destination.toUri().toString()
    }

    private fun deleteStoredIcon(uri: String, iconsDir: File) {
        runCatching {
            val file = Uri.parse(uri).path?.let(::File) ?: return
            if (file.exists() && file.canonicalFile.startsWith(iconsDir.canonicalFile)) {
                file.delete()
            }
        }
    }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "A saved profile icon is too large to back up" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val IconsDirectory = "profile-icons"
        const val MaxIconBytes = 16 * 1024 * 1024
        const val MaxEncodedIconCharacters = (MaxIconBytes * 4 / 3) + 8
    }
}

data class RestoredHyperLpaBackup(
    val settings: AppSettings,
    val metadata: Map<String, ProfileMetadata>,
)

@Serializable
internal data class HyperLpaBackup(
    val format: String = BackupFormat,
    val version: Int = CurrentBackupVersion,
    val createdAtEpochMillis: Long,
    val settings: AppSettings,
    val profiles: Map<String, BackupProfileMetadata> = emptyMap(),
    val providerIcons: Map<String, String> = emptyMap(),
)

@Serializable
internal data class BackupProfileMetadata(
    val tags: Set<String> = emptySet(),
    val reminderEpochMillis: Long? = null,
    val smdpAddress: String? = null,
    val installedBytes: Long? = null,
    val installedEid: String? = null,
    val iconBase64: String? = null,
)

internal fun decodeBackup(rawBackup: String): HyperLpaBackup {
    val backup = BackupJson.decodeFromString<HyperLpaBackup>(rawBackup)
    require(backup.format == BackupFormat) { "This is not a HyperLPA backup" }
    require(backup.version == CurrentBackupVersion) { "Unsupported HyperLPA backup version" }
    require(backup.profiles.size <= MaxBackedUpProfiles) { "The backup contains too many profiles" }
    require(backup.providerIcons.size <= MaxBackedUpProviderIcons) {
        "The backup contains too many provider icons"
    }
    return backup
}

internal fun encodeBackup(backup: HyperLpaBackup): String = BackupJson.encodeToString(backup)

private const val BackupFormat = "hyperlpa-backup"
private const val CurrentBackupVersion = 1
private const val MaxBackedUpProfiles = 10_000
private const val MaxBackedUpProviderIcons = 10_000
private val BackupJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = true
}
