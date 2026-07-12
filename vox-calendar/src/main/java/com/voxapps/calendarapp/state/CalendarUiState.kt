package com.voxapps.calendarapp.state

import androidx.compose.runtime.Immutable
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.CalendarLayer

enum class CalendarViewMode { YEAR, MONTH, WEEK, DAY }

/**
 * Top-level UI state for Vox Calendar (mirrors vox-expenses' ExpensesUiState). [Locked] is emitted only
 * when biometric reading is required and the session has expired. [Unlocked] carries the already
 * layer/tag-filtered entries plus the active view selection — recurrence expansion into concrete
 * per-view occurrences is left to the UI layer since it depends on the currently visible date window.
 */
@Immutable
sealed interface CalendarUiState {
    data object Loading : CalendarUiState

    data object Locked : CalendarUiState

    @Immutable
    data class Unlocked(
        val entries: List<CalendarEntryWithTags>,
        val layers: List<CalendarLayer>,
        val availableTags: List<String>,
        val selectedTags: Set<String>,
        val viewMode: CalendarViewMode,
        val selectedDateMillis: Long
    ) : CalendarUiState
}
