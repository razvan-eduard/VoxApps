package com.voxapps.calendarapp.ui

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
import androidx.compose.ui.unit.dp
import com.voxapps.calendar.CalendarDateUtils
import com.voxapps.calendar.CalendarMonthView
import com.voxapps.calendarapp.data.CalendarLayer
import java.time.YearMonth
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun HybridMonthView(
    items: List<EntryCalendarItem>,
    layers: List<CalendarLayer>,
    selectedDateMillis: Long,
    locale: Locale,
    onDateSelected: (Long) -> Unit,
    onToggleGridView: () -> Unit,
    itemContent: @Composable (EntryCalendarItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var isAnimatingToDate by remember { mutableStateOf(false) }

    val selectedDate = remember(selectedDateMillis) {
        CalendarDateUtils.millisToLocalDate(selectedDateMillis)
    }
    val currentMonth = remember(selectedDate) { YearMonth.from(selectedDate) }

    // Scroll the list when a date is selected in the grid
    LaunchedEffect(selectedDate) {
        // Only trigger scroll if it's not already at that position and not being dragged
        val targetIndex = selectedDate.dayOfMonth - 1
        if (listState.firstVisibleItemIndex != targetIndex && !isDragged) {
            isAnimatingToDate = true
            listState.animateScrollToItem(targetIndex)
            isAnimatingToDate = false
        }
    }

    // Sync grid selection when the list is scrolled manually
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            // IMPORTANT: only sync list -> grid if the user is actually dragging or flinging,
            // NOT during the programmatic animation triggered by the grid click.
            .filter { (isDragged || listState.isScrollInProgress) && !isAnimatingToDate }
            .collect { index ->
                val dayOfMonth = index + 1
                if (dayOfMonth <= currentMonth.lengthOfMonth()) {
                    val newDate = currentMonth.atDay(dayOfMonth)
                    val newMillis = java.time.Instant.ofEpochMilli(selectedDateMillis)
                        .atZone(java.time.ZoneId.systemDefault())
                        .with(newDate)
                        .toInstant()
                        .toEpochMilli()
                    if (newMillis != selectedDateMillis) {
                        onDateSelected(newMillis)
                    }
                }
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        MonthGridView(
            items = items,
            layers = layers,
            selectedDateMillis = selectedDateMillis,
            locale = locale,
            onDateSelected = onDateSelected,
            onHeaderClick = onToggleGridView,
            modifier = Modifier.fillMaxWidth() // Removed weight to allow dynamic height
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
            modifier = Modifier.fillMaxWidth().weight(1f) // Takes remaining space
        )
    }
}
