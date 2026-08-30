package com.voxapps.expenses.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.voxapps.design.VoxDarkMode
import com.voxapps.design.VoxTheme
import com.voxapps.design.effects.TodayEffect
import com.voxapps.design.effects.TodayEffectStyle
import com.voxapps.design.toEnumOr
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.expenses.state.ExpensesUiState
import com.voxapps.expenses.ui.onboarding.ExpensesOnboardingFlow
import com.voxapps.expenses.ui.settings.SettingsPage
import com.voxapps.expenses.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

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
    onUnlockRequest: () -> Unit,
    // Incremented (not a plain Boolean) by ExpensesActivity whenever a new "quick add" launch
    // arrives — from onCreate's initial intent AND from onNewIntent if the activity was already
    // running — so a second widget tap while the app is already open still re-triggers even though
    // a Boolean would look "unchanged". 0 = no pending request (the default, normal launch).
    quickAddTrigger: Int = 0,
    // Same shape as quickAddTrigger, for ExpensesWidget's tap-a-record-to-edit rows (and the
    // existing Calendar day-tap-through deep link, which shares the same EXTRA_EXPENSE_ID):
    // editExpenseId is the target expense's id, editExpenseTrigger is the counter that forces
    // re-firing even when the same expense is tapped twice in a row.
    editExpenseId: Long = -1L,
    editExpenseTrigger: Int = 0
) {
    val settings by container.settingsRepository.settingsFlow.collectAsStateWithLifecycle(
        initialValue = ExpensesSettings()
    )
    CompositionLocalProvider(LocalLanguageManager provides container.languageManager) {
        VoxTheme(
            darkMode = when (settings.themeDarkMode) {
                ExpensesSettings.THEME_LIGHT -> VoxDarkMode.LIGHT
                ExpensesSettings.THEME_DARK -> VoxDarkMode.DARK
                else -> VoxDarkMode.SYSTEM
            },
            colored = settings.themeColored
        ) {
            if (!settings.onboardingCompleted) {
                ExpensesOnboardingFlow(
                    languageManager = container.languageManager,
                    stateManager = container.expensesStateManager
                )
            } else {
                val ui by container.expensesStateManager.uiState.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }
                var settingsStartPage by remember { mutableStateOf<SettingsPage?>(null) }
                var showReports by remember { mutableStateOf(false) }
                var showArchive by remember { mutableStateOf(false) }
                var editTarget by remember { mutableStateOf<EditTarget?>(null) }

                // Widget "Add" tap — set even while locked; once ExpensesUiState transitions to
                // Unlocked the `when` below picks ExpenseEditScreen up automatically, no extra wiring.
                LaunchedEffect(quickAddTrigger) {
                    if (quickAddTrigger > 0) editTarget = EditTarget.New
                }

                // Widget row tap / Calendar day-tap-through deep link — waits for the first Unlocked
                // state (immediately if already unlocked, or after the user authenticates if not)
                // rather than a one-shot snapshot, so tapping while locked still opens it once unlocked.
                LaunchedEffect(editExpenseTrigger) {
                    if (editExpenseTrigger > 0 && editExpenseId >= 0) {
                        container.expensesStateManager.uiState
                            .filterIsInstance<ExpensesUiState.Unlocked>()
                            .first()
                        val expense = container.expensesRepository.expensesWithDetails.first()
                            .firstOrNull { it.expense.id == editExpenseId }
                        if (expense != null) {
                            editTarget = EditTarget.Existing(expense)
                        }
                    }
                }

                when (val state = ui) {
                    is ExpensesUiState.Loading -> Unit
                    is ExpensesUiState.Locked -> AuthGate(onUnlockRequest = onUnlockRequest)
                    is ExpensesUiState.Unlocked -> {
                        val target = editTarget
                        // The rows live here, not in the state: the paged window feeds the
                        // scrolling list, the whole snapshot feeds the screens that hold a list
                        // (reports, calendar layout, select-all) — and both stop the moment this
                        // composition does. Null until the first snapshot lands, so an empty
                        // ledger and a not-yet-answered query read differently.
                        val pagedExpenses = container.expensesStateManager.pagedExpenses
                            .collectAsLazyPagingItems()
                        val expenseSnapshot by container.expensesStateManager.filteredExpenses
                            .collectAsStateWithLifecycle(initialValue = null)
                        // Fetched fresh rather than derived from state.categories/state.expenses
                        // (which reflect any active filter) — the "+ New category..." color
                        // suggestion needs the true, unfiltered most-recent expense.
                        var mostRecentCategoryColor by remember(target) { mutableStateOf<Long?>(null) }
                        LaunchedEffect(target) {
                            if (target != null) mostRecentCategoryColor = container.expensesRepository.mostRecentCategoryColor()
                        }
                        when {
                            // key(): a widget deep-link can swap the target WHILE an editor is
                            // open — without a fresh composition the old screen's remembered field
                            // states silently point at the new record, and a save would write one
                            // record's fields onto another. Switching targets now builds a new
                            // editor seeded from the tapped record; the abandoned one's unsaved
                            // keystrokes are discarded, never cross-written.
                            target != null -> androidx.compose.runtime.key((target as? EditTarget.Existing)?.expense?.expense?.id ?: -1L) {
                                // Whether this particular record has a breakdown at all — presence
                                // only, never derived. See VatDisplay.
                                val edited = (target as? EditTarget.Existing)?.expense
                                val recordCarriesVat = com.voxapps.expenses.domain.llm.VatDisplay.carriesVat(
                                    netAmount = edited?.expense?.netAmount,
                                    vatAmount = edited?.expense?.vatAmount,
                                    itemVatAmounts = edited?.items.orEmpty().map { it.vatAmount }
                                )
                                ExpenseEditScreen(
                                existing = (target as? EditTarget.Existing)?.expense,
                                categories = state.categories,
                                defaultCurrency = container.settingsRepository.getSnapshot().defaultCurrency,
                                vatDisplayEnabled = com.voxapps.expenses.domain.llm.VatDisplay.shows(
                                    container.settingsRepository.getSnapshot().vatDisplay,
                                    recordCarriesVat
                                ),
                                vatFoundButHidden = com.voxapps.expenses.domain.llm.VatDisplay.offersToShow(
                                    container.settingsRepository.getSnapshot().vatDisplay,
                                    recordCarriesVat
                                ),
                                decimalSeparator = container.settingsRepository.getSnapshot().decimalSeparator,
                                locationPrefillEnabled = container.settingsRepository.getSnapshot().locationPrefillEnabled,
                                settingsRepository = container.settingsRepository,
                                mostRecentCategoryColor = mostRecentCategoryColor,
                                stateManager = container.expensesStateManager,
                                onDone = { editTarget = null }
                            ) }
                            showSettings -> SettingsScreen(
                                stateManager = container.expensesStateManager,
                                settingsRepo = container.settingsRepository,
                                exchangeRateRepository = container.exchangeRateRepository,
                                onBack = { showSettings = false; settingsStartPage = null },
                                startPage = settingsStartPage
                            )
                            showArchive -> ArchiveScreen(
                                stateManager = container.expensesStateManager,
                                retentionDays = settings.archiveRetentionDays,
                                onBack = { showArchive = false }
                            )
                            showReports -> ReportsScreen(
                                expenses = expenseSnapshot.orEmpty(),
                                state = state,
                                stateManager = container.expensesStateManager,
                                homeCurrency = container.settingsRepository.getSnapshot().homeCurrency,
                                exchangeRateRepository = container.exchangeRateRepository,
                                onBack = { showReports = false }
                            )
                            else -> ExpensesScreen(
                                state = state,
                                expenses = expenseSnapshot,
                                paged = pagedExpenses,
                                stateManager = container.expensesStateManager,
                                calendarViewEnabled = settings.calendarViewEnabled,
                                language = settings.language,
                                onAddExpense = { editTarget = EditTarget.New },
                                onEditExpense = { editTarget = EditTarget.Existing(it) },
                                onOpenSettings = { showSettings = true },
                                onOpenSettingsAt = { page -> settingsStartPage = page; showSettings = true },
                                onOpenReports = { showReports = true },
                                onOpenArchive = { showArchive = true },
                                todayEffect = settings.todayEffect.toEnumOr(TodayEffect.NONE),
                                todayEffectStyle = settings.todayEffectStyle.toEnumOr(TodayEffectStyle.RING),
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
