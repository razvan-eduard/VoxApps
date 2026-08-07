package com.voxapps.calendarapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.design.effects.ApplyTodayEffect
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale

@Composable
fun WeekView(
    items: List<EntryCalendarItem>,
    layers: List<CalendarLayer>,
    selectedDateMillis: Long,
    locale: Locale,
    onItemClick: (EntryCalendarItem) -> Unit,
    onDayHeaderClick: (Long) -> Unit,
    todayEffect: TodayEffect = TodayEffect.NONE,
    todayEffectStyle: TodayEffectStyle = TodayEffectStyle.RING,
    todayEffectPrimaryColor: Color = Color(0xFFFF6D00),
    todayEffectSecondaryColor: Color? = null,
    todayEffectSpeed: Float = 1f,
    selectedIds: Set<Long> = emptySet(),
    onItemLongClick: (EntryCalendarItem) -> Unit = {}
) {
    val zoneId = ZoneId.systemDefault()
    val languageManager = LocalLanguageManager.current
    val selectedDate = remember(selectedDateMillis) { Instant.ofEpochMilli(selectedDateMillis).atZone(zoneId).toLocalDate() }
    val weekStart = remember(selectedDate) { selectedDate.with(DayOfWeek.MONDAY) }
    val days = remember(weekStart) { (0..6).map { weekStart.plusDays(it.toLong()) } }
    val weekNumber = remember(weekStart) { weekStart.get(WeekFields.ISO.weekOfWeekBasedYear()) }
    val layerById = remember(layers) { layers.associateBy { it.id } }
    val today = LocalDate.now()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = String.format(languageManager.getString("week_number_label"), weekNumber),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(start = HOUR_LABEL_WIDTH, top = 4.dp)
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(HOUR_LABEL_WIDTH))
            days.forEach { day ->
                ApplyTodayEffect(
                    enabled = day == today,
                    elementName = "week_day_header_$day",
                    effect = todayEffect,
                    style = todayEffectStyle,
                    primaryColor = todayEffectPrimaryColor,
                    secondaryColor = todayEffectSecondaryColor,
                    speedMultiplier = todayEffectSpeed,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .let { if (day == today) it.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) else it }
                            .clickable {
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
        }
        // Pinned all-day pane, one column per day — mirrors DayView's "only visible if items
        // exist" convention. Without this, all-day items were silently dropped in Week view:
        // DayColumn discards them from the timed grid and nothing else ever showed them.
        val allDayEventsByDay = remember(items, days) {
            days.associateWith { day ->
                items.filter {
                    it.entryWithTags.entry.allDay &&
                        Instant.ofEpochMilli(it.occurrenceStartMillis).atZone(zoneId).toLocalDate() == day
                }
            }
        }
        if (allDayEventsByDay.values.any { it.isNotEmpty() }) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(HOUR_LABEL_WIDTH))
                days.forEach { day ->
                    // AllDayEventsPane renders nothing at all when its day has no all-day events
                    // (early-returns) — wrapping it in an always-emitted Box keeps every day's
                    // column the same width, so they stay aligned with the hour grid below.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .let { if (day == today) it.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) else it }
                    ) {
                        AllDayEventsPane(
                            events = allDayEventsByDay.getValue(day),
                            layerById = layerById,
                            onItemClick = onItemClick,
                            modifier = Modifier.fillMaxWidth(),
                            selectedIds = selectedIds,
                            onItemLongClick = onItemLongClick,
                            compact = true
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp)
        }

        HorizontalDivider()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                HourAxisLabels(modifier = Modifier.width(HOUR_LABEL_WIDTH))
                days.forEach { day ->
                    val dayItems = remember(items, day) {
                        items.filter { Instant.ofEpochMilli(it.occurrenceStartMillis).atZone(zoneId).toLocalDate() == day }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        DayColumn(
                            date = day,
                            items = dayItems,
                            layerById = layerById,
                            onItemClick = onItemClick,
                            modifier = Modifier.fillMaxWidth(),
                            selectedIds = selectedIds,
                            onItemLongClick = onItemLongClick,
                            showNowLine = false
                        )
                        // A translucent tint drawn ON TOP of the column (not behind it) — DayColumn's
                        // own alternating hour-cell backgrounds are fully opaque, so a tint placed
                        // behind them would be completely hidden.
                        if (day == today) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                            )
                        }
                    }
                }
            }
            // One continuous now-line spanning every day column — replaces the 7 separate
            // per-column copies DayColumn would otherwise draw (each confined to its own ~45dp-wide
            // column, easy to miss); only drawn at all if today falls within this displayed week.
            if (days.contains(today)) {
                val currentTimeFraction by rememberCurrentTimeFraction()
                val dotSize = 8.dp
                val nowColor = MaterialTheme.colorScheme.error
                val nowTop = (HOUR_HEIGHT * currentTimeFraction) - dotSize / 2
                Row(
                    modifier = Modifier.padding(top = nowTop).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.width(HOUR_LABEL_WIDTH))
                    Box(modifier = Modifier.size(dotSize).background(nowColor, CircleShape))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(nowColor)
                    )
                    Text(
                        text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = nowColor,
                        modifier = Modifier.padding(start = 6.dp, end = 4.dp)
                    )
                }
            }
        }
    }
}
