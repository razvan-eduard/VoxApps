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
import java.time.LocalTime
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.abs
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedContent

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
    onItemLongClick: (EntryCalendarItem) -> Unit = {},
    /** Swiping the grid moves a week at a time; the caller owns which date is selected. */
    onWeekChange: (Long) -> Unit = {},
    /** The app-wide animation switch. Off means the new week simply appears. */
    animationsEnabled: Boolean = true
) {
    val zoneId = ZoneId.systemDefault()
    val languageManager = LocalLanguageManager.current
    val selectedDate = remember(selectedDateMillis) { Instant.ofEpochMilli(selectedDateMillis).atZone(zoneId).toLocalDate() }
    val weekStart = remember(selectedDate) { selectedDate.with(DayOfWeek.MONDAY) }
    val days = remember(weekStart) { (0..6).map { weekStart.plusDays(it.toLong()) } }
    val weekNumber = remember(weekStart) { weekStart.get(WeekFields.ISO.weekOfWeekBasedYear()) }
    val layerById = remember(layers) { layers.associateBy { it.id } }
    val today = LocalDate.now()
    // Which way the last swipe went, so the slide matches it.
    var movingForward by remember { mutableStateOf(true) }

    // Horizontal drags move between weeks; the grid's own vertical scrolling is untouched, since
    // the two gestures claim different axes.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(weekStart) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(dragged) > SWIPE_WEEK_THRESHOLD_PX) {
                            movingForward = dragged < 0
                            val target = if (movingForward) weekStart.plusWeeks(1) else weekStart.minusWeeks(1)
                            onWeekChange(target.atStartOfDay(zoneId).toInstant().toEpochMilli())
                        }
                        dragged = 0f
                    },
                    onDragCancel = { dragged = 0f },
                    onHorizontalDrag = { _, amount -> dragged += amount }
                )
            }
    ) {
        Text(
            text = String.format(languageManager.getString("week_number_label"), weekNumber),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
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
        // The grid slides in the direction the finger went — the incoming week enters from the side
        // the swipe came from and the outgoing one leaves the other way. Without a remembered
        // direction the transition has no idea whether "next" is left or right.
        AnimatedContent(
            targetState = weekStart,
            transitionSpec = {
                if (!animationsEnabled) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val direction = if (movingForward) 1 else -1
                    slideInHorizontally(tween(WEEK_SLIDE_MILLIS)) { width -> direction * width } togetherWith
                        slideOutHorizontally(tween(WEEK_SLIDE_MILLIS)) { width -> -direction * width }
                }
            },
            label = "week"
        ) { _ ->
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
                    val isAlternate = days.indexOf(day) % 2 == 1
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
                        // Tints are drawn ON TOP of the column, never behind it: DayColumn's own
                        // hour-cell backgrounds are opaque, so anything behind them is invisible.
                        //
                        // Every other day carries a faint wash, so seven columns read as seven days
                        // at a glance instead of one field of lines you have to count across.
                        if (isAlternate) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = ALTERNATE_DAY_ALPHA))
                            )
                        }
                        // Today is not just another stripe: a fade down the column, strongest at the
                        // top where the eye lands. One constant either way if the colour is wrong.
                        if (day == today) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                TODAY_COLUMN_COLOR.copy(alpha = TODAY_COLUMN_TOP_ALPHA),
                                                TODAY_COLUMN_COLOR.copy(alpha = TODAY_COLUMN_BOTTOM_ALPHA)
                                            )
                                        )
                                    )
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
}

/** Every other column carries this much of [MaterialTheme.colorScheme.onSurface], so the week reads
 *  as seven days rather than one ruled field. */
private const val ALTERNATE_DAY_ALPHA = 0.05f

/** Today's column fades from this colour, strongest at the top. */
private val TODAY_COLUMN_COLOR = Color(0xFF4CAF50)
private const val TODAY_COLUMN_TOP_ALPHA = 0.28f
private const val TODAY_COLUMN_BOTTOM_ALPHA = 0.04f

/** How far a horizontal drag must travel before it counts as "next week" rather than a stray
 *  sideways wobble during a vertical scroll. */
private const val SWIPE_WEEK_THRESHOLD_PX = 120f

/** How long a week takes to slide past. Long enough to read as movement, short enough that a run of
 *  swipes does not queue up behind it. */
private const val WEEK_SLIDE_MILLIS = 260
