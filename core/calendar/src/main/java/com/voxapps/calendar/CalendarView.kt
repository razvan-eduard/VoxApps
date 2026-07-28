package com.voxapps.calendar

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlinx.coroutines.launch

private data class PendingScrollTarget(val month: YearMonth, val date: LocalDate)

/**
 * The single entry point apps call — a horizontally month-paged, vertically per-day agenda list,
 * with a fixed month/year header ([MonthYearHeader]) and a fading center popup naming the month
 * being swiped toward ([MonthTransitionIndicator]). See [CalendarMonthView] for per-month content.
 *
 * NOTE: the overscroll-triggers-month-change mechanism described in [CalendarOverscrollPaging] is
 * currently NOT wired in here — installing its `NestedScrollConnection` on the day-list froze
 * ordinary vertical scrolling entirely (confirmed on-device: neither direction scrolled past the
 * first gesture). Left as a follow-up once root-caused; for now, months only change via a manual
 * left/right swipe (the pager's own gesture) or a peek-item tap.
 */
@Composable
fun <T : CalendarItem> CalendarView(
    items: List<T>,
    modifier: Modifier = Modifier,
    pagerState: CalendarPagerState = rememberCalendarPagerState(),
    peekCount: Int = 3,
    locale: Locale = Locale.getDefault(),
    todayContentDescription: String = "Today",
    isHeaderExpanded: Boolean = false,
    onHeaderClick: (() -> Unit)? = null,
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

    // Callers that swap this composable out of composition (e.g. ExpensesRoot replacing
    // ExpensesScreen with ExpenseEditScreen and back) recreate pagerState/listState from scratch,
    // which otherwise leaves the day-list parked at its default top-of-month position. Re-run the
    // today scroll on every fresh mount so the default view is always "today", not day 1.
    LaunchedEffect(Unit) {
        navigateToToday()
    }

    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth()) {
            MonthYearHeader(
                month = pagerState.monthForPage(pagerState.pagerState.currentPage),
                modifier = Modifier.align(Alignment.Center),
                locale = locale,
                isExpanded = isHeaderExpanded,
                onClick = onHeaderClick
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
                val listState = rememberLazyListState()
                val prevPeekNonEmpty = remember(items, month, peekCount) {
                    CalendarDateUtils.lastItemsOfPreviousMonth(items, month, peekCount).isNotEmpty()
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
