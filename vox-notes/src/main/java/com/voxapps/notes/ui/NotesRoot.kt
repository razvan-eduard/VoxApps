package com.voxapps.notes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme
import com.voxapps.notes.di.NotesContainer
import com.voxapps.notes.state.NotesUiState
import com.voxapps.notes.ui.settings.SettingsScreen

/**
 * Top-level composable: applies the shared theme, observes [NotesUiState], and switches between the
 * lock screen (AuthGate), the notes screen, and settings.
 */
@Composable
fun NotesRoot(
    container: NotesContainer,
    onUnlockRequest: () -> Unit
) {
    VoxTheme(darkMode = VoxDarkMode.SYSTEM, colored = true) {
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
                        onOpenSettings = { showSettings = true }
                    )
                }
            }
        }
    }
}
