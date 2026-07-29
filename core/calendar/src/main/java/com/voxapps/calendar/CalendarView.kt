package com.voxapps.calendar

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private data class PendingScrollTarget(val month: YearMonth, val date: LocalDate)

/**
 * The single entry point apps call — a horizontally month-paged, vertically per-day agenda list.
 * Can optionally show a hybrid grid view if [isGridView] is true.
 */
@Composable
fun <T : CalendarItem> CalendarView(
    items: List<T>,
    modifier: Modifier = Modifier,
    pagerState: CalendarPagerState = rememberCalendarPagerState(),
    peekCount: Int = 3,
    locale: Locale = Locale.getDefault(),
    todayContentDescription: String = "Today",
    selectedDateMillis: Long = System.currentTimeMillis(),
    isGridView: Boolean = false,
    onToggleGridView: (() -> Unit)? = null,
    onDateSelected: ((Long) -> Unit)? = null,
    dayDots: Map<LocalDate, List<Long>> = emptyMap(),
    itemContent: @Composable (T) -> Unit,
    emptyDayContent: (@Composable (LocalDate) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var pendingScroll by remember { mutableStateOf<PendingScrollTarget?>(null) }

    fun navigateToItem(item: T) {
        val targetDate = CalendarDateUtils.millisToLocalDate(item.dateTimeMillis)
        val targetMonth = YearMonth.from(targetDate)
        pendingScroll = PendingScrollTarget(targetMonth, targetDate)
        scope.launch {
            pagerState.pagerState.animateScrollToPage(pagerState.pageForMonth(targetMonth))
        }
    }

    fun navigateToToday() {
        val today = LocalDate.now()
        val targetMonth = YearMonth.from(today)
        pendingScroll = PendingScrollTarget(targetMonth, today)
        scope.launch {
            pagerState.pagerState.animateScrollToPage(pagerState.pageForMonth(targetMonth))
        }
    }

    // Sync Pager Page -> Global Selected Date (Month level)
    LaunchedEffect(pagerState.pagerState.currentPage) {
        val pagerMonth = pagerState.currentMonth
        val selectedDate = CalendarDateUtils.millisToLocalDate(selectedDateMillis)
        if (YearMonth.from(selectedDate) != pagerMonth) {
            // User swiped to a new month, update selection to the 1st of that month
            val newDate = pagerMonth.atDay(1)
            onDateSelected?.invoke(CalendarDateUtils.startOfDayMillis(newDate))
        }
    }

    if (isGridView && onToggleGridView != null) {
        HybridMonthView(
            items = items,
            selectedDateMillis = selectedDateMillis,
            locale = locale,
            onDateSelected = { millis ->
                onDateSelected?.invoke(millis)
            },
            onToggleGridView = onToggleGridView,
            itemContent = itemContent,
            pagerState = pagerState,
            dayDots = dayDots,
            modifier = modifier
        )
    } else {
        Column(modifier = modifier) {
            Box(modifier = Modifier.fillMaxWidth()) {
                MonthYearHeader(
                    month = pagerState.monthForPage(pagerState.pagerState.currentPage),
                    modifier = Modifier.align(Alignment.Center),
                    locale = locale,
                    isExpanded = false,
                    onClick = onToggleGridView
                )
                IconButton(
                    onClick = ::navigateToToday,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                ) {
                    Icon(Icons.Filled.Today, contentDescription = todayContentDescription)
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                HorizontalPager(state = pagerState.pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                    val month = pagerState.monthForPage(page)
                    val selectedDate = remember(selectedDateMillis) { CalendarDateUtils.millisToLocalDate(selectedDateMillis) }
                    
                    // Initialize list at the selected day if it's in this month, otherwise at day 1
                    val initialIndex = remember(month) {
                        if (YearMonth.from(selectedDate) == month) selectedDate.dayOfMonth - 1 else 0
                    }
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
                    val isDragged by listState.interactionSource.collectIsDraggedAsState()
                    
                    val prevPeekNonEmpty = remember(items, month, peekCount) {
                        CalendarDateUtils.lastItemsOfPreviousMonth(items, month, peekCount).isNotEmpty()
                    }

                    // Sync scroll -> global selected date
                    LaunchedEffect(listState, pagerState.pagerState.currentPage) {
                        if (pagerState.pagerState.currentPage == page) {
                            snapshotFlow { listState.firstVisibleItemIndex }
                                .distinctUntilChanged()
                                .filter { (isDragged || listState.isScrollInProgress) && pendingScroll == null }
                                .collect { index ->
                                    val dayOfMonth = index + 1
                                    if (dayOfMonth <= month.lengthOfMonth()) {
                                        val newDate = month.atDay(dayOfMonth)
                                        val newMillis = CalendarDateUtils.startOfDayMillis(newDate)
                                        if (newMillis != selectedDateMillis) {
                                            onDateSelected?.invoke(newMillis)
                                        }
                                    }
                                }
                        }
                    }

                    LaunchedEffect(pendingScroll, month) {
                        val target = pendingScroll ?: return@LaunchedEffect
                        if (target.month != month) return@LaunchedEffect
                        val index = dayIndexInList(month, target.date, prevPeekNonEmpty)
                        listState.animateScrollToItem(index)
                        pendingScroll = null
                    }

                    CalendarMonthView(
                        month = month,
                        allItems = items,
                        listState = listState,
                        peekCount = peekCount,
                        locale = locale,
                        onPeekItemClick = ::navigateToItem,
                        itemContent = itemContent,
                        emptyDayContent = emptyDayContent
                    )
                }

                MonthTransitionIndicator(
                    pagerState = pagerState.pagerState,
                    monthForPage = pagerState::monthForPage
                )
            }
        }
    }
}
