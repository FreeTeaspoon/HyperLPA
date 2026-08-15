package app.hyperlpa.reminders

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderDateTest {
    private val zone = ZoneId.of("Australia/Melbourne")

    @Test
    fun instantNormalizesToLocalMidnightWithoutChangingItsDate() {
        val date = LocalDate.of(2026, 8, 15)
        val instant = ZonedDateTime.of(date, LocalTime.of(16, 45), zone).toInstant()

        assertEquals(date, instant.toReminderDate(zone))
        assertEquals(
            ZonedDateTime.of(date, LocalTime.MIDNIGHT, zone).toInstant(),
            instant.normalizeReminderInstant(zone),
        )
    }

    @Test
    fun localDateConvertsToAnInstantAtLocalMidnight() {
        val date = LocalDate.of(2026, 12, 31)

        assertEquals(
            ZonedDateTime.of(date, LocalTime.MIDNIGHT, zone).toInstant(),
            date.toReminderInstant(zone),
        )
    }

    @Test
    fun utcDateInputRemainsStableAcrossTheDateConversion() {
        val instant = Instant.parse("2026-08-15T04:00:00Z")

        assertEquals(LocalDate.of(2026, 8, 15), instant.toReminderDate(ZoneId.of("UTC")))
    }
}
