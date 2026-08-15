package app.hyperlpa.reminders

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Reminders are date-only. Keep the existing Instant contract for persistence and WorkManager,
 * but always represent a selected date at local midnight.
 */
internal fun LocalDate.toReminderInstant(zoneId: ZoneId = ZoneId.systemDefault()): Instant =
    atStartOfDay(zoneId).toInstant()

internal fun Instant.toReminderDate(zoneId: ZoneId = ZoneId.systemDefault()): LocalDate =
    atZone(zoneId).toLocalDate()

internal fun Instant.normalizeReminderInstant(zoneId: ZoneId = ZoneId.systemDefault()): Instant =
    toReminderDate(zoneId).toReminderInstant(zoneId)

internal fun Instant.formatReminderDate(zoneId: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(zoneId)
        .format(this)
