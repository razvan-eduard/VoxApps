package com.voxapps.calendarapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.calendarapp.domain.localization.LanguageManager

/** offsetMinutesBefore -> translation key. v1 preset set only — no custom-minutes input. Shared by
 *  both the per-entry reminder picker ([com.voxapps.calendarapp.ui.EntryEditScreen]) and the
 *  per-calendar one ([Sidebar]'s `LayerEditDialog`), so the two are always guaranteed to offer the
 *  identical option set. */
val REMINDER_PRESETS = listOf(
    0 to "reminder_at_start",
    5 to "reminder_5min",
    15 to "reminder_15min",
    30 to "reminder_30min",
    60 to "reminder_1hour",
    1440 to "reminder_1day"
)

/** A row of toggleable reminder-offset chips. [enabled] = false renders every chip non-interactive
 *  (used when a calendar-level override is active — see [EntryEditScreen]'s reminders section). */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ReminderOffsetsPicker(
    selected: List<Int>,
    onToggle: (Int) -> Unit,
    languageManager: LanguageManager,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        REMINDER_PRESETS.forEach { (offset, labelKey) ->
            FilterChip(
                selected = offset in selected,
                enabled = enabled,
                onClick = { onToggle(offset) },
                label = { Text(languageManager.getString(labelKey)) }
            )
        }
    }
}
