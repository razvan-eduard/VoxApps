package com.voxapps.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voxapps.design.VoxFullscreenSheet
import com.voxapps.notes.state.SortMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSortSheet(
    sort: SortMode,
    dateFrom: Long?,
    dateTo: Long?,
    onApply: (SortMode, Long?, Long?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val rangeState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = dateFrom,
        initialSelectedEndDateMillis = dateTo
    )

    VoxFullscreenSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 20.dp, bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = sort == SortMode.NEWEST,
                    onClick = { onApply(SortMode.NEWEST, rangeState.selectedStartDateMillis, rangeState.selectedEndDateMillis) },
                    label = { Text(languageManager.getString("sort_newest")) }
                )
                FilterChip(
                    selected = sort == SortMode.OLDEST,
                    onClick = { onApply(SortMode.OLDEST, rangeState.selectedStartDateMillis, rangeState.selectedEndDateMillis) },
                    label = { Text(languageManager.getString("sort_oldest")) }
                )
            }

            DateRangePicker(state = rangeState, modifier = Modifier.weight(1f).padding(top = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onClear) { Text(languageManager.getString("clear")) }
                TextButton(
                    onClick = { onApply(sort, rangeState.selectedStartDateMillis, rangeState.selectedEndDateMillis) }
                ) { Text(languageManager.getString("apply")) }
            }
        }
    }
}
