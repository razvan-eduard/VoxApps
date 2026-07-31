package com.voxapps.calendarapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DayView(
    items: List<EntryCalendarItem>,
    layers: List<CalendarLayer>,
    selectedDateMillis: Long,
    locale: Locale,
    onItemClick: (EntryCalendarItem) -> Unit,
    onOpenDaySummary: (Long) -> Unit,
    todayEffect: TodayEffect = TodayEffect.NONE,
    todayEffectStyle: TodayEffectStyle = TodayEffectStyle.RING,
    todayEffectPrimaryColor: Color = Color(0xFFFF6D00),
    todayEffectSecondaryColor: Color? = null,
    todayEffectSpeed: Float = 1f
) {
    val zoneId = ZoneId.systemDefault()
    val date = remember(selectedDateMillis) { Instant.ofEpochMilli(selectedDateMillis).atZone(zoneId).toLocalDate() }
    val layerById = remember(layers) { layers.associateBy { it.id } }
    
    // Filter items belonging to the selected date
    val dayItems = remember(items, date) {
        items.filter { Instant.ofEpochMilli(it.occurrenceStartMillis).atZone(zoneId).toLocalDate() == date }
    }

    // Industry standard: Separate All-Day context from the timed grid
    val (allDayEvents, timedEvents) = remember(dayItems) {
        dayItems.partition { it.entryWithTags.entry.allDay }
    }

    val today = LocalDate.now()
    val languageManager = LocalLanguageManager.current

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Pinned Date Header
        ApplyTodayEffect(
            enabled = date == today,
            elementName = "day_header_$date",
            effect = todayEffect,
            style = todayEffectStyle,
            primaryColor = todayEffectPrimaryColor,
            secondaryColor = todayEffectSecondaryColor,
            speedMultiplier = todayEffectSpeed
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                )
                IconButton(onClick = { onOpenDaySummary(selectedDateMillis) }) {
                    Icon(Icons.Filled.Summarize, contentDescription = languageManager.getString("day_summary_title"))
                }
            }
        }

        // 2. Pinned All-Day Pane (Only visible if items exist)
        if (allDayEvents.isNotEmpty()) {
            AllDayEventsPane(
                events = allDayEvents,
                layerById = layerById,
                onItemClick = onItemClick
            )
            HorizontalDivider(thickness = 0.5.dp)
        }

        HorizontalDivider()

        // 3. Scrollable Timed Grid
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            HourAxisLabels(modifier = Modifier.width(HOUR_LABEL_WIDTH))
            DayColumn(
                date = date,
                items = timedEvents, // Grid only shows timed events
                layerById = layerById,
                onItemClick = onItemClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
