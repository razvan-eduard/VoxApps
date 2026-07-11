package com.voxapps.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Day number + day-of-week, so scroll position inside a month is never ambiguous — month/year is
 * shown once, fixed, at the top of [CalendarView] rather than repeated on every row (see
 * `MonthYearHeader`). The date block sits centered in the row, with a divider line filling the space
 * on either side out to the row's edges.
 *
 * [locale] drives the day-of-week name via `java.time`'s formatting — defaults to the device locale,
 * but callers should pass the app's own in-app language setting (see [CalendarView]'s `locale`
 * param) so the calendar's weekday names follow the language the user picked in Settings rather than
 * the phone's system locale, which can differ.
 */
@Composable
fun DayHeader(date: LocalDate, modifier: Modifier = Modifier, isEmpty: Boolean = false, locale: Locale = Locale.getDefault()) {
    val alpha = if (isEmpty) 0.55f else 1f

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                textAlign = TextAlign.Center
            )
            Text(
                text = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                textAlign = TextAlign.Center
            )
        }
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
