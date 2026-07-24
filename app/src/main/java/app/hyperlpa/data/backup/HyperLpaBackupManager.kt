package app.hyperlpa.data.backup

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.core.net.toUri
import app.hyperlpa.R
import app.hyperlpa.data.metadata.ProfileMetadata
import app.hyperlpa.data.metadata.ProfileMetadataSnapshot
import app.hyperlpa.data.metadata.ProfileMetadataStore
import app.hyperlpa.data.metadata.normalizeReminderLabel
import app.hyperlpa.data.settings.AppSettings
import app.hyperlpa.data.settings.AppSettingsRecoverySnapshot
import app.hyperlpa.data.settings.AppSettingsStore
import app.hyperlpa.reminders.withProfileReminderIsolation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HyperLpaBackupManager(
    context: Context,
    private val settingsStore: AppSettingsStore,
    private val metadataStore: ProfileMetadataStore,
) {
    private val appContext = context.applicationContext
    private val restoreJournalFile = File(appContext.noBackupFilesDir, RestoreJournalFileName)
    private val restoreJournal = AtomicFile(restoreJournalFile)
    private val restoreMutex = Mutex()

    suspend fun createBackup(
        passphrase: CharArray,
    ): String = withProfileReminderIsolation {
        // Read both stores directly while the caller holds its application-state mutation gate.
        // Combined UI state is intentionally not used: StateFlow collection can lag a committed
        // DataStore generation and produce a backup that is already stale when it is written.
        val settings = settingsStore.snapshot()
        val metadata = metadataStore.snapshot()
        val backup = HyperLpaBackup(
            createdAtEpochMillis = System.currentTimeMillis(),
            settings = settings,
            profiles = metadata.metadata.mapValues { (_, value) ->
                BackupProfileMetadata(
                    tags = value.tags,
                    reminderEpochMillis = value.reminderEpochMillis,
                    reminderLabel = value.reminderLabel,
                    smdpAddress = value.smdpAddress,
                    installedBytes = value.installedBytes,
                    installedEid = value.installedEid,
                    providerKey = value.providerKey,
                    iconBase64 = value.iconUri?.let(::encodeIcon),
                )
            },
            providerIcons = metadata.providerIcons.mapValues { (_, uri) -> encodeIcon(uri) },
        )
        encryptBackup(backup, passphrase)
    }

    suspend fun restoreBackup(
        rawBackup: String,
        passphrase: CharArray,
    ): RestoredHyperLpaBackup = restoreMutex.withLock {
        withProfileReminderIsolation {
        // Never stack a new restore on top of an interrupted cross-store commit.
        recoverInterruptedRestoreLocked()

        val backup = decryptBackup(rawBackup, passphrase)
        val previousSettings = settingsStore.snapshotForRecovery()
        val previousMetadata = metadataStore.snapshot()
        val iconsDir = File(appContext.filesDir, IconsDirectory)
        val restoreId = UUID.randomUUID().toString()
        val profileIconFiles = backup.profiles.entries.mapIndexedNotNull { index, (_, value) ->
            value.iconBase64?.let { File(iconsDir, "restored_profile_${restoreId}_$index.img") }
        }
        val providerIconFiles = backup.providerIcons.entries.mapIndexed { index, _ ->
            File(iconsDir, "restored_provider_${restoreId}_$index.img")
        }
        val plannedFiles = profileIconFiles + providerIconFiles
        require(plannedFiles.none(File::exists)) { "A restore destination already exists" }

        var profileIconIndex = 0
        val restoredMetadata = backup.profiles.mapValues { (_, value) ->
            val iconUri = value.iconBase64?.let {
                profileIconFiles[profileIconIndex++].toUri().toString()
            }
            ProfileMetadata(
                tags = value.tags,
                reminderAt = value.reminderEpochMillis?.let(java.time.Instant::ofEpochMilli),
                reminderLabel = value.reminderLabel,
                iconUri = iconUri,
                smdpAddress = value.smdpAddress,
                installedBytes = value.installedBytes?.takeIf { it > 0 },
                installedEid = value.installedEid,
                providerKey = value.providerKey,
            )
        }
        val restoredProviderIcons = backup.providerIcons.keys.mapIndexed { index, provider ->
            provider to providerIconFiles[index].toUri().toString()
        }.toMap()
        val restoredSettings = backup.settings.safeForRestore()
        val journal = RestoreRecoveryJournal(
            previousSettings = previousSettings,
            previousMetadata = previousMetadata,
            createdIconPaths = plannedFiles.map(File::getAbsolutePath),
        )

        // Commit the durable rollback record before the first icon or DataStore write. Process
        // death at any later instruction is recovered synchronously at next app start.
        writeRestoreJournal(journal)
        try {
            check(iconsDir.mkdirs() || iconsDir.isDirectory) {
                "Could not create the profile icon directory"
            }
            var nextProfileIcon = 0
            backup.profiles.values.forEach { value ->
                value.iconBase64?.let { encoded ->
                    restoreIcon(encoded, profileIconFiles[nextProfileIcon++])
                }
            }
            backup.providerIcons.values.forEachIndexed { index, encoded ->
                restoreIcon(encoded, providerIconFiles[index])
            }

            settingsStore.replaceSettings(restoredSettings)
            metadataStore.replaceAll(restoredMetadata, restoredProviderIcons)
            val previousReminderIccids = previousMetadata.metadata
                .filterValues { metadata -> metadata.reminderEpochMillis != null }
                .keys
            val reminderFallback = appContext.getString(R.string.profile_reminder_profile_fallback)
            metadataStore.cancelReminders(previousReminderIccids)
            metadataStore.syncReminders(
                reminders = restoredMetadata.mapValues { (_, metadata) ->
                    (metadata.reminderLabel ?: reminderFallback) to metadata.reminderAt
                },
                enabled = restoredSettings.scheduledReminders,
            )
            deleteRestoreJournal()

            val retainedUris = restoredMetadata.values.mapNotNull(ProfileMetadata::iconUri).toSet() +
                restoredProviderIcons.values
            (previousMetadata.metadata.values.mapNotNull { metadata -> metadata.iconUri } +
                previousMetadata.providerIcons.values)
                .filterNot(retainedUris::contains)
                .forEach { uri -> deleteStoredIcon(uri, iconsDir) }

            RestoredHyperLpaBackup(
                settings = restoredSettings,
                metadata = restoredMetadata,
            )
        } catch (error: Throwable) {
            val rollbackFailure = runCatching {
                withContext(NonCancellable) { rollbackRestore(journal) }
            }.exceptionOrNull()
            rollbackFailure?.let(error::addSuppressed)
            throw error
        }
        }
    }

    /** Restores the last complete generation before repositories or UI begin using it. */
    suspend fun recoverInterruptedRestore() = restoreMutex.withLock {
        withProfileReminderIsolation { recoverInterruptedRestoreLocked() }
    }

    private suspend fun recoverInterruptedRestoreLocked() {
        val journal = try {
            readRestoreJournal()
        } catch (_: CorruptRestoreJournalException) {
            quarantineCorruptRestoreJournal()
            return
        } ?: return
        withContext(NonCancellable) { rollbackRestore(journal) }
    }

    private suspend fun rollbackRestore(journal: RestoreRecoveryJournal) {
        val supersededReminderIccids = runCatching { metadataStore.persistedReminderIccids() }
            .getOrDefault(emptySet())
        settingsStore.restoreRecoverySnapshot(journal.previousSettings)
        metadataStore.restoreSnapshot(journal.previousMetadata)
        val reminderFallback = appContext.getString(R.string.profile_reminder_profile_fallback)
        val recoveredReminders: Map<String, Pair<String, java.time.Instant?>> =
            journal.previousMetadata.metadata.mapNotNull { (iccid, metadata) ->
            metadata.reminderEpochMillis?.let { epochMillis ->
                iccid to (
                    (metadata.reminderLabel ?: reminderFallback) to
                        java.time.Instant.ofEpochMilli(epochMillis)
                    )
            }
        }.toMap()
        deletePlannedRestoreFiles(journal.createdIconPaths)
        deleteRestoreJournal()
        // The two DataStores are the durable rollback boundary. Workers validate their timestamp
        // against metadata before posting, and startup reconciliation repairs missing/replaced
        // requests, so WorkManager availability must not keep a successfully rolled-back journal
        // active forever.
        runCatching {
            metadataStore.cancelReminders(supersededReminderIccids + recoveredReminders.keys)
            metadataStore.syncReminders(
                reminders = recoveredReminders,
                enabled = journal.previousSettings.settings.scheduledReminders,
            )
        }
    }

    private fun writeRestoreJournal(journal: RestoreRecoveryJournal) {
        val bytes = BackupJson.encodeToString(journal).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MaxRestoreJournalBytes) { "The restore recovery journal is too large" }
        val output = restoreJournal.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            restoreJournal.finishWrite(output)
        } catch (error: Throwable) {
            restoreJournal.failWrite(output)
            throw error
        } finally {
            bytes.fill(0)
        }
    }

    private fun readRestoreJournal(): RestoreRecoveryJournal? {
        if (!restoreJournalFile.isFile) return null
        val bytes = try {
            restoreJournal.openRead().use { input ->
                input.readLimited(MaxRestoreJournalBytes, "The restore recovery journal is too large")
            }
        } catch (error: IOException) {
            // An I/O failure may be transient. Preserve the journal so a later launch can retry.
            throw error
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            throw CorruptRestoreJournalException(error)
        }
        return try {
            BackupJson.decodeFromString<RestoreRecoveryJournal>(
                String(bytes, StandardCharsets.UTF_8),
            ).also { journal ->
                require(journal.format == RestoreJournalFormat) { "Invalid restore recovery journal" }
                require(journal.version == CurrentRestoreJournalVersion) {
                    "Unsupported restore recovery journal version"
                }
                require(journal.createdIconPaths.size <= MaxBackedUpProfiles + MaxBackedUpProviderIcons) {
                    "The restore recovery journal contains too many files"
                }
            }
        } catch (error: Exception) {
            throw CorruptRestoreJournalException(error)
        } finally {
            bytes.fill(0)
        }
    }

    private fun quarantineCorruptRestoreJournal() {
        val quarantined = File(appContext.noBackupFilesDir, CorruptRestoreJournalFileName)
        runCatching {
            if (quarantined.exists()) check(quarantined.delete()) {
                "Could not replace the corrupt restore recovery journal"
            }
            check(restoreJournalFile.renameTo(quarantined)) {
                "Could not quarantine the corrupt restore recovery journal"
            }
        }
        // A malformed journal cannot be replayed safely. If preserving a quarantined copy was not
        // possible, fail closed by removing the active AtomicFile so it cannot crash every launch.
        if (restoreJournalFile.exists()) restoreJournal.delete()
    }

    private fun deleteRestoreJournal() {
        restoreJournal.delete()
        check(!restoreJournalFile.exists()) { "Could not remove the restore recovery journal" }
    }

    private fun deletePlannedRestoreFiles(paths: Collection<String>) {
        val iconsDir = File(appContext.filesDir, IconsDirectory).canonicalFile
        paths.forEach { path ->
            runCatching {
                val candidate = File(path).canonicalFile
                if (candidate.toPath().startsWith(iconsDir.toPath())) candidate.delete()
            }
        }
    }

    private fun encodeIcon(uri: String): String {
        val input = appContext.contentResolver.openInputStream(uri.toUri())
            ?: error("Could not read a saved profile icon")
        val bytes = input.use {
            it.readLimited(MaxIconBytes, "A saved profile icon is too large to back up")
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun restoreIcon(
        encoded: String,
        destination: File,
    ): String {
        require(encoded.length <= MaxEncodedIconCharacters) { "A backed-up icon is too large" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MaxIconBytes) { "A backed-up icon is too large" }
        try {
            destination.outputStream().use { output -> output.write(bytes) }
            validateRestoredIcon(destination)
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
        return destination.toUri().toString()
    }

    /**
     * Backup authentication proves who created the bytes, not that an imported image is safe to
     * decode on this device. Apply the same bounded two-pass decode used for newly selected icons
     * before making a restored file visible to the rest of the app.
     */
    private fun validateRestoredIcon(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        require(width in 1..MaxIconDimension) { "A backed-up icon has invalid dimensions" }
        require(height in 1..MaxIconDimension) { "A backed-up icon has invalid dimensions" }
        require(width.toLong() * height <= MaxIconPixels) { "A backed-up icon is too large" }

        var sampleSize = 1
        while (width / sampleSize > MaxDecodedIconDimension || height / sampleSize > MaxDecodedIconDimension) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: error("A backed-up icon could not be decoded")
        decoded.recycle()
    }

    private fun deleteStoredIcon(uri: String, iconsDir: File) {
        runCatching {
            val file = uri.toUri().path?.let(::File) ?: return
            val candidate = file.canonicalFile
            if (candidate.isFile && candidate.toPath().startsWith(iconsDir.canonicalFile.toPath())) {
                candidate.delete()
            }
        }
    }

    private fun InputStream.readLimited(maxBytes: Int, tooLargeMessage: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { tooLargeMessage }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        internal fun hasInterruptedRestore(context: Context): Boolean =
            File(context.applicationContext.noBackupFilesDir, RestoreJournalFileName).isFile

        private const val IconsDirectory = "profile-icons"
        private const val RestoreJournalFileName = "backup-restore-recovery-v1.json"
        private const val CorruptRestoreJournalFileName = "$RestoreJournalFileName.corrupt"
        private const val MaxRestoreJournalBytes = 8 * 1024 * 1024
        private const val MaxIconBytes = 4 * 1024 * 1024
        private const val MaxEncodedIconCharacters = (MaxIconBytes * 4 / 3) + 8
        private const val MaxIconDimension = 16_384
        private const val MaxIconPixels = 64_000_000L
        private const val MaxDecodedIconDimension = 2_048
    }
}

data class RestoredHyperLpaBackup(
    val settings: AppSettings,
    val metadata: Map<String, ProfileMetadata>,
)

@Serializable
private data class RestoreRecoveryJournal(
    val format: String = RestoreJournalFormat,
    val version: Int = CurrentRestoreJournalVersion,
    val previousSettings: AppSettingsRecoverySnapshot,
    val previousMetadata: ProfileMetadataSnapshot,
    val createdIconPaths: List<String>,
)

private class CorruptRestoreJournalException(cause: Throwable) : IOException(cause)

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
    val reminderLabel: String? = null,
    val smdpAddress: String? = null,
    val installedBytes: Long? = null,
    val installedEid: String? = null,
    val providerKey: String? = null,
    val iconBase64: String? = null,
)

@Serializable
private data class EncryptedBackupEnvelope(
    val format: String = EncryptedBackupFormat,
    val version: Int = CurrentEnvelopeVersion,
    val createdAtEpochMillis: Long,
    val kdf: String = BackupKdf,
    val iterations: Int = BackupKdfIterations,
    val saltBase64: String,
    val nonceBase64: String,
    val ciphertextBase64: String,
)

class InvalidBackupPassphraseException(cause: Throwable? = null) :
    GeneralSecurityException("The backup password is incorrect or the backup has been modified", cause)

internal fun decodeBackup(rawBackup: String): HyperLpaBackup {
    val backup = BackupJson.decodeFromString<HyperLpaBackup>(rawBackup)
    require(backup.format == BackupFormat) { "This is not a HyperLPA backup" }
    require(backup.version == CurrentBackupVersion) { "Unsupported HyperLPA backup version" }
    require(backup.profiles.size <= MaxBackedUpProfiles) { "The backup contains too many profiles" }
    require(backup.providerIcons.size <= MaxBackedUpProviderIcons) {
        "The backup contains too many provider icons"
    }
    var encodedIconCharacters = 0L
    backup.profiles.forEach { (iccid, metadata) ->
        require(iccid.length in 10..32 && iccid.all(Char::isDigit)) { "The backup contains an invalid ICCID" }
        require(metadata.tags.size <= 16 && metadata.tags.all { it.length <= 32 }) {
            "The backup contains invalid profile tags"
        }
        require(
            metadata.reminderLabel == null ||
                normalizeReminderLabel(metadata.reminderLabel) == metadata.reminderLabel
        ) { "The backup contains an invalid reminder label" }
        require(metadata.smdpAddress == null || metadata.smdpAddress.length <= 255) {
            "The backup contains an invalid SM-DP+ address"
        }
        require(metadata.installedEid == null ||
            (metadata.installedEid.length == 32 && metadata.installedEid.all(Char::isDigit))) {
            "The backup contains an invalid EID"
        }
        require(metadata.providerKey == null ||
            (metadata.providerKey.length <= 128 &&
                app.hyperlpa.data.metadata.providerIconKey(metadata.providerKey) == metadata.providerKey)) {
            "The backup contains an invalid provider identity"
        }
        encodedIconCharacters += metadata.iconBase64?.length ?: 0
    }
    backup.providerIcons.forEach { (provider, icon) ->
        require(provider.length <= 128) { "The backup contains an invalid provider name" }
        encodedIconCharacters += icon.length
    }
    require(encodedIconCharacters <= MaxAggregateEncodedIconCharacters) {
        "The backup contains too much icon data"
    }
    return backup
}

internal fun encodeBackup(backup: HyperLpaBackup): String = BackupJson.encodeToString(backup)

internal fun encryptBackup(
    backup: HyperLpaBackup,
    passphrase: CharArray,
    random: SecureRandom = SecureRandom(),
): String {
    require(passphrase.size >= MinimumPassphraseLength) {
        "The backup password must be at least $MinimumPassphraseLength characters"
    }
    val plaintext = encodeBackup(backup).toByteArray(StandardCharsets.UTF_8)
    require(plaintext.size <= MaxBackupPlaintextBytes) { "The backup is too large" }
    val salt = ByteArray(BackupSaltBytes).also(random::nextBytes)
    val nonce = ByteArray(BackupNonceBytes).also(random::nextBytes)
    val key = deriveBackupKey(passphrase, salt, BackupKdfIterations)
    try {
        val envelopeTemplate = EncryptedBackupEnvelope(
            createdAtEpochMillis = backup.createdAtEpochMillis,
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            nonceBase64 = Base64.getEncoder().encodeToString(nonce),
            ciphertextBase64 = "",
        )
        val cipher = Cipher.getInstance(BackupCipher)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(BackupTagBits, nonce))
        cipher.updateAAD(envelopeTemplate.aad())
        val ciphertext = cipher.doFinal(plaintext)
        return BackupJson.encodeToString(
            envelopeTemplate.copy(ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext)),
        )
    } finally {
        plaintext.fill(0)
        key.encoded?.fill(0)
    }
}

