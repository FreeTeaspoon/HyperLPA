package app.hyperlpa.ui.components

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class MiuixDatePickerDialogTest {
    @Test
    fun pickerRangesRespectBoundsAndLeapYears() {
        val minimum = LocalDate.of(2024, 2, 10)
        val maximum = LocalDate.of(2026, 7, 24)

        assertEquals(2..12, datePickerMonthRange(2024, minimum, maximum))
        assertEquals(1..7, datePickerMonthRange(2026, minimum, maximum))
        assertEquals(10..29, datePickerDayRange(2024, 2, minimum, maximum))
        assertEquals(1..24, datePickerDayRange(2026, 7, minimum, maximum))
    }
}
