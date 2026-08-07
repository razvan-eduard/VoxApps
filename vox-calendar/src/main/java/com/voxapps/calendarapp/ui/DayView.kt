package com.voxapps.calendarapp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.design.effects.ApplyTodayEffect
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Minimum horizontal drag (px) before a swipe counts as "change day" — small flicks/scrolls inside
 *  the vertical hour grid shouldn't accidentally page to another day. */
private const val SWIPE_THRESHOLD_PX = 120f

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
    todayEffectSpeed: Float = 1f,
    selectedIds: Set<Long> = emptySet(),
    onItemLongClick: (EntryCalendarItem) -> Unit = {},
    // Swiping is a distinct gesture from the Year/Month/Week/Day mode-switch's page-flip (see
    // CalendarScreen's book-flip) — a horizontal slide instead, matching how most calendar/pager
    // UIs distinguish "change what's showing" (flip) from "move within the same granularity" (slide).
    onNavigateDay: (deltaDays: Int) -> Unit = {}
) {
    val zoneId = ZoneId.systemDefault()
    val date = remember(selectedDateMillis) { Instant.ofEpochMilli(selectedDateMillis).atZone(zoneId).toLocalDate() }
    val dragAccumulator = remember { mutableFloatStateOf(0f) }

    AnimatedContent(
        targetState = date,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccumulator.floatValue = 0f },
                    onDragEnd = {
                        val dragged = dragAccumulator.floatValue
                        if (abs(dragged) >= SWIPE_THRESHOLD_PX) {
                            // Natural-scroll convention: dragging left (negative delta) reveals the
                            // next day, dragging right reveals the previous one — same direction as
                            // swiping through photos or pages.
                            onNavigateDay(if (dragged < 0) 1 else -1)
                        }
                        dragAccumulator.floatValue = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    dragAccumulator.floatValue += dragAmount
                }
            },
        transitionSpec = {
            // Distinct from the Year/Month/Week/Day mode-switch's page-flip — a plain horizontal
            // slide, direction matching whether this is a forward (next day) or backward (previous
            // day) navigation, same convention as swiping through photos/pages.
            val forward = targetState.isAfter(initialState)
            if (forward) {
                (slideInHorizontally(tween(300)) { fullWidth -> fullWidth } + fadeIn(tween(300)))
                    .togetherWith(slideOutHorizontally(tween(300)) { fullWidth -> -fullWidth } + fadeOut(tween(300)))
            } else {
                (slideInHorizontally(tween(300)) { fullWidth -> -fullWidth } + fadeIn(tween(300)))
                    .togetherWith(slideOutHorizontally(tween(300)) { fullWidth -> fullWidth } + fadeOut(tween(300)))
            }
        },
        label = "dayViewSwipe"
    ) { animatedDate ->
        DayViewContent(
            date = animatedDate,
            items = items,
            layers = layers,
            selectedDateMillis = selectedDateMillis,
            locale = locale,
            onItemClick = onItemClick,
            onOpenDaySummary = onOpenDaySummary,
            todayEffect = todayEffect,
            todayEffectStyle = todayEffectStyle,
            todayEffectPrimaryColor = todayEffectPrimaryColor,
            todayEffectSecondaryColor = todayEffectSecondaryColor,
            todayEffectSpeed = todayEffectSpeed,
            selectedIds = selectedIds,
            onItemLongClick = onItemLongClick
        )
    }
}

@Composable
private fun DayViewContent(
    date: LocalDate,
    items: List<EntryCalendarItem>,
    layers: List<CalendarLayer>,
    selectedDateMillis: Long,
    locale: Locale,
    onItemClick: (EntryCalendarItem) -> Unit,
    onOpenDaySummary: (Long) -> Unit,
    todayEffect: TodayEffect,
    todayEffectStyle: TodayEffectStyle,
    todayEffectPrimaryColor: Color,
    todayEffectSecondaryColor: Color?,
    todayEffectSpeed: Float,
    selectedIds: Set<Long>,
    onItemLongClick: (EntryCalendarItem) -> Unit
) {
    val zoneId = ZoneId.systemDefault()
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
                onItemClick = onItemClick,
                selectedIds = selectedIds,
                onItemLongClick = onItemLongClick
            )
            HorizontalDivider(thickness = 0.5.dp)
        }

        HorizontalDivider()

        // 3. Scrollable Timed Grid
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val scrollState = rememberScrollState()
            val density = LocalDensity.current
            val viewportHeightPx = with(density) { maxHeight.toPx() }
            // On first showing today's column, land with "now" about a third of the way down the
            // viewport (not pinned to the very top, and not dead-centered either) so the current
            // time is immediately visible along with a little of what's already past — rather than
            // opening on 00:00, which for most of the day is a long scroll away from "now".
            LaunchedEffect(date) {
                if (date == LocalDate.now()) {
                    val now = LocalTime.now()
                    val nowFraction = now.hour + now.minute / 60f
                    val hourHeightPx = with(density) { HOUR_HEIGHT.toPx() }
                    val targetPx = (hourHeightPx * nowFraction) - (viewportHeightPx * 0.35f)
                    scrollState.scrollTo(targetPx.toInt().coerceAtLeast(0))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(bottom = 24.dp)
            ) {
                HourAxisLabels(modifier = Modifier.width(HOUR_LABEL_WIDTH))
                DayColumn(
                    date = date,
                    items = timedEvents, // Grid only shows timed events
                    layerById = layerById,
                    onItemClick = onItemClick,
                    modifier = Modifier.weight(1f),
                    selectedIds = selectedIds,
                    onItemLongClick = onItemLongClick
                )
            }
        }
    }
}