internal fun decryptBackup(rawBackup: String, passphrase: CharArray): HyperLpaBackup {
    require(rawBackup.length <= MaxEncryptedBackupCharacters) { "The backup is too large" }
    val envelope = BackupJson.decodeFromString<EncryptedBackupEnvelope>(rawBackup)
    require(envelope.format == EncryptedBackupFormat) { "This is not an encrypted HyperLPA backup" }
    require(envelope.version == CurrentEnvelopeVersion) { "Unsupported encrypted backup version" }
    require(envelope.kdf == BackupKdf) { "Unsupported backup key derivation" }
    require(envelope.iterations in MinimumAcceptedKdfIterations..MaximumAcceptedKdfIterations) {
        "Unsupported backup key-derivation cost"
    }
    val salt = decodeBase64Bounded(envelope.saltBase64, BackupSaltBytes, "salt")
    val nonce = decodeBase64Bounded(envelope.nonceBase64, BackupNonceBytes, "nonce")
    require(envelope.ciphertextBase64.length <= MaxCiphertextBase64Characters) { "The backup is too large" }
    val ciphertext = try {
        Base64.getDecoder().decode(envelope.ciphertextBase64)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("The backup ciphertext is not valid Base64", error)
    }
    require(ciphertext.size <= MaxBackupPlaintextBytes + BackupTagBits / 8) { "The backup is too large" }
    val key = deriveBackupKey(passphrase, salt, envelope.iterations)
    val plaintext = try {
        val cipher = Cipher.getInstance(BackupCipher)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BackupTagBits, nonce))
        cipher.updateAAD(envelope.aad())
        cipher.doFinal(ciphertext)
    } catch (error: AEADBadTagException) {
        throw InvalidBackupPassphraseException(error)
    } finally {
        ciphertext.fill(0)
        key.encoded?.fill(0)
    }
    return try {
        decodeBackup(String(plaintext, StandardCharsets.UTF_8))
    } finally {
        plaintext.fill(0)
    }
}

