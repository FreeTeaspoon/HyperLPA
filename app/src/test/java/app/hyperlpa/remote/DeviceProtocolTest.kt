package app.hyperlpa.remote

import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DeviceProtocolTest {
    @Test fun `download preview and progress survive a wire round trip`() {
        val request = DownloadRequest("smdp.example", "matching", confirmationCode = "confirmation")
        val profile = ProfileInfo("123", ProfileState.DISABLED, "Travel", "", "Carrier", "A000", ProfileClass.OPERATIONAL, reminderAt = Instant.ofEpochMilli(1000))
        val message = DeviceMessage("result", requestId = newDeviceId(), snapshot = DeviceSnapshot(lpa = LpaRepositoryState(
            profiles = listOf(profile), operation = LpaOperation.Downloading(DownloadStage.CONFIRMING),
            pendingProfileDownload = ProfileDownloadPreview(profile, request),
        )))
        assertEquals(message, DeviceJson.decodeFromString(DeviceMessage.serializer(), DeviceJson.encodeToString(DeviceMessage.serializer(), message)))
    }

    @Test fun `operation results preserve unverified outcomes without retaining activation data`() {
        val result = DeviceMessage("result", done = true,
            outcome = OperationOutcome.Unverified(OperationFailure("Connection", "Read the card again")))
        val encoded = DeviceJson.encodeToString(DeviceMessage.serializer(), result)
        assertEquals(result, DeviceJson.decodeFromString(DeviceMessage.serializer(), encoded))
        assertFalse(encoded.contains("matchingId"))
    }
}
