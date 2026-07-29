package app.hyperlpa.provisioning

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.hyperlpa.domain.model.DownloadRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class EncryptedProvisioningQueueStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, QueueDirectoryName)
    private val queueFile = File(directory, QueueFileName)
    private val atomicFile = AtomicFile(queueFile)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun load(): StoredQueueLoadResult {
        val atomicArtifactsExist = queueFile.exists() ||
            File("${queueFile.path}.bak").exists() ||
            File("${queueFile.path}.new").exists()
        if (!atomicArtifactsExist) return StoredQueueLoadResult.Empty
        return try {
            val encrypted = atomicFile.openRead().use { input ->
                // openRead() first restores a .bak-only AtomicFile. Size the exact recovered file
                // descriptor rather than the pre-recovery base path.
                val declaredLength = input.channel.size()
                require(declaredLength in MinimumEnvelopeBytes.toLong()..MaxEncryptedQueueBytes.toLong()) {
                    "Invalid encrypted queue size"
                }
                val output = ByteArrayOutputStream(declaredLength.toInt())
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MaxEncryptedQueueBytes) { "Encrypted queue is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val plaintext = try {
                decrypt(encrypted)
            } finally {
                encrypted.fill(0)
            }
            try {
                val queue = json.decodeFromString<StoredBatchQueue>(plaintext.decodeToString())
                    .validated()
                StoredQueueLoadResult.Loaded(queue)
            } finally {
                plaintext.fill(0)
            }
        } catch (_: AEADBadTagException) {
            StoredQueueLoadResult.Unreadable
        } catch (_: Throwable) {
            StoredQueueLoadResult.Unreadable
        }
    }

    fun save(queue: StoredBatchQueue) {
        val validated = queue.validated()
        val plaintext = json.encodeToString(validated).encodeToByteArray()
        require(plaintext.size <= MaxPlaintextQueueBytes) { "Provisioning queue is too large" }
        val encrypted = try {
            encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        require(encrypted.size <= MaxEncryptedQueueBytes) { "Encrypted provisioning queue is too large" }

        check(directory.isDirectory || directory.mkdirs()) {
            "Could not create the private provisioning queue directory"
        }
        check(directory.isDirectory) { "The provisioning queue path is not a directory" }
        val output = atomicFile.startWrite()
        try {
            output.write(encrypted)
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        } finally {
            encrypted.fill(0)
        }
    }

    fun clear() {
        atomicFile.delete()
        KeyStore.getInstance(AndroidKeyStore).apply {
            load(null)
            if (containsAlias(KeyAlias)) deleteEntry(KeyAlias)
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CipherTransformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(EnvelopeMagic)
        val ciphertext = cipher.doFinal(plaintext)
        val nonce = cipher.iv
        check(nonce.size == NonceBytes) { "Unexpected AES-GCM nonce size" }
        return EnvelopeMagic + nonce + ciphertext
    }

    private fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size >= MinimumEnvelopeBytes) { "Invalid encrypted queue" }
        require(envelope.copyOfRange(0, EnvelopeMagic.size).contentEquals(EnvelopeMagic)) {
            "Unknown encrypted queue format"
        }
        val nonceStart = EnvelopeMagic.size
        val nonce = envelope.copyOfRange(nonceStart, nonceStart + NonceBytes)
        val ciphertext = envelope.copyOfRange(nonceStart + NonceBytes, envelope.size)
        val cipher = Cipher.getInstance(CipherTransformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GcmTagBits, nonce))
        cipher.updateAAD(EnvelopeMagic)
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore).run {
            init(
                KeyGenParameterSpec.Builder(
                    KeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val QueueDirectoryName = "provisioning"
        const val QueueFileName = "queue.aesgcm"
        const val KeyAlias = "hyperlpa.provisioning.queue.v1"
        const val AndroidKeyStore = "AndroidKeyStore"
        const val CipherTransformation = "AES/GCM/NoPadding"
        const val NonceBytes = 12
        const val GcmTagBits = 128
        const val MaxPlaintextQueueBytes = 192 * 1024
        const val MaxEncryptedQueueBytes = 256 * 1024
        val EnvelopeMagic = byteArrayOf('H'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), 'Q'.code.toByte(), 1)
        val MinimumEnvelopeBytes = EnvelopeMagic.size + NonceBytes + (GcmTagBits / 8)
    }
}

internal sealed interface StoredQueueLoadResult {
    data object Empty : StoredQueueLoadResult
    data object Unreadable : StoredQueueLoadResult
    data class Loaded(val queue: StoredBatchQueue) : StoredQueueLoadResult
}

@Serializable
internal data class StoredBatchQueue(
    val version: Int = CurrentQueueVersion,
    val readerId: String,
    val eid: String,
    val entries: List<StoredBatchEntry>,
    val updatedAtEpochMillis: Long,
)

@Serializable
internal data class StoredBatchEntry(
    val index: Int,
    val smdpAddress: String,
    val matchingId: String? = null,
    val smdpOid: String? = null,
    val confirmationCodeRequired: Boolean = false,
    val confirmationCode: String? = null,
    val imei: String? = null,
    val status: BatchDownloadStatus = BatchDownloadStatus.WAITING,
    val error: BatchDownloadError? = null,
) {
    fun toRequest(): DownloadRequest = DownloadRequest(
        smdpAddress = smdpAddress,
        matchingId = matchingId,
        smdpOid = smdpOid,
        confirmationCodeRequired = confirmationCodeRequired,
        confirmationCode = confirmationCode,
        imei = imei,
    )
}

internal fun DownloadRequest.toStoredEntry(index: Int): StoredBatchEntry = StoredBatchEntry(
    index = index,
    smdpAddress = smdpAddress,
    matchingId = matchingId,
    smdpOid = smdpOid,
    confirmationCodeRequired = confirmationCodeRequired,
    confirmationCode = confirmationCode,
    imei = imei,
)

private fun StoredBatchQueue.validated(): StoredBatchQueue {
    require(version == CurrentQueueVersion) { "Unsupported provisioning queue version" }
    requireBoundedSecret(readerId, 1_024, "reader ID")
    requireBoundedSecret(eid, 64, "EID")
    require(entries.size in 1..MaxProvisioningQueueItems) { "Invalid provisioning queue size" }
    require(entries.map(StoredBatchEntry::index) == entries.indices.toList()) {
        "Invalid provisioning queue indexes"
    }
    return copy(entries = entries.map { entry ->
        requireBoundedSecret(entry.smdpAddress, 253, "SM-DP+ address")
        entry.matchingId?.let { matchingId ->
            require(matchingId.length <= 1_024 && matchingId.none(Char::isISOControl)) {
                "Invalid matching ID"
            }
        }
        entry.smdpOid?.let { requireBoundedSecret(it, 256, "SM-DP+ OID") }
        entry.confirmationCode?.let { requireBoundedSecret(it, 128, "confirmation code") }
        entry.imei?.let { requireBoundedSecret(it, 32, "IMEI") }
        require(!entry.confirmationCodeRequired || !entry.confirmationCode.isNullOrBlank()) {
            "A required confirmation code is missing"
        }
        entry
    })
}

private fun requireBoundedSecret(value: String, maxLength: Int, fieldName: String) {
    require(value.isNotBlank() && value.length <= maxLength) { "Invalid $fieldName" }
    require(value.none(Char::isISOControl)) { "Invalid $fieldName" }
}

private const val CurrentQueueVersion = 3
