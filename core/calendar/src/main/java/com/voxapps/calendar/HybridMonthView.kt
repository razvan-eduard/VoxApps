package com.voxapps.calendar

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kizitonwose.calendar.compose.rememberCalendarState
import com.kizitonwose.calendar.core.OutDateStyle
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun <T : CalendarItem> HybridMonthView(
    items: List<T>,
    selectedDateMillis: Long,
    locale: Locale,
    onDateSelected: (Long) -> Unit,
    onToggleGridView: () -> Unit,
    itemContent: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
    pagerState: CalendarPagerState? = null,
    dayDots: Map<LocalDate, List<Long>> = emptyMap(),
    todayLabel: String = "Today",
    todayEffect: TodayEffect = TodayEffect.NONE,
    todayEffectStyle: TodayEffectStyle = TodayEffectStyle.RING,
    todayEffectPrimaryColor: Color = Color(0xFFFF6D00),
    todayEffectSecondaryColor: Color? = null,
    todayEffectSpeed: Float = 1f
) {
    val listState = rememberLazyListState()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var isAnimatingToDate by remember { mutableStateOf(false) }

    val selectedDate = remember(selectedDateMillis) {
        CalendarDateUtils.millisToLocalDate(selectedDateMillis)
    }
    val currentMonth = remember(selectedDate) { YearMonth.from(selectedDate) }

    val gridState = rememberCalendarState(
        startMonth = currentMonth.minusMonths(120),
        endMonth = currentMonth.plusMonths(120),
        firstVisibleMonth = currentMonth,
        firstDayOfWeek = firstDayOfWeekFromLocale(),
        outDateStyle = OutDateStyle.EndOfRow
    )

    // 1. Sync Grid -> Pager (Month level)
    LaunchedEffect(gridState.firstVisibleMonth) {
        val gridMonth = gridState.firstVisibleMonth.yearMonth
        if (pagerState != null && pagerState.currentMonth != gridMonth) {
            pagerState.pagerState.scrollToPage(pagerState.pageForMonth(gridMonth))
        }
    }

    // 2. Sync Pager -> Grid (Month level)
    if (pagerState != null) {
        LaunchedEffect(pagerState.pagerState.currentPage) {
            val pagerMonth = pagerState.currentMonth
            if (gridState.firstVisibleMonth.yearMonth != pagerMonth) {
                gridState.scrollToMonth(pagerMonth)
            }
        }
    }

    // 3. Sync Selection -> List Scroll
    LaunchedEffect(selectedDate) {
        val targetIndex = selectedDate.dayOfMonth - 1
        if (listState.firstVisibleItemIndex != targetIndex && !isDragged) {
            isAnimatingToDate = true
            listState.animateScrollToItem(targetIndex)
            isAnimatingToDate = false
        }
        // Ensure grid also shows the month of the selected date
        if (gridState.firstVisibleMonth.yearMonth != YearMonth.from(selectedDate)) {
            gridState.scrollToMonth(YearMonth.from(selectedDate))
        }
    }

    // 4. Sync List Scroll -> Selection. Keyed on the month: an effect keyed on listState alone
    // captured the FIRST composition's month forever, so after the user moved months, scrolling
    // the agenda computed dates in the stale month and yanked the whole view back to it. The
    // selection is read through rememberUpdatedState for the same reason.
    val currentSelectedMillis by androidx.compose.runtime.rememberUpdatedState(selectedDateMillis)
    LaunchedEffect(listState, currentMonth) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .filter { (isDragged || listState.isScrollInProgress) && !isAnimatingToDate }
            .collect { index ->
                val dayOfMonth = index + 1
                if (dayOfMonth <= currentMonth.lengthOfMonth()) {
                    val newDate = currentMonth.atDay(dayOfMonth)
                    val newMillis = java.time.Instant.ofEpochMilli(currentSelectedMillis)
                        .atZone(java.time.ZoneId.systemDefault())
                        .with(newDate)
                        .toInstant()
                        .toEpochMilli()
                    if (newMillis != currentSelectedMillis) {
                        onDateSelected(newMillis)
                    }
                }
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        MonthGridView(
            selectedDateMillis = selectedDateMillis,
            locale = locale,
            onDateSelected = onDateSelected,
            onHeaderClick = onToggleGridView,
            state = gridState,
            dayDots = dayDots,
            todayEffect = todayEffect,
            todayEffectStyle = todayEffectStyle,
            todayEffectPrimaryColor = todayEffectPrimaryColor,
            todayEffectSecondaryColor = todayEffectSecondaryColor,
            todayEffectSpeed = todayEffectSpeed,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(thickness = 1.dp)

        CalendarMonthView(
            month = currentMonth,
            allItems = items,
            listState = listState,
            peekCount = 0, // No peeks in hybrid mode
            locale = locale,
            onPeekItemClick = { /* already in current month */ },
            itemContent = itemContent,
            todayLabel = todayLabel,
            todayEffect = todayEffect,
            todayEffectStyle = todayEffectStyle,
            todayEffectPrimaryColor = todayEffectPrimaryColor,
            todayEffectSecondaryColor = todayEffectSecondaryColor,
            todayEffectSpeed = todayEffectSpeed,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}
