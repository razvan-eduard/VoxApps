package com.voxapps.calendarapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.calendarapp.state.CalendarUiState
import com.voxapps.calendarapp.ui.settings.SettingsScreen
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme

private sealed interface EditTarget {
    data object New : EditTarget
    data class Existing(val entry: CalendarEntryWithTags) : EditTarget
}

/**
 * Top-level composable: applies the shared theme, provides [LocalLanguageManager], observes
 * [CalendarUiState], and switches between the lock screen (AuthGate), the calendar, the add/edit
 * screen, and settings (mirrors vox-expenses' ExpensesRoot).
 */
@Composable
fun CalendarRoot(
    container: CalendarContainer,
    onUnlockRequest: () -> Unit
) {
    CompositionLocalProvider(LocalLanguageManager provides container.languageManager) {
        VoxTheme(darkMode = VoxDarkMode.SYSTEM, colored = true) {
            val ui by container.calendarStateManager.uiState.collectAsStateWithLifecycle()
            var showSettings by remember { mutableStateOf(false) }
            var editTarget by remember { mutableStateOf<EditTarget?>(null) }

            when (val state = ui) {
                is CalendarUiState.Loading -> Unit
                is CalendarUiState.Locked -> AuthGate(onUnlockRequest = onUnlockRequest)
                is CalendarUiState.Unlocked -> {
                    val target = editTarget
                    val defaultLayer = state.layers.firstOrNull { it.isDefault } ?: state.layers.firstOrNull()
                    when {
                        target != null && defaultLayer != null -> EntryEditScreen(
                            existing = (target as? EditTarget.Existing)?.entry,
                            defaultLayer = defaultLayer,
                            stateManager = container.calendarStateManager,
                            onDone = { editTarget = null }
                        )
                        showSettings -> SettingsScreen(
                            stateManager = container.calendarStateManager,
                            settingsRepo = container.settingsRepository,
                            calendarRepository = container.calendarRepository,
                            onBack = { showSettings = false }
                        )
                        else -> CalendarScreen(
                            state = state,
                            language = container.settingsRepository.getSnapshot().language,
                            stateManager = container.calendarStateManager,
                            onAddEntry = { editTarget = EditTarget.New },
                            onEditEntry = { item -> editTarget = EditTarget.Existing(item.entryWithTags) },
                            onOpenSettings = { showSettings = true }
                        )
                    }
                }
            }
        }
    }
}
