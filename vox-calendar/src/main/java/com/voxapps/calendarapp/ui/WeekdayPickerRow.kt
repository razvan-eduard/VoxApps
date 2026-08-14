package com.voxapps.calendarapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.data.WeekdayMask
import java.time.DayOfWeek

/**
 * Seven tappable day circles, Monday-first, backed by a [WeekdayMask]-encoded `Int` — the one
 * weekday chooser for both a WEEKLY recurrence's custom days and a routine to-do list's active
 * days. [labels] resolves each day's 2-letter chip text (a translation key lookup at both call
 * sites, keeping day names in the app's language rather than the system's). Plain clickable boxes
 * rather than `Surface(onClick)` — Material's 48dp minimum touch target would widen seven chips
 * past a card-width row.
 */
@Composable
fun WeekdayPickerRow(
    mask: Int,
    onToggle: (DayOfWeek) -> Unit,
    labels: (DayOfWeek) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        DayOfWeek.entries.forEach { day ->
            val selected = WeekdayMask.contains(mask, day)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .clickable(enabled = enabled) { onToggle(day) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    labels(day),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

/** The translation key for [day]'s chip label — shared so every caller shows identical chips. */
fun weekdayLabelKey(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "weekday_mon"
    DayOfWeek.TUESDAY -> "weekday_tue"
    DayOfWeek.WEDNESDAY -> "weekday_wed"
    DayOfWeek.THURSDAY -> "weekday_thu"
    DayOfWeek.FRIDAY -> "weekday_fri"
    DayOfWeek.SATURDAY -> "weekday_sat"
    DayOfWeek.SUNDAY -> "weekday_sun"
}
