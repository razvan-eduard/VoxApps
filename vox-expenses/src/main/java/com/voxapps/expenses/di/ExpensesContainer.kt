package com.voxapps.expenses.di

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.voxapps.expenses.data.ExchangeRateRepository
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.data.ExpensesDatabase
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepositoryImpl
import com.voxapps.expenses.domain.apps.LauncherAppsCache
import com.voxapps.expenses.domain.limits.SpendingLimitAlertRepository
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationRepository
import com.voxapps.expenses.domain.llm.PendingCategoryMergeRepository
import com.voxapps.expenses.domain.llm.PendingNotificationExpenseRepository
import com.voxapps.expenses.domain.llm.ScanPreParseRepository
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.expenses.state.ExpensesStateManager
import com.voxapps.expenses.state.SessionManager
import com.voxapps.expenses.ui.widget.ExpensesWidget
import com.voxapps.ipc.VoxLlmRequestQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Manual DI container for Vox Expenses (mirrors vox-notes' NotesContainer). Owns all singletons and
 * is constructed once from [com.voxapps.expenses.ExpensesApplication.onCreate].
 */
class ExpensesContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: ExpensesSettingsRepository = ExpensesSettingsRepositoryImpl(appContext)

    private val database = ExpensesDatabase.get(appContext)
    val attachmentDao = database.attachmentDao()
    val duplicateRuleDao = database.duplicateRuleDao()
    val fieldCorrectionMemory = com.voxapps.fieldmemory.FieldCorrectionMemory(database.learnedFieldCorrectionDao())
    val recurringPaymentRepository = com.voxapps.expenses.domain.recurring.RecurringPaymentRepository(
        database.recurringPaymentDao()
    )

    val expensesRepository = ExpensesRepository(
        database.expenseDao(),
        database.categoryDao(),
        database.expenseLineItemDao(),
        database.spendingLimitDao(),
        database.remapRuleDao(),
        database.remapPatternSightingDao(),
        appContext,
        attachmentDao,
        duplicateRuleDao,
        fieldCorrectionMemory,
        recurringPaymentRepository
    )

    /** Proposals a record holds until someone accepts them — storage and lifecycle from
     *  :core:suggestions, meaning from [ExpenseSuggestionTarget]. */
    val suggestionStore = com.voxapps.suggestions.SuggestionStore(
        database.fieldSuggestionDao(),
        com.voxapps.expenses.data.ExpenseSuggestionTarget(expensesRepository)
    ).also { expensesRepository.suggestions = it }

    val pendingLlmRequestQueue = VoxLlmRequestQueue(database.pendingLlmRequestDao())

    val pendingCategoryMergeRepository = PendingCategoryMergeRepository(appContext)
    val expenseDeduplicationRepository = ExpenseDeduplicationRepository(appContext)
    val pendingNotificationExpenseRepository = PendingNotificationExpenseRepository(appContext)
    val scanPreParseRepository = ScanPreParseRepository(appContext)
    val templateDirectionMemory = com.voxapps.expenses.domain.llm.TemplateDirectionMemory(appContext)
    val exchangeRateRepository = ExchangeRateRepository(appContext)
    val spendingLimitAlertRepository = SpendingLimitAlertRepository(appContext)

    val sessionManager = SessionManager()

    val expensesStateManager = ExpensesStateManager(
        settingsRepository,
        expensesRepository,
        sessionManager,
        pendingCategoryMergeRepository,
        expenseDeduplicationRepository,
        pendingNotificationExpenseRepository,
        recurringPaymentRepository,
        spendingLimitAlertRepository,
        pendingLlmRequestQueue,
        templateDirectionMemory,
        attachmentDao,
        duplicateRuleDao,
        appContext
    )

    /**
     * What a caller over the IPC bus is told when this app is locked. Read through
     * [languageManager] because it is spoken and displayed by whoever asked — Commander's TTS, Hub's
     * backup screen — so it has to be in the language the user set, not the language it was written
     * in.
     */
    val lockedMessage: String get() = languageManager.getString("locked_message")

    val languageManager = LanguageManager(appContext).also {
        it.loadLanguage(settingsRepository.getSnapshot().language)
    }

    init {
        // Keeps ExpensesWidget's home-screen snapshot fresh — reacts to lock-state transitions
        // (uiState), data changes (expensesWithDetails, read independently of any in-app filter, see
        // ExpensesWidget.kt's provideGlance doc comment), AND settings (settingsFlow — border/
        // today-effect color, style, thickness etc. are all read fresh into the widget's content but
        // nothing about changing them touches expenses or uiState, so without watching settingsFlow
        // too, a settings-only change would sit un-reflected until the next unrelated data change,
        // the midnight worker, or the OS's 30-min update floor).
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            combine(
                expensesStateManager.uiState,
                expensesRepository.expensesWithDetails,
                settingsRepository.settingsFlow,
                // Expenses themselves don't change when an attachment is added/removed on one of
                // them, so the widget's paperclip indicator would otherwise only catch up on the
                // next unrelated refresh instead of promptly.
                attachmentDao.observeRecordIdsWithAttachments(ExpensesAttachments.RECORD_TYPE)
            // conflate(): each emission drives a Glance updateAll() — an IPC round-trip to the
            // launcher — and a bulk import or a P2P sync merge emits once per record, so the
            // widget would be redrawn N times to show one final state. Conflating drops the
            // intermediate values while an update is still in flight, keeping the newest, so the
            // refresh rate is bounded by how fast updateAll() completes rather than by how fast
            // rows are written. No debounce: nothing here is latency-sensitive enough to justify
            // delaying the common single-change case.
            ) { _, _, _, _ -> }.conflate().collect { ExpensesWidget().updateAll(appContext) }
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
