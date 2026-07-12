package com.voxapps.calendarapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxapps.calendar.CalendarDateUtils
import com.voxapps.calendarapp.data.CalendarLayer
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun YearView(
    items: List<EntryCalendarItem>,
    layers: List<CalendarLayer>,
    selectedDateMillis: Long,
    locale: Locale,
    onDayClick: (Long) -> Unit
) {
    val zoneId = ZoneId.systemDefault()
    val year = remember(selectedDateMillis) { Instant.ofEpochMilli(selectedDateMillis).atZone(zoneId).toLocalDate().year }
    val layerById = remember(layers) { layers.associateBy { it.id } }
    val itemsByDay = remember(items) {
        items.groupBy { Instant.ofEpochMilli(it.occurrenceStartMillis).atZone(zoneId).toLocalDate() }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(12) { index ->
            MiniMonth(
                month = YearMonth.of(year, index + 1),
                itemsByDay = itemsByDay,
                layerById = layerById,
                locale = locale,
                onDayClick = onDayClick
            )
        }
    }
}

@Composable
private fun MiniMonth(
    month: YearMonth,
    itemsByDay: Map<LocalDate, List<EntryCalendarItem>>,
    layerById: Map<Long, CalendarLayer>,
    locale: Locale,
    onDayClick: (Long) -> Unit
) {
    val zoneId = ZoneId.systemDefault()
    val today = LocalDate.now()
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "${month.month.getDisplayName(TextStyle.FULL, locale)} ${month.year}",
            style = MaterialTheme.typography.labelLarge
        )
        val leadingBlanks = month.atDay(1).dayOfWeek.value - 1
        val cells: List<LocalDate?> = List(leadingBlanks) { null } + CalendarDateUtils.daysInMonth(month)
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clickable(enabled = day != null) {
                                day?.let { onDayClick(it.atStartOfDay(zoneId).toInstant().toEpochMilli()) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (day != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${day.dayOfMonth}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                                    color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                val dayItems = itemsByDay[day].orEmpty()
                                if (dayItems.isNotEmpty()) {
                                    val color = layerById[dayItems.first().entryWithTags.entry.layerId]
                                        ?.let { Color(it.colorArgb.toInt()) }
                                        ?: MaterialTheme.colorScheme.primary
                                    Box(Modifier.size(4.dp).background(color, CircleShape))
                                }
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
