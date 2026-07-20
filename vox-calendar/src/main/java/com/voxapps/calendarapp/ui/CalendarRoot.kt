package com.voxapps.calendarapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.calendarapp.data.CalendarEntryWithTags
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.calendarapp.state.CalendarUiState
import com.voxapps.calendarapp.ui.onboarding.CalendarOnboardingFlow
import com.voxapps.calendarapp.ui.settings.SettingsScreen
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

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
    onUnlockRequest: () -> Unit,
    // Incremented (not a plain Boolean) by CalendarActivity whenever a new "quick add" launch
    // arrives — from onCreate's initial intent AND from onNewIntent if the activity was already
    // running — so a second widget tap while the app is already open still re-triggers even though
    // a Boolean would look "unchanged". 0 = no pending request (the default, normal launch).
    quickAddTrigger: Int = 0,
    // Same shape as quickAddTrigger, for CalendarWidget's tap-a-record-to-edit rows: editEntryId is
    // the tapped entry's id, editEntryTrigger is the counter that forces re-firing even when the
    // same record is tapped twice in a row (two equal ids wouldn't look like a state change).
    editEntryId: Long = -1L,
    editEntryTrigger: Int = 0
) {
    val settings by container.settingsRepository.settingsFlow.collectAsStateWithLifecycle(
        initialValue = CalendarSettings()
    )
    CompositionLocalProvider(LocalLanguageManager provides container.languageManager) {
        VoxTheme(
            darkMode = when (settings.themeDarkMode) {
                CalendarSettings.THEME_LIGHT -> VoxDarkMode.LIGHT
                CalendarSettings.THEME_DARK -> VoxDarkMode.DARK
                else -> VoxDarkMode.SYSTEM
            },
            colored = settings.themeColored
        ) {
            if (!settings.onboardingCompleted) {
                CalendarOnboardingFlow(
                    languageManager = container.languageManager,
                    stateManager = container.calendarStateManager
                )
            } else {
                val ui by container.calendarStateManager.uiState.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }
                var editTarget by remember { mutableStateOf<EditTarget?>(null) }

                // Widget "Add" tap — set even while locked; once CalendarUiState transitions to
                // Unlocked the `when` below picks EntryEditScreen up automatically, no extra wiring.
                LaunchedEffect(quickAddTrigger) {
                    if (quickAddTrigger > 0) editTarget = EditTarget.New
                }

                // Widget record-row tap — waits for the first Unlocked state (immediately if already
                // unlocked, or after the user authenticates if not) rather than a one-shot snapshot,
                // so tapping a record while locked still opens it once the user unlocks.
                LaunchedEffect(editEntryTrigger) {
                    if (editEntryTrigger > 0 && editEntryId >= 0) {
                        val unlocked = container.calendarStateManager.uiState
                            .filterIsInstance<CalendarUiState.Unlocked>()
                            .first()
                        unlocked.entries.firstOrNull { it.entry.id == editEntryId }?.let {
                            editTarget = EditTarget.Existing(it)
                        }
                    }
                }

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
}
