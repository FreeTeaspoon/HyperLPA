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
}
