package com.voxapps.expenses.state

import android.content.Context
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.SpendingLimit
import com.voxapps.expenses.data.preferences.ExpensesSettingsRepository
import com.voxapps.expenses.domain.limits.SpendingLimitAlertRepository
import com.voxapps.expenses.domain.llm.CategoryAutoMergeScheduler
import com.voxapps.expenses.domain.llm.CategoryMergeRequestSender
import com.voxapps.expenses.domain.llm.DuplicateGroup
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationRepository
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationRequestSender
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationScheduler
import com.voxapps.expenses.domain.llm.ExpenseSummary
import com.voxapps.expenses.domain.llm.PendingCategoryMergeRepository
import com.voxapps.expenses.domain.llm.PendingNotificationExpense
import com.voxapps.expenses.domain.llm.PendingNotificationExpenseRepository
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExpensesStateManager internal constructor(
    private val settingsRepo: ExpensesSettingsRepository,
    private val expensesRepo: ExpensesRepository,
    private val sessionManager: SessionManager,
    private val pendingCategoryMergeRepo: PendingCategoryMergeRepository,
    private val expenseDeduplicationRepo: ExpenseDeduplicationRepository,
    private val pendingNotificationExpenseRepo: PendingNotificationExpenseRepository,
    private val spendingLimitAlertRepo: SpendingLimitAlertRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class Runtime(
        val selectedCategoryId: Long? = null,
        val sort: SortMode = SortMode.NEWEST,
        val dateFrom: Long? = null,
        val dateTo: Long? = null,
        val selectedBank: String? = null,
        val selectedVendor: String? = null,
        val sessionTick: Int = 0
    )

    private val _runtime = MutableStateFlow(Runtime())

    private val _uiState = MutableStateFlow<ExpensesUiState>(ExpensesUiState.Loading)
    val uiState: StateFlow<ExpensesUiState> = _uiState.asStateFlow()

    init {
        combine(
            settingsRepo.settingsFlow,
            expensesRepo.expensesWithDetails,
            expensesRepo.categories,
            _runtime
        ) { settings, expenses, categories, rt ->
            val locked = settings.isBiometricRequired &&
                !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
            if (locked) {
                ExpensesUiState.Locked
            } else {
                ExpensesUiState.Unlocked(
                    expenses = ExpenseFilter.apply(
                        expenses, rt.selectedCategoryId, rt.dateFrom, rt.dateTo, rt.selectedBank, rt.selectedVendor, rt.sort
                    ),
                    categories = categories,
                    selectedCategoryId = rt.selectedCategoryId,
                    sort = rt.sort,
                    dateFrom = rt.dateFrom,
                    dateTo = rt.dateTo,
                    selectedBank = rt.selectedBank,
                    selectedVendor = rt.selectedVendor,
                    availableBanks = expenses.mapNotNull { it.expense.bank }.distinct().sorted(),
                    availableVendors = expenses.mapNotNull { it.expense.vendor }.distinct().sorted()
                )
            }
        }.onEach { _uiState.value = it }.launchIn(scope)
    }

    fun setCategoryFilter(categoryId: Long?) = _runtime.update { it.copy(selectedCategoryId = categoryId) }
    fun setSort(sort: SortMode) = _runtime.update { it.copy(sort = sort) }
    fun setDateFilter(from: Long?, to: Long?) = _runtime.update { it.copy(dateFrom = from, dateTo = to) }
    fun clearDateFilter() = _runtime.update { it.copy(dateFrom = null, dateTo = null) }
    fun setBankFilter(bank: String?) = _runtime.update { it.copy(selectedBank = bank) }
    fun setVendorFilter(vendor: String?) = _runtime.update { it.copy(selectedVendor = vendor) }

    fun setBiometricRequired(required: Boolean) { scope.launch { settingsRepo.setBiometricRequired(required) } }
    fun setSessionTimeoutMinutes(minutes: Int) { scope.launch { settingsRepo.setSessionTimeoutMinutes(minutes) } }
    fun setLanguage(code: String) { scope.launch { settingsRepo.setLanguage(code) } }
    fun setDefaultCurrency(code: String) { scope.launch { settingsRepo.setDefaultCurrency(code) } }
    fun setDefaultVoiceCategoryId(id: Long?) { scope.launch { settingsRepo.setDefaultVoiceCategoryId(id) } }
    fun setVoiceSaveToastEnabled(enabled: Boolean) { scope.launch { settingsRepo.setVoiceSaveToastEnabled(enabled) } }
    fun setAutoCreateVoiceCategory(enabled: Boolean) { scope.launch { settingsRepo.setAutoCreateVoiceCategory(enabled) } }
    fun setScheduledMergeInterval(context: Context, interval: String) {
        scope.launch { settingsRepo.setScheduledMergeInterval(interval) }
        CategoryAutoMergeScheduler.reschedule(context, interval)
    }
    fun setScheduledExpenseDedupInterval(context: Context, interval: String) {
        scope.launch { settingsRepo.setScheduledExpenseDedupInterval(interval) }
        ExpenseDeduplicationScheduler.reschedule(context, interval)
    }
    fun setHomeCurrency(code: String) { scope.launch { settingsRepo.setHomeCurrency(code) } }
    fun setPaymentSourcePackages(packages: Set<String>) { scope.launch { settingsRepo.setPaymentSourcePackages(packages) } }
    fun setBankingSourcePackages(packages: Set<String>) { scope.launch { settingsRepo.setBankingSourcePackages(packages) } }
    fun setAutoAcceptNotificationExpenses(enabled: Boolean) { scope.launch { settingsRepo.setAutoAcceptNotificationExpenses(enabled) } }
    fun setDebugLoggingEnabled(enabled: Boolean) {
        Logger.setEnabled(enabled)
        scope.launch { settingsRepo.setDebugLoggingEnabled(enabled) }
    }
    fun setVatDisplayEnabled(enabled: Boolean) { scope.launch { settingsRepo.setVatDisplayEnabled(enabled) } }
    fun setDecimalSeparator(separator: String) { scope.launch { settingsRepo.setDecimalSeparator(separator) } }
    fun setCalendarViewEnabled(enabled: Boolean) { scope.launch { settingsRepo.setCalendarViewEnabled(enabled) } }
    fun setDebugToastsEnabled(enabled: Boolean) { scope.launch { settingsRepo.setDebugToastsEnabled(enabled) } }
    fun setAttachPhotoOnScan(enabled: Boolean) { scope.launch { settingsRepo.setAttachPhotoOnScan(enabled) } }
    fun setAttachPhotoOnRetry(enabled: Boolean) { scope.launch { settingsRepo.setAttachPhotoOnRetry(enabled) } }
    fun setThemeDarkMode(mode: String) { scope.launch { settingsRepo.setThemeDarkMode(mode) } }
    fun setThemeColored(colored: Boolean) { scope.launch { settingsRepo.setThemeColored(colored) } }
    fun setOnboardingCompleted(completed: Boolean) { scope.launch { settingsRepo.setOnboardingCompleted(completed) } }
    fun seedDebugTestData() {
        scope.launch {
            com.voxapps.expenses.domain.debug.DebugDataSeeder.seed(expensesRepo, settingsRepo.getSnapshot().defaultCurrency)
        }
    }

    fun unlock() {
        sessionManager.markUnlocked()
        bumpSession()
    }

    fun lock() {
        sessionManager.lock()
        bumpSession()
    }

    fun recheckLock() = bumpSession()

    private fun bumpSession() = _runtime.update { it.copy(sessionTick = it.sessionTick + 1) }

    fun addExpense(
        title: String?,
        totalAmount: Double,
        currencyCode: String,
        vendor: String?,
        bank: String?,
        location: String?,
        dateTime: Long,
        comments: String?,
        categoryId: Long?,
        items: List<ExpenseLineItem>,
        imageName: String? = null,
        isStub: Boolean = false
    ) {
        scope.launch {
            expensesRepo.addExpense(
                title, totalAmount, currencyCode, vendor, bank, location, dateTime, comments, categoryId, items, imageName, isStub
            )
        }
    }

    fun updateExpense(expense: Expense, items: List<ExpenseLineItem>) {
        scope.launch { expensesRepo.updateExpense(expense, items) }
    }

    fun deleteExpense(expense: Expense) { scope.launch { expensesRepo.deleteExpense(expense) } }
    fun deleteExpenseById(id: Long) { scope.launch { expensesRepo.deleteExpenseById(id) } }

    fun deleteAllExpenses() {
        scope.launch { expensesRepo.deleteAllExpenses() }
    }

    fun addCategory(name: String, colorArgb: Long) {
        val position = (uiStateCategories()).size
        scope.launch { expensesRepo.addCategory(name, colorArgb, position, System.currentTimeMillis()) }
    }

    fun updateCategory(category: Category) { scope.launch { expensesRepo.updateCategory(category) } }

    fun removeCategory(category: Category) {
        scope.launch {
            expensesRepo.deleteCategory(category)
            if (_runtime.value.selectedCategoryId == category.id) setCategoryFilter(null)
        }
    }

    private fun uiStateCategories(): List<Category> =
        (_uiState.value as? ExpensesUiState.Unlocked)?.categories ?: emptyList()

    fun requestCategoryAutoMerge(context: Context, categoryNames: List<String>) {
        val language = settingsRepo.getSnapshot().language
        CategoryMergeRequestSender.send(context, categoryNames, language)
    }

    val pendingCategoryMergeMapping: Flow<Map<String, String>> = pendingCategoryMergeRepo.pendingMappingFlow

    fun approveCategoryMerge(mapping: Map<String, String>) {
        scope.launch {
            expensesRepo.applyCategoryMerge(mapping)
            pendingCategoryMergeRepo.clearPendingMapping()
        }
    }

    fun dismissCategoryMerge() {
        scope.launch { pendingCategoryMergeRepo.clearPendingMapping() }
    }

    fun requestExpenseDeduplication(context: Context) {
        scope.launch {
            val expenses = expensesRepo.expenses.first().map {
                ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime)
            }
            ExpenseDeduplicationRequestSender.send(context, expenses)
        }
    }

    val pendingExpenseDuplicateGroups: Flow<List<DuplicateGroup>> = expenseDeduplicationRepo.pendingGroupsFlow

    fun approveExpenseDeduplication(groups: List<DuplicateGroup>) {
        scope.launch {
            expensesRepo.applyExpenseDeduplication(groups)
            expenseDeduplicationRepo.clearPendingGroups()
        }
    }

    fun dismissExpenseDeduplication() {
        scope.launch { expenseDeduplicationRepo.clearPendingGroups() }
    }

    val pendingNotificationExpenses: Flow<List<PendingNotificationExpense>> = pendingNotificationExpenseRepo.pendingFlow

    fun approveNotificationExpense(entry: PendingNotificationExpense) {
        scope.launch {
            val settings = settingsRepo.getSnapshot()
            expensesRepo.addParsedExpense(
                title = entry.title,
                totalAmount = entry.totalAmount,
                currencyCode = entry.currency,
                vendor = entry.vendor,
                bank = entry.bank,
                location = null,
                comments = null,
                dateTime = entry.capturedAt,
                spokenCategory = entry.category,
                defaultCategoryId = settings.defaultVoiceCategoryId,
                autoCreate = settings.autoCreateVoiceCategory
            )
            pendingNotificationExpenseRepo.removePending(setOf(entry.id))
        }
    }

    fun dismissNotificationExpense(id: Long) {
        scope.launch { pendingNotificationExpenseRepo.removePending(setOf(id)) }
    }

    fun dismissAllNotificationExpenses() {
        scope.launch { pendingNotificationExpenseRepo.clearAll() }
    }

    val spendingLimits: Flow<List<SpendingLimit>> = expensesRepo.spendingLimits

    fun addSpendingLimit(categoryId: Long?, amountHomeCurrency: Double, period: String) {
        scope.launch { expensesRepo.addSpendingLimit(categoryId, amountHomeCurrency, period) }
    }

    fun deleteSpendingLimit(limit: SpendingLimit) {
        scope.launch {
            expensesRepo.deleteSpendingLimit(limit)
            spendingLimitAlertRepo.forget(limit.id)
        }
    }

    companion object {
        @Volatile private var instance: ExpensesStateManager? = null

        fun getInstance(
            settingsRepo: ExpensesSettingsRepository,
            expensesRepo: ExpensesRepository,
            sessionManager: SessionManager,
            pendingCategoryMergeRepo: PendingCategoryMergeRepository,
            expenseDeduplicationRepo: ExpenseDeduplicationRepository,
            pendingNotificationExpenseRepo: PendingNotificationExpenseRepository,
            spendingLimitAlertRepo: SpendingLimitAlertRepository
        ): ExpensesStateManager = instance ?: synchronized(this) {
            instance ?: ExpensesStateManager(
                settingsRepo, expensesRepo, sessionManager, pendingCategoryMergeRepo, expenseDeduplicationRepo,
                pendingNotificationExpenseRepo, spendingLimitAlertRepo
            ).also { instance = it }
        }
    }
}
