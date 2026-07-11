package com.voxapps.expenses.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.expenses.state.ExpensesUiState
import com.voxapps.expenses.ui.settings.SettingsScreen

private sealed interface EditTarget {
    data object New : EditTarget
    data class Existing(val expense: ExpenseWithDetails) : EditTarget
}

/**
 * Top-level composable: applies the shared theme, provides [LocalLanguageManager], observes
 * [ExpensesUiState], and switches between the lock screen (AuthGate), the expense list, the
 * add/edit screen, and settings (mirrors vox-notes' NotesRoot).
 */
@Composable
fun ExpensesRoot(
    container: ExpensesContainer,
    onUnlockRequest: () -> Unit
) {
    CompositionLocalProvider(LocalLanguageManager provides container.languageManager) {
        VoxTheme(darkMode = VoxDarkMode.SYSTEM, colored = true) {
            val ui by container.expensesStateManager.uiState.collectAsStateWithLifecycle()
            var showSettings by remember { mutableStateOf(false) }
            var showReports by remember { mutableStateOf(false) }
            var editTarget by remember { mutableStateOf<EditTarget?>(null) }

            when (val state = ui) {
                is ExpensesUiState.Loading -> Unit
                is ExpensesUiState.Locked -> AuthGate(onUnlockRequest = onUnlockRequest)
                is ExpensesUiState.Unlocked -> {
                    val target = editTarget
                    when {
                        target != null -> ExpenseEditScreen(
                            existing = (target as? EditTarget.Existing)?.expense,
                            categories = state.categories,
                            defaultCurrency = container.settingsRepository.getSnapshot().defaultCurrency,
                            vatDisplayEnabled = container.settingsRepository.getSnapshot().vatDisplayEnabled,
                            decimalSeparator = container.settingsRepository.getSnapshot().decimalSeparator,
                            stateManager = container.expensesStateManager,
                            onDone = { editTarget = null }
                        )
                        showSettings -> SettingsScreen(
                            stateManager = container.expensesStateManager,
                            settingsRepo = container.settingsRepository,
                            exchangeRateRepository = container.exchangeRateRepository,
                            onBack = { showSettings = false }
                        )
                        showReports -> ReportsScreen(
                            expenses = state.expenses,
                            homeCurrency = container.settingsRepository.getSnapshot().homeCurrency,
                            exchangeRateRepository = container.exchangeRateRepository,
                            onBack = { showReports = false }
                        )
                        else -> ExpensesScreen(
                            state = state,
                            stateManager = container.expensesStateManager,
                            onAddExpense = { editTarget = EditTarget.New },
                            onEditExpense = { editTarget = EditTarget.Existing(it) },
                            onOpenSettings = { showSettings = true },
                            onOpenReports = { showReports = true }
                        )
                    }
                }
            }
        }
    }
}
