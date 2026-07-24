package com.voxapps.expenses.di

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.voxapps.expenses.data.ExchangeRateRepository
import com.voxapps.expenses.data.ExpensesDatabase
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepositoryImpl
import com.voxapps.expenses.domain.apps.LauncherAppsCache
import com.voxapps.expenses.domain.limits.SpendingLimitAlertRepository
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationRepository
import com.voxapps.expenses.domain.llm.PendingCategoryMergeRepository
import com.voxapps.expenses.domain.llm.PendingNotificationExpenseRepository
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.state.SessionManager
import com.voxapps.expenses.ui.widget.ExpensesWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Manual DI container for Vox Expenses (mirrors vox-notes' NotesContainer). Owns all singletons and
 * is constructed once from [com.voxapps.expenses.ExpensesApplication.onCreate].
 */
class ExpensesContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: ExpensesSettingsRepository = ExpensesSettingsRepositoryImpl(appContext)

    private val database = ExpensesDatabase.get(appContext)
    val expensesRepository = ExpensesRepository(
        database.expenseDao(),
        database.categoryDao(),
        database.expenseLineItemDao(),
        database.spendingLimitDao(),
        database.merchantCategoryMemoryDao(),
        appContext
    )

    val pendingCategoryMergeRepository = PendingCategoryMergeRepository(appContext)
    val expenseDeduplicationRepository = ExpenseDeduplicationRepository(appContext)
    val pendingNotificationExpenseRepository = PendingNotificationExpenseRepository(appContext)
    val exchangeRateRepository = ExchangeRateRepository(appContext)
    val spendingLimitAlertRepository = SpendingLimitAlertRepository(appContext)

    val sessionManager = SessionManager()

    val expensesStateManager = ExpensesStateManager.getInstance(
        settingsRepository,
        expensesRepository,
        sessionManager,
        pendingCategoryMergeRepository,
        expenseDeduplicationRepository,
        pendingNotificationExpenseRepository,
        spendingLimitAlertRepository
    )

    val languageManager = LanguageManager(appContext).also {
        it.loadLanguage(settingsRepository.getSnapshot().language)
    }

    init {
        // Keeps ExpensesWidget's home-screen snapshot fresh — reacts to both lock-state transitions
        // (uiState) and data changes (expensesWithDetails), since the widget reads both independently
        // of any in-app filter (see ExpensesWidget.kt's provideGlance doc comment).
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            combine(expensesStateManager.uiState, expensesRepository.expensesWithDetails) { _, _ -> }
                .collect { ExpensesWidget().updateAll(appContext) }
        }

        // Warm the launcher-apps cache before any UI composes (mirrors vox-commander's AppContainer
        // loading AppRegistry from cache in its init block). Unlike AppRegistry, this scan is a single
        // queryIntentActivities call with no per-app probing, so it's fast enough to run synchronously
        // here rather than needing a dedicated splash screen — only the persisted-cache write is async.
        val cachedJson = settingsRepository.getSnapshot().appCacheJson
        val loadedFromCache = LauncherAppsCache.loadFromCache(cachedJson)
        if (!loadedFromCache) {
            LauncherAppsCache.scan(appContext)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                settingsRepository.setAppCache(LauncherAppsCache.toJsonCache())
            }
        }
    }
}