internal fun isEncryptedBackup(rawBackup: String): Boolean = runCatching {
    BackupJson.decodeFromString<EncryptedBackupEnvelope>(rawBackup).format == EncryptedBackupFormat
}.getOrDefault(false)

private fun deriveBackupKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
    val spec = PBEKeySpec(passphrase, salt, iterations, BackupKeyBits)
    return try {
        SecretKeySpec(SecretKeyFactory.getInstance(BackupKdf).generateSecret(spec).encoded, "AES")
    } finally {
        spec.clearPassword()
    }
}

private fun EncryptedBackupEnvelope.aad(): ByteArray =
    "$format:$version:$createdAtEpochMillis:$kdf:$iterations".toByteArray(StandardCharsets.UTF_8)

private fun decodeBase64Bounded(value: String, expectedBytes: Int, field: String): ByteArray {
    require(value.length <= ((expectedBytes * 4 / 3) + 8)) { "The backup $field is too large" }
    val decoded = try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("The backup $field is not valid Base64", error)
    }
    require(decoded.size == expectedBytes) { "The backup $field has an invalid length" }
    return decoded
}

private fun AppSettings.safeForRestore(): AppSettings = copy(
    // Imported configuration must never immediately contact arbitrary endpoints or
    // attached readers, schedule work, or enable sensitive diagnostics without a
    // separate user action after the import has been reviewed.
    autoLoadProfiles = false,
    enableNBridge = false,
    enableOmapi = false,
    enableTelephony = false,
    enableUsbCcid = false,
    enableBle = false,
    enableRemote = false,
    autoLoadRemoteReaders = false,
    notificationAutoSend = false,
    notificationAutoRemove = false,
    scheduledReminders = false,
    loadOperatorIcons = false,
    estimateProfileSize = false,
    apduLogging = false,
    developerMode = false,
    lastReaderId = null,
)

