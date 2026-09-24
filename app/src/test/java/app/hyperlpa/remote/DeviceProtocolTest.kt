package app.hyperlpa.remote

import app.hyperlpa.data.LpaRepositoryState
import app.hyperlpa.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DeviceProtocolTest {
    @Test fun `notification sharing defaults to denied when reading an older device journal`() {
        val stored = DeviceJson.decodeFromString(StoredDevices.serializer(), "{}")
        assertFalse(stored.sharePhoneNotifications)
        assertTrue(stored.allowedNotificationApps.isEmpty())
        assertTrue(stored.notificationPeers.isEmpty())
    }

    @Test fun `notification history stays inside encrypted peer payload`() {
        val secret = DeviceCrypto.secret()
        val message = DeviceMessage("phone_notifications", phoneNotificationsAvailable = true,
            phoneNotifications = listOf(PhoneNotificationEntry(newDeviceId(), "org.example.sms", "New message", "Private text", 1_700_000_000_000L)))
        val sender = newDeviceId()
        val recipient = newDeviceId()
        val pair = newDeviceId()
        val serialized = DeviceJson.encodeToString(DeviceMessage.serializer(), message)
        val encrypted = DeviceCrypto.encrypt(secret, sender, recipient, pair, serialized, 1_700_000_000_000L)
        assertFalse(encrypted.body.contains("Private text"))
        val restored = DeviceJson.decodeFromString(DeviceMessage.serializer(),
            DeviceCrypto.decrypt(secret, encrypted, recipient, 1_700_000_000_000L))
        assertEquals(message, restored)
    }

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
