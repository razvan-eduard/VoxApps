package com.voxapps.calendar

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Fixed, non-scrolling month/year label shown once above the day-list (e.g. "July 2026") — replaces
 * repeating month/year on every day row (see [DayHeader]). Updates as [CalendarView] pages between
 * months. [locale] defaults to the device locale but callers should pass the app's in-app language
 * setting (see [CalendarView]'s `locale` param).
 */
@Composable
internal fun MonthYearHeader(month: YearMonth, modifier: Modifier = Modifier, locale: Locale = Locale.getDefault()) {
    Text(
        text = "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}",
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(vertical = 12.dp)
    )
}
