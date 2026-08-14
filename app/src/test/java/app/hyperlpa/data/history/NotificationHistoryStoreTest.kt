package app.hyperlpa.data.history

import app.hyperlpa.domain.model.NotificationOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationHistoryStoreTest {
    @Test
    fun boundedHistoryKeepsOnlyNewestEntries() {
        val entries = (0 until MaxNotificationHistoryEntries + 25).map { index ->
            NotificationHistoryEntry(
                timestampEpochMillis = index.toLong(),
                action = NotificationHistoryAction.SEND,
                status = NotificationHistoryStatus.SUCCEEDED,
                trigger = NotificationHistoryTrigger.AUTOMATIC,
                notificationOperation = NotificationOperation.INSTALL.name,
            )
        }

        val bounded = boundedHistory(entries)

        assertEquals(MaxNotificationHistoryEntries, bounded.size)
        assertEquals(25L, bounded.first().timestampEpochMillis)
        assertEquals((MaxNotificationHistoryEntries + 24).toLong(), bounded.last().timestampEpochMillis)
    }

    @Test
    fun hostSanitizerDropsCredentialsPathsQueriesAndPorts() {
        assertEquals(
            "notify.example.com",
            sanitizeNotificationHost("https://user:secret@Notify.Example.com:8443/path?token=value"),
        )
        assertEquals("notify.example.com", sanitizeNotificationHost("notify.example.com/path?code=value"))
        assertNull(sanitizeNotificationHost("not a host/notify"))
        assertNull(sanitizeNotificationHost("https://exa_mple.com/notify"))
    }

    @Test
    fun addressSanitizerKeepsOnlySafeOrigin() {
        assertEquals(
            "https://notify.example.com:8443",
            sanitizeNotificationAddress(
                "https://user:secret@Notify.Example.com:8443/path?token=value#fragment",
            ),
        )
        assertEquals(
            "https://notify.example.com",
            sanitizeNotificationAddress("notify.example.com/path?code=value"),
        )
        assertNull(sanitizeNotificationAddress("ftp://notify.example.com/path"))
        assertNull(sanitizeNotificationAddress("not a host/notify"))
    }

    @Test
    fun notificationIccidKeepsFullDigitsOnly() {
        assertEquals(
            "12345678901234567890",
            sanitizeNotificationIccid(" 12345678901234567890 "),
        )
        assertEquals("1234567", sanitizeNotificationIccid("12-34 567"))
        assertNull(sanitizeNotificationIccid(""))
    }

    @Test
    fun eidAndPayloadSanitizersKeepOnlyResendSafeValues() {
        assertEquals(
            "12345678901234567890123456789012",
            sanitizeNotificationEid(" 12345678901234567890123456789012 "),
        )
        assertNull(sanitizeNotificationEid("1234"))
        assertEquals("YWJjZA==", sanitizePendingNotificationPayload(" YWJjZA== "))
        assertNull(sanitizePendingNotificationPayload("not a base64 payload"))
    }
}