private const val BackupFormat = "hyperlpa-backup"
private const val CurrentBackupVersion = 1
private const val RestoreJournalFormat = "hyperlpa-restore-recovery"
private const val CurrentRestoreJournalVersion = 1
private const val EncryptedBackupFormat = "hyperlpa-encrypted-backup"
private const val CurrentEnvelopeVersion = 1
private const val MaxBackedUpProfiles = 128
private const val MaxBackedUpProviderIcons = 512
private const val MaxBackupPlaintextBytes = 32 * 1024 * 1024
private const val MaxAggregateEncodedIconCharacters = 28L * 1024 * 1024
private const val MaxEncryptedBackupCharacters = 48 * 1024 * 1024
private const val MaxCiphertextBase64Characters = 44 * 1024 * 1024
private const val MinimumPassphraseLength = 10
private const val BackupKdf = "PBKDF2WithHmacSHA256"
private const val BackupKdfIterations = 310_000
private const val MinimumAcceptedKdfIterations = 210_000
private const val MaximumAcceptedKdfIterations = 1_000_000
private const val BackupKeyBits = 256
private const val BackupSaltBytes = 16
private const val BackupNonceBytes = 12
private const val BackupTagBits = 128
private const val BackupCipher = "AES/GCM/NoPadding"
private val BackupJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = true
}
