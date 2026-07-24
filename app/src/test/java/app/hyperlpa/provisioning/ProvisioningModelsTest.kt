package app.hyperlpa.provisioning

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProvisioningModelsTest {
    @Test
    fun `persisted queue retains its reader and euicc affinity`() {
        val queue = StoredBatchQueue(
            readerId = "usb:42:0",
            eid = "89049032000000000000000000000001",
            entries = listOf(
                StoredBatchEntry(index = 0, smdpAddress = "rsp.example"),
            ),
            updatedAtEpochMillis = 1L,
        )

        val restored = Json.decodeFromString<StoredBatchQueue>(Json.encodeToString(queue))

        assertEquals(queue.readerId, restored.readerId)
        assertEquals(queue.eid, restored.eid)
        assertEquals(BatchDownloadStatus.WAITING, restored.entries.single().status)
    }

    @Test
    fun `batch confirmation syntax supplies separately entered code`() {
        val request = parseBatchDownloadLine(
            "LPA:1\$rsp.example\$matching-id\$1.2.3.4\$1 | 246810",
            defaultImei = "490154203237518",
        )

        assertEquals("rsp.example", request.smdpAddress)
        assertEquals("matching-id", request.matchingId)
        assertEquals("1.2.3.4", request.smdpOid)
        assertEquals("246810", request.confirmationCode)
        assertEquals("490154203237518", request.imei)
    }

    @Test
    fun `confirmation-required line without separator value is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseBatchDownloadLine("LPA:1\$rsp.example\$matching-id\$\$1")
        }
    }

    @Test
    fun `ordinary activation code remains unchanged`() {
        val request = parseBatchDownloadLine("LPA:1\$rsp.example\$matching-id")

        assertEquals("matching-id", request.matchingId)
        assertNull(request.confirmationCode)
    }

    @Test
    fun `multiple confirmation separators are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseBatchDownloadLine("LPA:1\$rsp.example\$matching-id | first | second")
        }
    }

    @Test
    fun `interrupted recovery changes only in-flight items`() {
        val items = listOf(
            BatchDownloadItem(0, "one.example", BatchDownloadStatus.SUCCEEDED),
            BatchDownloadItem(1, "two.example", BatchDownloadStatus.DOWNLOADING),
            BatchDownloadItem(2, "three.example", BatchDownloadStatus.WAITING),
        )

        val recovered = interruptInFlightItems(items)

        assertEquals(BatchDownloadStatus.SUCCEEDED, recovered[0].status)
        assertEquals(BatchDownloadStatus.INTERRUPTED, recovered[1].status)
        assertEquals(BatchDownloadStatus.WAITING, recovered[2].status)
    }

    @Test
    fun `unverified interrupted item is not resumable or retryable`() {
        val state = BatchDownloadUiState(
            loading = false,
            items = listOf(
                BatchDownloadItem(
                    index = 0,
                    address = "unknown.example",
                    status = BatchDownloadStatus.INTERRUPTED,
                    error = BatchDownloadError.OUTCOME_UNVERIFIED,
                ),
                BatchDownloadItem(
                    index = 1,
                    address = "pending.example",
                    status = BatchDownloadStatus.WAITING,
                ),
            ),
        )

        assertEquals(1, state.resumableCount)
        assertEquals(0, state.retryableCount)
    }
}
