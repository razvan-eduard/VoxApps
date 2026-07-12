package com.voxapps.calendarapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.data.CalendarLayer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun WeekView(
    items: List<EntryCalendarItem>,
    layers: List<CalendarLayer>,
    selectedDateMillis: Long,
    locale: Locale,
    onItemClick: (EntryCalendarItem) -> Unit,
    onDayHeaderClick: (Long) -> Unit
) {
    val zoneId = ZoneId.systemDefault()
    val selectedDate = remember(selectedDateMillis) { Instant.ofEpochMilli(selectedDateMillis).atZone(zoneId).toLocalDate() }
    val weekStart = remember(selectedDate) { selectedDate.with(DayOfWeek.MONDAY) }
    val days = remember(weekStart) { (0..6).map { weekStart.plusDays(it.toLong()) } }
    val layerById = remember(layers) { layers.associateBy { it.id } }
    val today = LocalDate.now()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(HOUR_LABEL_WIDTH))
            days.forEach { day ->
                Column(
                    modifier = Modifier.weight(1f).clickable {
                        onDayHeaderClick(day.atStartOfDay(zoneId).toInstant().toEpochMilli())
                    },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${day.dayOfMonth}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                        color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            HourAxisLabels(modifier = Modifier.width(HOUR_LABEL_WIDTH))
            days.forEach { day ->
                val dayItems = remember(items, day) {
                    items.filter { Instant.ofEpochMilli(it.occurrenceStartMillis).atZone(zoneId).toLocalDate() == day }
                }
                DayColumn(
                    date = day,
                    items = dayItems,
                    layerById = layerById,
                    onItemClick = onItemClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
