package com.voxapps.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * The flattened LazyColumn item index for [date] within [month]'s day-list — used to scroll
 * precisely to a specific day (e.g. after a peek-item tap or cross-month navigation). Correct only
 * because [CalendarMonthView] emits exactly one `item{}` per calendar day (with that day's cards
 * nested inside via a plain `Column`, not a further `items()` call) rather than a flat `items()`
 * over individual notes/expenses — so this is a simple offset, not a per-day item-count sum.
 *
 * The previous month's grayed peek is an item of its own when it is shown, so it shifts every day
 * below it. Both directions of the mapping live here, and every caller goes through one of them:
 * written out by hand at a call site, the offset is the kind of thing that is remembered in the
 * place that scrolls and forgotten in the place that reads the scroll back.
 */
internal fun dayIndexInList(month: YearMonth, date: LocalDate, hasPrevPeek: Boolean): Int {
    val prevOffset = if (hasPrevPeek) 1 else 0
    val dayOffset = date.dayOfMonth - 1
    return prevOffset + dayOffset
}

/**
 * The inverse of [dayIndexInList]: which day of [month] a list index is showing, or null where the
 * index is not a day of it at all — the previous month's peek above the days, or the next month's
 * below them.
 */
internal fun dayForIndexInList(month: YearMonth, index: Int, hasPrevPeek: Boolean): LocalDate? {
    val prevOffset = if (hasPrevPeek) 1 else 0
    val dayOfMonth = index - prevOffset + 1
    return if (dayOfMonth in 1..month.lengthOfMonth()) month.atDay(dayOfMonth) else null
}

/**
 * Per-month day-list content: an optional grayed peek into the tail of the previous month, one row
 * per calendar day of [month] (every day, including empty ones — see [CalendarDateUtils.bucketByDay]),
 * then an optional grayed peek into the head of the next month. Rendered by [CalendarView] as the
 * content of a single [androidx.compose.foundation.pager.HorizontalPager] page.
 */
@Composable
fun <T : CalendarItem> CalendarMonthView(
    month: YearMonth,
    allItems: List<T>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    peekCount: Int = 3,
    locale: Locale = Locale.getDefault(),
    onPeekItemClick: (T) -> Unit,
    itemContent: @Composable (T) -> Unit,
    emptyDayContent: (@Composable (LocalDate) -> Unit)? = null,
    today: LocalDate = LocalDate.now(),
    todayLabel: String = "Today",
    todayEffect: TodayEffect = TodayEffect.NONE,
    todayEffectStyle: TodayEffectStyle = TodayEffectStyle.RING,
    todayEffectPrimaryColor: Color = Color(0xFFFF6D00),
    todayEffectSecondaryColor: Color? = null,
    todayEffectSpeed: Float = 1f
) {
    val dayBuckets = remember(allItems, month) { CalendarDateUtils.bucketByDay(allItems, month) }
    val prevPeek = remember(allItems, month, peekCount) {
        CalendarDateUtils.lastItemsOfPreviousMonth(allItems, month, peekCount)
    }
    val nextPeek = remember(allItems, month, peekCount) {
        CalendarDateUtils.firstItemsOfNextMonth(allItems, month, peekCount)
    }

    LazyColumn(state = listState, modifier = modifier) {
        if (prevPeek.isNotEmpty()) {
            item(key = "peek-prev") {
                GrayedPeekSection(items = prevPeek, itemContent = itemContent, onClick = onPeekItemClick)
            }
        }

        dayBuckets.forEach { (date, dayItems) ->
            item(key = "day-$date") {
                Column {
                    DayHeader(
                        date = date,
                        isEmpty = dayItems.isEmpty(),
                        locale = locale,
                        today = today,
                        todayLabel = todayLabel,
                        todayEffect = todayEffect,
                        todayEffectStyle = todayEffectStyle,
                        todayEffectPrimaryColor = todayEffectPrimaryColor,
                        todayEffectSecondaryColor = todayEffectSecondaryColor,
                        todayEffectSpeed = todayEffectSpeed
                    )
                    if (dayItems.isEmpty()) {
                        if (emptyDayContent != null) {
                            emptyDayContent(date)
                        } else {
                            DefaultEmptyDayRow()
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            dayItems.forEach { item -> itemContent(item) }
                        }
                    }
                }
            }
        }

        if (nextPeek.isNotEmpty()) {
            item(key = "peek-next") {
                GrayedPeekSection(items = nextPeek, itemContent = itemContent, onClick = onPeekItemClick)
            }
        }
    }
}

/** Reduced-alpha, tappable preview of an adjacent month's edge items — visually distinct from the
 *  current month's real content, navigates on tap (see [CalendarView.navigateToItem]). */
@Composable
private fun <T : CalendarItem> GrayedPeekSection(
    items: List<T>,
    itemContent: @Composable (T) -> Unit,
    onClick: (T) -> Unit
) {
    Column(modifier = Modifier.alpha(0.45f)) {
        items.forEach { item ->
            Box {
                // itemContent is caller-supplied and typically an already-clickable card (its own
                // onClick opens the normal edit flow) — a click modifier wrapping it here would never
                // fire, since the inner, more specific clickable always wins the touch. An opaque
                // overlay on top, matching its size, intercepts the tap first so peek items navigate
                // instead of opening the editor.
                itemContent(item)
                Box(
                    modifier = Modifier.matchParentSize().clickable { onClick(item) }
                )
            }
        }
    }
}

@Composable
private fun DefaultEmptyDayRow() {
    Text(
        text = "—",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 44.dp, bottom = 8.dp)
    )
}
