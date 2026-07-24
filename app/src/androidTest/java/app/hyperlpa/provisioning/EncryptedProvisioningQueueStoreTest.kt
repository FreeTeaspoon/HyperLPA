package app.hyperlpa.provisioning

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedProvisioningQueueStoreTest {
    @Test
    fun queueRoundTripsWithoutWritingSecretsInPlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = EncryptedProvisioningQueueStore(context)
        val matchingId = "matching-secret-77f143"
        val confirmationCode = "confirmation-secret-39b821"
        val queue = StoredBatchQueue(
            readerId = "usb:42:0",
            eid = "89049032000000000000000000000001",
            entries = listOf(
                StoredBatchEntry(
                    index = 0,
                    smdpAddress = "rsp.example",
                    matchingId = matchingId,
                    smdpOid = "1.2.3.4",
                    confirmationCodeRequired = true,
                    confirmationCode = confirmationCode,
                    status = BatchDownloadStatus.FAILED,
                    error = BatchDownloadError.DOWNLOAD_FAILED,
                ),
            ),
            updatedAtEpochMillis = 1L,
        )

        try {
            store.clear()
            store.save(queue)

            val encryptedFile = File(context.noBackupFilesDir, "provisioning/queue.aesgcm")
            assertTrue(encryptedFile.isFile)
            val diskText = encryptedFile.readBytes().toString(StandardCharsets.ISO_8859_1)
            assertFalse(diskText.contains(matchingId))
            assertFalse(diskText.contains(confirmationCode))
            assertFalse(diskText.contains(queue.readerId))
            assertFalse(diskText.contains(queue.eid))

            val loaded = store.load() as StoredQueueLoadResult.Loaded
            assertEquals(queue.readerId, loaded.queue.readerId)
            assertEquals(queue.eid, loaded.queue.eid)
            assertEquals(matchingId, loaded.queue.entries.single().matchingId)
            assertEquals(confirmationCode, loaded.queue.entries.single().confirmationCode)
            assertEquals(BatchDownloadError.DOWNLOAD_FAILED, loaded.queue.entries.single().error)
        } finally {
            store.clear()
        }
    }
}
