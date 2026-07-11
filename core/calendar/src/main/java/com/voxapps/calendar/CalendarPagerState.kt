package com.voxapps.calendar

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// A large-but-finite virtual page range anchored on "today" (rather than a true infinite pager)
// keeps monthForPage/pageForMonth plain integer arithmetic with no overflow edge cases; ~100 years
// either direction is functionally unbounded for a notes/expenses app.
private const val ANCHOR_PAGE = 1200
private const val PAGE_COUNT = 2401

/** Maps [HorizontalPager][androidx.compose.foundation.pager.HorizontalPager] page indices to
 *  [YearMonth]s and back. */
@Stable
class CalendarPagerState(
    internal val anchorMonth: YearMonth,
    initialMonth: YearMonth
) {
    val pagerState: PagerState = PagerState(
        currentPage = pageForMonth(initialMonth),
        pageCount = { PAGE_COUNT }
    )

    fun monthForPage(page: Int): YearMonth = anchorMonth.plusMonths((page - ANCHOR_PAGE).toLong())

    fun pageForMonth(month: YearMonth): Int =
        ANCHOR_PAGE + ChronoUnit.MONTHS.between(anchorMonth, month).toInt()

    val currentMonth: YearMonth get() = monthForPage(pagerState.currentPage)
}

@Composable
fun rememberCalendarPagerState(initialMonth: YearMonth = YearMonth.now()): CalendarPagerState {
    val anchor = remember { YearMonth.now() }
    return remember { CalendarPagerState(anchorMonth = anchor, initialMonth = initialMonth) }
}
