package app.hyperlpa.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
internal data class PendingDeviceRequest(
    val peer: String,
    val id: String,
    val readerId: String,
    val eid: String,
    val started: Long,
)

@Serializable
internal data class StoredDevices(
    val relay: String = "",
    val id: String = newDeviceId(),
    val token: String = DeviceCrypto.secret(),
    val name: String = "",
    val enabled: Boolean = false,
    val peers: List<PairedDevice> = emptyList(),
    val invitation: PairInvitation? = null,
    val operations: List<DeviceOperationRecord> = emptyList(),
    val pending: List<PendingDeviceRequest> = emptyList(),
    val refreshRequired: Set<String> = emptySet(),
    val sharePhoneNotifications: Boolean = false,
    val notificationApps: Set<String> = emptySet(),
    val allowedNotificationApps: Set<String> = emptySet(),
    val notificationPeers: Set<String> = emptySet(),
)

/** One atomic, Keystore-encrypted journal. Pair secrets never enter app settings or backups. */
internal class DeviceStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "remote-devices.v1"))
    private val alias = "${context.packageName}.remote-devices.v1"
    private val aad = "HyperLPA remote device journal v1".toByteArray(Charsets.UTF_8)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    @Synchronized
    fun read(): StoredDevices {
        if (!file.baseFile.exists()) return StoredDevices()
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MaxJournalBytes) { "Device journal is invalid" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().also { require(it.size >= 29) }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        return DeviceJson.decodeFromString(StoredDevices.serializer(), String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }

    @Synchronized
    fun write(value: StoredDevices) {
        val plaintext = DeviceJson.encodeToString(StoredDevices.serializer(), value).toByteArray(Charsets.UTF_8)
        require(plaintext.size <= MaxJournalBytes - 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val bytes = cipher.iv + cipher.doFinal(plaintext)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    companion object { private const val MaxJournalBytes = 8 * 1024 * 1024 }
}
