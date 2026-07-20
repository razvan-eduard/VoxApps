package com.voxapps.calendarapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.data.CalendarLayer
import com.voxapps.calendarapp.domain.daylink.DayLinkIntentSender
import com.voxapps.calendarapp.domain.daylink.DaySummary
import com.voxapps.calendarapp.domain.daylink.DaySummaryClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/** Matches the home-screen widgets' row-tint alpha ([com.voxapps.calendarapp.ui.widget.CalendarWidget]
 *  and friends) so a color-coded row looks identical whether it's reached from the widget or here. */
private const val ROW_TINT_ALPHA = 0.18f

/**
 * Shown on day-tap (from [DayView]'s header) — this day's Calendar entries plus two cross-app
 * sections ("Notes", "Expenses") fetched via [DaySummaryClient]. Every row uses the same color-tinted,
 * rounded, tappable style as that source app's own home-screen widget (see [WidgetStyleRow]) and taps
 * straight into that record's editor — a Notes/Expenses section is omitted entirely (not just shown
 * empty) when that app isn't installed, checked locally before ever attempting the cross-app fetch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySummarySheet(
    dayMillis: Long,
    calendarItemsForDay: List<EntryCalendarItem>,
    layers: List<CalendarLayer>,
    onDismiss: () -> Unit,
    onEditEntry: (EntryCalendarItem) -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val zoneId = ZoneId.systemDefault()
    val layerById = remember(layers) { layers.associateBy { it.id } }

    val notesInstalled = remember { DaySummaryClient.isNotesInstalled(context) }
    val expensesInstalled = remember { DaySummaryClient.isExpensesInstalled(context) }

    var notesSummary by remember { mutableStateOf<DaySummary?>(null) }
    var expensesSummary by remember { mutableStateOf<DaySummary?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(dayMillis) {
        loading = true
        val date = Instant.ofEpochMilli(dayMillis).atZone(zoneId).toLocalDate()
        val from = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1
        withContext(Dispatchers.IO) {
            if (notesInstalled) notesSummary = DaySummaryClient.fetchNotes(context, from, to)
            if (expensesInstalled) expensesSummary = DaySummaryClient.fetchExpenses(context, from, to)
        }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = DateFormat.getDateInstance(DateFormat.FULL).format(Date(dayMillis)),
                style = MaterialTheme.typography.titleMedium
            )

            if (calendarItemsForDay.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    calendarItemsForDay.forEach { item ->
                        val entry = item.entryWithTags.entry
                        val layerColor = layerById[entry.layerId]?.let { Color(it.colorArgb.toInt()) }
                        WidgetStyleRow(
                            title = entry.title,
                            trailing = if (!entry.allDay) {
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.occurrenceStartMillis))
                            } else {
                                null
                            },
                            tint = layerColor,
                            onClick = { onEditEntry(item) }
                        )
                    }
                }
                HorizontalDivider()
            }

            if (notesInstalled) {
                DaySummaryCard {
                    Text(languageManager.getString("day_summary_notes"), style = MaterialTheme.typography.labelLarge)
                    DaySummarySection(
                        loading = loading,
                        summary = notesSummary,
                        emptyKey = "day_summary_no_notes",
                        languageManager = languageManager,
                        onItemClick = { DayLinkIntentSender.openNoteForEdit(context, it.id) }
                    )
                }
            }

            if (expensesInstalled) {
                DaySummaryCard {
                    Text(languageManager.getString("day_summary_expenses"), style = MaterialTheme.typography.labelLarge)
                    DaySummarySection(
                        loading = loading,
                        summary = expensesSummary,
                        emptyKey = "day_summary_no_expenses",
                        languageManager = languageManager,
                        onItemClick = { DayLinkIntentSender.openExpenseForEdit(context, it.id) }
                    )
                }
            }
        }
    }
}

/** Groups a cross-app section (Notes or Expenses) into its own bordered card — rows are tappable
 *  themselves now (see [WidgetStyleRow]), so this replaces what used to be a separate "Open X" button. */
@Composable
private fun DaySummaryCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun DaySummarySection(
    loading: Boolean,
    summary: DaySummary?,
    emptyKey: String,
    languageManager: com.voxapps.calendarapp.domain.localization.LanguageManager,
    onItemClick: (com.voxapps.calendarapp.domain.daylink.DaySummaryEntry) -> Unit
) {
    when {
        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        summary == null || summary.items.isEmpty() -> Text(
            text = languageManager.getString(emptyKey),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            summary.items.forEach { entry ->
                WidgetStyleRow(
                    title = entry.title,
                    trailing = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.timeMillis)),
                    tint = entry.colorArgb?.let { Color(it.toInt()) },
                    onClick = { onItemClick(entry) }
                )
            }
        }
    }
}

/**
 * One row, styled to match the home-screen widgets' entry rows exactly (see [ROW_TINT_ALPHA] and
 * `CalendarWidget`/`ExpensesWidget`/`NotesWidget`'s own row composables): a rounded, [tint]-colored
 * (at [ROW_TINT_ALPHA]) background — or no background at all when [tint] is null (uncategorized) —
 * title on the left, an optional trailing time/label on the right, whole row tappable.
 */
@Composable
private fun WidgetStyleRow(
    title: String,
    trailing: String?,
    tint: Color?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .let { m -> if (tint != null) m.background(tint.copy(alpha = ROW_TINT_ALPHA)) else m }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
