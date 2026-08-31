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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
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
 *
 * With a [selection], the agenda list carries the shared multi-select grammar (see
 * [CalendarSelection]): `itemContent` receives each item's [CalendarItemSelection] handles to bind
 * onto its card. Without one, the handles are null and the list is tap-to-open only.
 */
@Composable
fun <T : CalendarItem> CalendarView(
    items: List<T>,
    modifier: Modifier = Modifier,
    pagerState: CalendarPagerState? = null,
    peekCount: Int = 3,
    locale: Locale = Locale.getDefault(),
    todayContentDescription: String = "Today",
    selectedDateMillis: Long = System.currentTimeMillis(),
    isGridView: Boolean = false,
    onToggleGridView: (() -> Unit)? = null,
    onDateSelected: ((Long) -> Unit)? = null,
    dayDots: Map<LocalDate, List<Long>> = emptyMap(),
    todayEffect: TodayEffect = TodayEffect.NONE,
    todayEffectStyle: TodayEffectStyle = TodayEffectStyle.RING,
    todayEffectPrimaryColor: Color = Color(0xFFFF6D00),
    todayEffectSecondaryColor: Color? = null,
    todayEffectSpeed: Float = 1f,
    selection: CalendarSelection<T>? = null,
    itemContent: @Composable (T, CalendarItemSelection?) -> Unit,
    emptyDayContent: (@Composable (LocalDate) -> Unit)? = null
) {
    // The month/grid plumbing below predates the selection contract and hands cards a plain
    // one-argument slot; the contract is bound here, once, so neither inner view repeats it.
    val boundItemContent: @Composable (T) -> Unit = { item ->
        itemContent(item, selection?.handlesFor(item))
    }
    val scope = rememberCoroutineScope()
    var pendingScroll by remember { mutableStateOf<PendingScrollTarget?>(null) }
    // The pager opens at the SELECTED month, not today's: this composable unmounts whenever a
    // record editor covers it, and a today-anchored fresh pager made the month sync below reset
    // the user's selection to the current month on every return.
    @Suppress("NAME_SHADOWING") val pagerState = pagerState ?: rememberCalendarPagerState(
        initialMonth = remember { YearMonth.from(CalendarDateUtils.millisToLocalDate(selectedDateMillis)) }
    )

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

    // Sync Pager Page -> Global Selected Date (Month level). Skips its very first run: it exists
    // to mirror a USER month-swipe into the selection, and on first composition the page change is
    // just the pager being created — letting that run would overwrite a restored selection.
    var lastSyncedPage by remember { mutableStateOf(pagerState.pagerState.currentPage) }
    LaunchedEffect(pagerState.pagerState.currentPage) {
        val page = pagerState.pagerState.currentPage
        if (page == lastSyncedPage) return@LaunchedEffect
        lastSyncedPage = page
        val pagerMonth = pagerState.currentMonth
        val selectedDate = CalendarDateUtils.millisToLocalDate(selectedDateMillis)
        if (YearMonth.from(selectedDate) != pagerMonth) {
            onDateSelected?.invoke(
                CalendarDateUtils.startOfDayMillis(CalendarDateUtils.dayToLandOn(pagerMonth))
            )
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
            itemContent = boundItemContent,
            pagerState = pagerState,
            dayDots = dayDots,
            todayLabel = todayContentDescription,
            todayEffect = todayEffect,
            todayEffectStyle = todayEffectStyle,
            todayEffectPrimaryColor = todayEffectPrimaryColor,
            todayEffectSecondaryColor = todayEffectSecondaryColor,
            todayEffectSpeed = todayEffectSpeed,
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
                    
                    val prevPeekNonEmpty = remember(items, month, peekCount) {
                        CalendarDateUtils.lastItemsOfPreviousMonth(items, month, peekCount).isNotEmpty()
                    }

                    // Opens on the selected day when it belongs to this month, otherwise at the top.
                    // Through the shared mapping, so the peek section above the days is counted here
                    // exactly as the Today button counts it.
                    val initialIndex = remember(month, prevPeekNonEmpty) {
                        if (YearMonth.from(selectedDate) == month) {
                            dayIndexInList(month, selectedDate, prevPeekNonEmpty)
                        } else {
                            0
                        }
                    }
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
                    val isDragged by listState.interactionSource.collectIsDraggedAsState()

                    // A screen composes before its records arrive, so the first frame sees an empty
                    // list and cannot know whether the previous month's peek will be there — and the
                    // seeded position above is only read once. Re-aim at the selected day when that
                    // answer changes, and stop the moment a finger has moved the list, which is the
                    // point at which where it sits stops being this code's business.
                    var userHasDragged by remember(month) { mutableStateOf(false) }
                    LaunchedEffect(isDragged) { if (isDragged) userHasDragged = true }
                    LaunchedEffect(month, prevPeekNonEmpty) {
                        if (userHasDragged || pendingScroll != null) return@LaunchedEffect
                        if (YearMonth.from(selectedDate) != month) return@LaunchedEffect
                        listState.scrollToItem(dayIndexInList(month, selectedDate, prevPeekNonEmpty))
                    }

                    // Sync scroll -> global selected date. The selection is read through
                    // rememberUpdatedState: the collect lambda outlives many selection changes,
                    // and a stale comparison value re-fires the callback for a date already set.
                    val currentSelectedMillis by androidx.compose.runtime.rememberUpdatedState(selectedDateMillis)
                    LaunchedEffect(listState, pagerState.pagerState.currentPage) {
                        if (pagerState.pagerState.currentPage == page) {
                            snapshotFlow { listState.firstVisibleItemIndex }
                                .distinctUntilChanged()
                                .filter { (isDragged || listState.isScrollInProgress) && pendingScroll == null }
                                .collect { index ->
                                    val newDate = dayForIndexInList(month, index, prevPeekNonEmpty)
                                    if (newDate != null) {
                                        val newMillis = CalendarDateUtils.startOfDayMillis(newDate)
                                        if (newMillis != currentSelectedMillis) {
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
                        itemContent = boundItemContent,
                        emptyDayContent = emptyDayContent,
                        todayLabel = todayContentDescription,
                        todayEffect = todayEffect,
                        todayEffectStyle = todayEffectStyle,
                        todayEffectPrimaryColor = todayEffectPrimaryColor,
                        todayEffectSecondaryColor = todayEffectSecondaryColor,
                        todayEffectSpeed = todayEffectSpeed
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
