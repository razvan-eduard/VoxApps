package com.voxapps.calendarapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.data.CalendarLayer
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Shared hour-of-day grid mechanics used by both [WeekView] and [DayView] — one column per day, a
 *  shared hour axis on the left. Kept local to `:vox-calendar`: `core:calendar` only offers a
 *  month-paged agenda list, and Notes/Expenses have no use for an hour grid. */
internal val HOUR_HEIGHT: Dp = 64.dp
internal val HOUR_LABEL_WIDTH: Dp = 52.dp
private const val HOURS_IN_DAY = 24

@Composable
internal fun HourAxisLabels(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        repeat(HOURS_IN_DAY) { hour ->
            Box(modifier = Modifier.fillMaxWidth().height(HOUR_HEIGHT)) {
                Text(
                    text = String.format(Locale.US, "%02d:00", hour),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp, end = 8.dp).align(Alignment.TopEnd)
                )
            }
        }
    }
}

@Composable
internal fun DayColumn(
    date: LocalDate,
    items: List<EntryCalendarItem>,
    layerById: Map<Long, CalendarLayer>,
    onItemClick: (EntryCalendarItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val zoneId = ZoneId.systemDefault()
    // Separate timed events for the grid (industry standard: all-day handled in pinned header)
    val timedItems = items.filter { !it.entryWithTags.entry.allDay }

    Box(modifier = modifier.height(HOUR_HEIGHT * HOURS_IN_DAY)) {
        Column(Modifier.fillMaxWidth()) {
            repeat(HOURS_IN_DAY) { hour ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HOUR_HEIGHT)
                        .background(
                            if (hour % 2 == 0) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                // Subtle alternating color for depth
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            }
                        )
                )
            }
        }
        timedItems.forEach { item ->
            val start = Instant.ofEpochMilli(item.occurrenceStartMillis).atZone(zoneId)
            if (start.toLocalDate() != date) return@forEach
            val startHour = start.hour + start.minute / 60f
            val durationHours = item.occurrenceEndMillis
                ?.let { (it - item.occurrenceStartMillis) / 3_600_000f }
                ?.takeIf { it > 0f }
                ?: 1f
            val top = HOUR_HEIGHT * startHour
            val entry = item.entryWithTags.entry
            val color = layerById[entry.layerId]?.let { Color(it.colorArgb.toInt()) } ?: MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .padding(top = top, start = 2.dp, end = 2.dp)
                    .height((HOUR_HEIGHT.value * durationHours).coerceAtLeast(24f).dp)
                    .fillMaxWidth()
                    .background(color.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                    .clickable { onItemClick(item) }
                    .padding(4.dp)
            ) {
                Text(
                    text = entry.title,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (date == LocalDate.now()) {
            val currentTimeFraction by rememberCurrentTimeFraction()
            val dotSize = 8.dp
            val nowColor = MaterialTheme.colorScheme.error
            Row(
                modifier = Modifier
                    .padding(top = (HOUR_HEIGHT * currentTimeFraction) - dotSize / 2)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(dotSize).background(nowColor, CircleShape))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(nowColor)
                )
                // Matches the "now" time label already shown in the to-do timeline (NowSplitter)
                // and the home-screen widget's Today section (NowSplitterRow), so the current-time
                // marker reads the same way across the app.
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

/** Ticks once a minute so the current-time line drifts down the grid in real time without a full
 *  screen refresh. Only meaningful for today's column — callers gate rendering on that. */
@Composable
private fun rememberCurrentTimeFraction(): State<Float> =
    produceState(initialValue = currentHourFraction()) {
        while (true) {
            value = currentHourFraction()
            delay(60_000L)
        }
    }

private fun currentHourFraction(): Float {
    val now = LocalTime.now()
    return now.hour + now.minute / 60f
}
