package com.voxapps.calendarapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxapps.calendarapp.data.CalendarLayer

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AllDayEventsPane(
    events: List<EntryCalendarItem>,
    layerById: Map<Long, CalendarLayer>,
    onItemClick: (EntryCalendarItem) -> Unit,
    modifier: Modifier = Modifier,
    selectedIds: Set<Long> = emptySet(),
    onItemLongClick: (EntryCalendarItem) -> Unit = {},
    // WeekView renders 7 of these side by side (each maybe 40-50dp wide) — DayView's single
    // full-width pane sizing (12dp horizontal padding, 12sp text, one line) leaves almost no room
    // for a title at that width, which is why it read as empty pills with no visible text. Week
    // passes compact=true for a much tighter fit that actually shows a couple of words.
    compact: Boolean = false
) {
    if (events.isEmpty()) return

    val horizontalPadding = if (compact) 4.dp else 12.dp
    val verticalPadding = if (compact) 3.dp else 6.dp
    val fontSize = if (compact) 10.sp else 12.sp
    val maxLines = if (compact) 2 else 1
    val maxHeight = if (compact) 160.dp else 120.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 2.dp else 8.dp, vertical = 4.dp)
            .heightIn(max = maxHeight) // Cap height and allow internal scroll if many
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)
    ) {
        events.forEach { item ->
            val entry = item.entryWithTags.entry
            val color = layerById[entry.layerId]?.let { Color(it.colorArgb.toInt()) }
                ?: MaterialTheme.colorScheme.primary
            val isSelected = entry.id in selectedIds

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color, RoundedCornerShape(if (compact) 6.dp else 16.dp))
                    .let {
                        if (isSelected) {
                            it.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(if (compact) 6.dp else 16.dp))
                        } else {
                            it
                        }
                    }
                    .combinedClickable(
                        onClick = { onItemClick(item) },
                        onLongClick = { onItemLongClick(item) }
                    )
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            ) {
                Text(
                    text = entry.title,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = fontSize,
                    lineHeight = if (compact) fontSize * 1.1f else MaterialTheme.typography.labelMedium.lineHeight,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
