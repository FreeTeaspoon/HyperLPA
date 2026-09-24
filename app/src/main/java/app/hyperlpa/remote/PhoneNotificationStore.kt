package app.hyperlpa.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class PhoneNotificationEntry(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val notificationKey: String = "",
    val appLabel: String = "",
)

@Serializable
private data class PhoneNotificationDocument(val entries: List<PhoneNotificationEntry> = emptyList())

internal class PhoneNotificationStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "phone-notifications.v1"))
    private val alias = "${context.packageName}.phone-notifications.v1"
    private val aad = "HyperLPA phone notification history v1".toByteArray()

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
    fun list(): List<PhoneNotificationEntry> {
        if (!file.baseFile.exists() && !File("${file.baseFile.path}.bak").exists()) return emptyList()
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MaxBytes) { "Invalid phone notification history" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size in 29..MaxBytes) { "Invalid phone notification history" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        val stored = DeviceJson.decodeFromString(
            PhoneNotificationDocument.serializer(), cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString(),
        ).entries
        val current = stored.filter {
            it.timestamp > System.currentTimeMillis() - RetentionMillis &&
                (it.title.isNotBlank() || it.text.isNotBlank())
        }.takeLast(MaxEntries)
        if (stored.size != current.size) write(current)
        return current
    }

    @Synchronized
    fun record(packageName: String, notificationKey: String, title: String, text: String, timestamp: Long, appLabel: String = "") {
        if (title.isBlank() && text.isBlank()) return
        val entries = list().toMutableList()
        val existing = entries.indexOfFirst { it.notificationKey == notificationKey }
        val id = if (existing >= 0) entries.removeAt(existing).id else UUID.randomUUID().toString()
        entries += PhoneNotificationEntry(id, packageName.take(255), title.take(256), text.take(2048),
            timestamp, notificationKey.take(512), appLabel.take(80))
        write(entries.takeLast(MaxEntries))
    }

    @Synchronized
    fun delete(id: String) { write(list().filterNot { it.id == id }) }

    @Synchronized
    fun clear() { write(emptyList()) }

    private fun write(entries: List<PhoneNotificationEntry>) {
        val plaintext = DeviceJson.encodeToString(PhoneNotificationDocument.serializer(), PhoneNotificationDocument(entries)).encodeToByteArray()
        require(plaintext.size <= MaxBytes - 28) { "Phone notification history is too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val output = file.startWrite()
        try { output.write(cipher.iv + cipher.doFinal(plaintext)); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    private companion object {
        const val MaxEntries = 150
        const val MaxBytes = 1024 * 1024
        const val RetentionMillis = 7 * 24 * 60 * 60 * 1000L
    }
}
