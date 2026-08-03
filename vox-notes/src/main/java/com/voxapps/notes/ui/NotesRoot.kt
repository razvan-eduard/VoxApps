package com.voxapps.notes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.di.NotesContainer
import com.voxapps.notes.state.NotesUiState
import com.voxapps.notes.ui.onboarding.NotesOnboardingFlow
import com.voxapps.notes.ui.settings.SettingsScreen

/**
 * Top-level composable: applies the shared theme, provides [LocalLanguageManager], observes
 * [NotesUiState], and switches between the lock screen (AuthGate), the notes screen, and settings.
 */
@Composable
fun NotesRoot(
    container: NotesContainer,
    onUnlockRequest: () -> Unit,
    // Incremented (not a plain Boolean) by NotesActivity whenever a new "quick add" launch arrives
    // — from onCreate's initial intent AND from onNewIntent if the activity was already running —
    // so a second widget tap while the app is already open still re-triggers even though a Boolean
    // would look "unchanged". 0 = no pending request (the default, normal launch). Forwarded to
    // NotesScreen, which owns the actual inline-editor state.
    quickAddTrigger: Int = 0,
    // Same shape as quickAddTrigger, for NotesWidget's tap-a-note-to-edit rows: editNoteId is the
    // tapped note's id, editNoteTrigger is the counter that forces re-firing even when the same
    // note is tapped twice in a row.
    editNoteId: Long = -1L,
    editNoteTrigger: Int = 0
) {
    val settings by container.settingsRepository.settingsFlow.collectAsStateWithLifecycle(
        initialValue = NotesSettings()
    )
    CompositionLocalProvider(LocalLanguageManager provides container.languageManager) {
        VoxTheme(
            darkMode = when (settings.themeDarkMode) {
                NotesSettings.THEME_LIGHT -> VoxDarkMode.LIGHT
                NotesSettings.THEME_DARK -> VoxDarkMode.DARK
                else -> VoxDarkMode.SYSTEM
            },
            colored = settings.themeColored
        ) {
            if (!settings.onboardingCompleted) {
                NotesOnboardingFlow(
                    languageManager = container.languageManager,
                    stateManager = container.notesStateManager
                )
            } else {
                val ui by container.notesStateManager.uiState.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }

                when (val state = ui) {
                    is NotesUiState.Loading -> Unit
                    is NotesUiState.Locked -> AuthGate(onUnlockRequest = onUnlockRequest)
                    is NotesUiState.Unlocked -> {
                        if (showSettings) {
                            SettingsScreen(
                                stateManager = container.notesStateManager,
                                settingsRepo = container.settingsRepository,
                                onBack = { showSettings = false }
                            )
                        } else {
                            NotesScreen(
                                state = state,
                                stateManager = container.notesStateManager,
                                settings = settings,
                                onOpenSettings = { showSettings = true },
                                quickAddTrigger = quickAddTrigger,
                                editNoteId = editNoteId,
                                editNoteTrigger = editNoteTrigger,
                                todayEffect = runCatching { TodayEffect.valueOf(settings.todayEffect) }.getOrDefault(TodayEffect.NONE),
                                todayEffectStyle = runCatching { TodayEffectStyle.valueOf(settings.todayEffectStyle) }.getOrDefault(TodayEffectStyle.RING),
                                todayEffectPrimaryColor = Color(settings.todayEffectColor.toInt()),
                                todayEffectSecondaryColor = settings.todayEffectColor2?.let { Color(it.toInt()) },
                                todayEffectSpeed = settings.todayEffectSpeed
                            )
                        }
                    }
                }
            }
        }
    }
}
