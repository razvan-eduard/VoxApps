package com.voxapps.calendarapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.domain.daylink.DayLinkIntentSender
import com.voxapps.calendarapp.domain.daylink.DaySummary
import com.voxapps.calendarapp.domain.daylink.DaySummaryClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/**
 * Shown on day-tap (from [DayView]'s header) — this day's Calendar entries plus two cross-app
 * sections ("Notes", "Expenses") fetched via [DaySummaryClient]. Each section's rows are tappable to
 * open that app pre-filtered to the day (see [DayLinkIntentSender]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySummarySheet(
    dayMillis: Long,
    calendarItemsForDay: List<EntryCalendarItem>,
    onDismiss: () -> Unit,
    onEditEntry: (EntryCalendarItem) -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val zoneId = ZoneId.systemDefault()

    var notesSummary by remember { mutableStateOf<DaySummary?>(null) }
    var expensesSummary by remember { mutableStateOf<DaySummary?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(dayMillis) {
        loading = true
        val date = Instant.ofEpochMilli(dayMillis).atZone(zoneId).toLocalDate()
        val from = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1
        withContext(Dispatchers.IO) {
            notesSummary = DaySummaryClient.fetchNotes(context, from, to)
            expensesSummary = DaySummaryClient.fetchExpenses(context, from, to)
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
                calendarItemsForDay.forEach { item ->
                    Text(
                        text = item.entryWithTags.entry.title,
                        modifier = Modifier.fillMaxWidth().clickable { onEditEntry(item) }.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                HorizontalDivider()
            }

            Text(languageManager.getString("day_summary_notes"), style = MaterialTheme.typography.labelLarge)
            DaySummarySection(
                loading = loading,
                summary = notesSummary,
                emptyKey = "day_summary_no_notes",
                languageManager = languageManager
            )
            Button(
                onClick = { DayLinkIntentSender.openNotesOnDay(context, dayMillis) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(languageManager.getString("day_summary_open_notes")) }

            HorizontalDivider()

            Text(languageManager.getString("day_summary_expenses"), style = MaterialTheme.typography.labelLarge)
            DaySummarySection(
                loading = loading,
                summary = expensesSummary,
                emptyKey = "day_summary_no_expenses",
                languageManager = languageManager
            )
            Button(
                onClick = { DayLinkIntentSender.openExpensesOnDay(context, dayMillis) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(languageManager.getString("day_summary_open_expenses")) }
        }
    }
}

@Composable
private fun DaySummarySection(
    loading: Boolean,
    summary: DaySummary?,
    emptyKey: String,
    languageManager: com.voxapps.calendarapp.domain.localization.LanguageManager
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
        else -> summary.items.forEach { entry ->
            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
