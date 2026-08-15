package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

@Composable
fun MiuixDatePickerDialog(
    show: Boolean,
    initialDate: LocalDate,
    minimumDate: LocalDate,
    maximumDate: LocalDate,
    title: String,
    cancelText: String,
    confirmText: String,
    onDismissRequest: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
) {
    require(!maximumDate.isBefore(minimumDate))
    val initial = initialDate.coerceIn(minimumDate, maximumDate)
    var year by rememberSaveable { mutableIntStateOf(initial.year) }
    var month by rememberSaveable { mutableIntStateOf(initial.monthValue) }
    var day by rememberSaveable { mutableIntStateOf(initial.dayOfMonth) }

    LaunchedEffect(show, initialDate, minimumDate, maximumDate) {
        if (show) {
            val reset = initialDate.coerceIn(minimumDate, maximumDate)
            year = reset.year
            month = reset.monthValue
            day = reset.dayOfMonth
        }
    }

    val monthRange = datePickerMonthRange(year, minimumDate, maximumDate)
    val actualMonth = month.coerceIn(monthRange)
    val dayRange = datePickerDayRange(year, actualMonth, minimumDate, maximumDate)
    val actualDay = day.coerceIn(dayRange)
    val selectedDate = LocalDate.of(year, actualMonth, actualDay)
    val locale = LocalLocale.current.platformLocale

    WindowDialog(
        show = show,
        title = title,
        summary = selectedDate.format(
            DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu", locale),
        ),
        onDismissRequest = onDismissRequest,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NumberPicker(
                value = actualMonth,
                onValueChange = { newMonth ->
                    month = newMonth
                    day = day.coerceIn(
                        datePickerDayRange(year, newMonth, minimumDate, maximumDate),
                    )
                },
                range = monthRange,
                label = {
                    Month.of(it).getDisplayName(TextStyle.SHORT, locale)
                },
                visibleItemCount = 3,
                wrapAround = monthRange.first == 1 && monthRange.last == 12,
                modifier = Modifier.weight(1f),
            )
            NumberPicker(
                value = actualDay,
                onValueChange = { day = it },
                range = dayRange,
                visibleItemCount = 3,
                wrapAround = dayRange.first == 1 &&
                    dayRange.last == YearMonth.of(year, actualMonth).lengthOfMonth(),
                modifier = Modifier.weight(1f),
            )
            NumberPicker(
                value = year,
                onValueChange = { newYear ->
                    year = newYear
                    val newMonthRange = datePickerMonthRange(
                        newYear,
                        minimumDate,
                        maximumDate,
                    )
                    month = month.coerceIn(newMonthRange)
                    day = day.coerceIn(
                        datePickerDayRange(
                            newYear,
                            month,
                            minimumDate,
                            maximumDate,
                        ),
                    )
                },
                range = minimumDate.year..maximumDate.year,
                visibleItemCount = 3,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = cancelText,
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    onDateSelected(selectedDate)
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(confirmText)
            }
        }
    }
}

internal fun datePickerMonthRange(
    year: Int,
    minimumDate: LocalDate,
    maximumDate: LocalDate,
): IntRange {
    val first = if (year == minimumDate.year) minimumDate.monthValue else 1
    val last = if (year == maximumDate.year) maximumDate.monthValue else 12
    return first..last
}

internal fun datePickerDayRange(
    year: Int,
    month: Int,
    minimumDate: LocalDate,
    maximumDate: LocalDate,
): IntRange {
    val first = if (year == minimumDate.year && month == minimumDate.monthValue) {
        minimumDate.dayOfMonth
    } else {
        1
    }
    val last = if (year == maximumDate.year && month == maximumDate.monthValue) {
        maximumDate.dayOfMonth
    } else {
        YearMonth.of(year, month).lengthOfMonth()
    }
    return first..last
}

private fun LocalDate.coerceIn(minimumDate: LocalDate, maximumDate: LocalDate): LocalDate = when {
    isBefore(minimumDate) -> minimumDate
    isAfter(maximumDate) -> maximumDate
    else -> this
}
