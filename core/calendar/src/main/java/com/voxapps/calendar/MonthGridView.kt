package com.voxapps.calendar

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kizitonwose.calendar.compose.CalendarState
import com.kizitonwose.calendar.compose.HorizontalCalendar
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.OutDateStyle
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun MonthGridView(
    selectedDateMillis: Long,
    locale: Locale,
    onDateSelected: (Long) -> Unit,
    onHeaderClick: () -> Unit,
    modifier: Modifier = Modifier,
    firstDayOfWeek: DayOfWeek = firstDayOfWeekFromLocale(),
    state: CalendarState = rememberCalendarState(
        startMonth = YearMonth.now().minusMonths(120),
        endMonth = YearMonth.now().plusMonths(120),
        firstVisibleMonth = YearMonth.now(),
        firstDayOfWeek = firstDayOfWeek,
        outDateStyle = OutDateStyle.EndOfRow
    ),
    dayDots: Map<LocalDate, List<Long>> = emptyMap()
) {
    val scope = rememberCoroutineScope()
    val selectedDate = remember(selectedDateMillis) { CalendarDateUtils.millisToLocalDate(selectedDateMillis) }

    Column(modifier = modifier) {
        MonthHeaderWrapper(
            calendarMonth = state.firstVisibleMonth.yearMonth,
            locale = locale,
            onPrevMonth = {
                scope.launch {
                    state.animateScrollToMonth(state.firstVisibleMonth.yearMonth.minusMonths(1))
                }
            },
            onNextMonth = {
                scope.launch {
                    state.animateScrollToMonth(state.firstVisibleMonth.yearMonth.plusMonths(1))
                }
            },
            onHeaderClick = onHeaderClick
        )
        DaysOfWeekTitle(firstDayOfWeek = firstDayOfWeek, locale = locale)
        HorizontalCalendar(
            state = state,
            dayContent = { day ->
                DayCell(
                    day = day,
                    isSelected = selectedDate == day.date,
                    onClick = { onDateSelected(CalendarDateUtils.startOfDayMillis(it.date)) },
                    dots = dayDots[day.date] ?: emptyList()
                )
            }
        )
    }
}

@Composable
private fun MonthHeaderWrapper(
    calendarMonth: YearMonth,
    locale: Locale,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onHeaderClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onPrevMonth) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
        }
        
        MonthYearHeader(
            month = calendarMonth,
            locale = locale,
            isExpanded = true,
            onClick = onHeaderClick
        )

        IconButton(onClick = onNextMonth) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
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
                textAlign = TextAlign.Center,
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
            .aspectRatio(1.4f)
            .padding(horizontal = 1.5.dp, vertical = 1.5.dp)
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
            verticalArrangement = Arrangement.Center
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
                fontSize = 12.sp
            )
            if (day.position == DayPosition.MonthDate && dots.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 1.dp)
                ) {
                    dots.take(4).forEach { colorArgb ->
                        Box(
                            modifier = Modifier
                                .size(3.dp)
                                .background(Color(colorArgb.toInt()), CircleShape)
                        )
                    }
                }
            }
        }
    }
}
