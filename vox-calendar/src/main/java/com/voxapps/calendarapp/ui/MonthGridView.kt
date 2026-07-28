package com.voxapps.calendarapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import com.voxapps.calendar.CalendarDateUtils
import com.voxapps.calendarapp.data.CalendarLayer
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun MonthGridView(
    items: List<EntryCalendarItem>,
    layers: List<CalendarLayer>,
    selectedDateMillis: Long,
    locale: Locale,
    onDateSelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedDate = remember(selectedDateMillis) { CalendarDateUtils.millisToLocalDate(selectedDateMillis) }
    val currentMonth = remember(selectedDate) { YearMonth.from(selectedDate) }
    val startMonth = remember { currentMonth.minusMonths(120) }
    val endMonth = remember { currentMonth.plusMonths(120) }
    val firstDayOfWeek = remember { firstDayOfWeekFromLocale() }

    val state = rememberCalendarState(
        startMonth = startMonth,
        endMonth = endMonth,
        firstVisibleMonth = currentMonth,
        firstDayOfWeek = firstDayOfWeek
    )

    // Sync state when selectedDate changes externally
    LaunchedEffect(selectedDate) {
        state.scrollToMonth(YearMonth.from(selectedDate))
    }

    val layerById = remember(layers) { layers.associateBy { it.id } }
    val itemsByDay = remember(items) {
        items.groupBy { CalendarDateUtils.millisToLocalDate(it.occurrenceStartMillis) }
    }

    Column(modifier = modifier) {
        DaysOfWeekTitle(firstDayOfWeek = firstDayOfWeek, locale = locale)
        HorizontalCalendar(
            state = state,
            dayContent = { day ->
                DayCell(
                    day = day,
                    isSelected = selectedDate == day.date,
                    onClick = { onDateSelected(CalendarDateUtils.startOfDayMillis(it.date)) },
                    dots = itemsByDay[day.date]?.mapNotNull { 
                        layerById[it.entryWithTags.entry.layerId]?.colorArgb 
                    }?.distinct() ?: emptyList()
                )
            }
        )
    }
}

@Composable
private fun DaysOfWeekTitle(firstDayOfWeek: java.time.DayOfWeek, locale: Locale) {
    val daysOfWeek = remember(firstDayOfWeek) {
        val days = java.time.DayOfWeek.entries.toMutableList()
        val index = days.indexOf(firstDayOfWeek)
        days.subList(index, days.size) + days.subList(0, index)
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        for (dayOfWeek in daysOfWeek) {
            Text(
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                text = dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DayCell(
    day: CalendarDay,
    isSelected: Boolean,
    onClick: (CalendarDay) -> Unit,
    dots: List<Long>
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .clickable(
                enabled = day.position == DayPosition.MonthDate,
                onClick = { onClick(day) }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (day.position == DayPosition.MonthDate) {
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
                fontSize = 14.sp
            )
            if (day.position == DayPosition.MonthDate && dots.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dots.take(4).forEach { colorArgb ->
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(Color(colorArgb.toInt()), CircleShape)
                        )
                    }
                }
            }
        }
    }
}
