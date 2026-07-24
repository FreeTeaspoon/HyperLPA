package app.hyperlpa.reminders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileReminderWorkerTest {
    @Test
    fun deliveryRequiresMatchingPersistedReminderAndEnabledNotifications() {
        assertTrue(
            shouldDeliverProfileReminder(
                ReminderDeliveryState(
                    scheduledRemindersEnabled = true,
                    storedReminderEpochMillis = 123L,
                    expectedReminderEpochMillis = 123L,
                    storedReminderLabel = "Travel plan",
                    expectedReminderLabel = "Travel plan",
                    notificationsAvailable = true,
                ),
            ),
        )
    }

    @Test
    fun removedOrReplacedReminderIsSkipped() {
        assertFalse(deliveryState(stored = null).let(::shouldDeliverProfileReminder))
        assertFalse(deliveryState(stored = 456L).let(::shouldDeliverProfileReminder))
        assertFalse(
            deliveryState(stored = 123L, expected = null)
                .let(::shouldDeliverProfileReminder),
        )
        assertFalse(
            deliveryState(stored = 123L, storedLabel = "Replacement plan")
                .let(::shouldDeliverProfileReminder),
        )
    }

    @Test
    fun disabledSettingOrNotificationChannelIsSkippedWithoutDelivery() {
        assertFalse(
            deliveryState(stored = 123L, scheduledRemindersEnabled = false)
                .let(::shouldDeliverProfileReminder),
        )
        assertFalse(
            deliveryState(stored = 123L, notificationsAvailable = false)
                .let(::shouldDeliverProfileReminder),
        )
    }

    @Test
    fun workIdentityTagIsStableAndDoesNotExposeIccid() {
        val iccid = "8901000000000000001"

        val first = reminderWorkIdentityTag(iccid)

        assertEquals(first, reminderWorkIdentityTag(iccid))
        assertFalse(first.contains(iccid))
        assertTrue(first.startsWith("profile-reminder-id-"))
    }

    private fun deliveryState(
        stored: Long?,
        expected: Long? = 123L,
        storedLabel: String? = "Travel plan",
        expectedLabel: String? = "Travel plan",
        scheduledRemindersEnabled: Boolean = true,
        notificationsAvailable: Boolean = true,
    ) = ReminderDeliveryState(
        scheduledRemindersEnabled = scheduledRemindersEnabled,
        storedReminderEpochMillis = stored,
        expectedReminderEpochMillis = expected,
        storedReminderLabel = storedLabel,
        expectedReminderLabel = expectedLabel,
        notificationsAvailable = notificationsAvailable,
    )
}
