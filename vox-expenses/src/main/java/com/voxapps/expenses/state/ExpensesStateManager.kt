package com.voxapps.expenses.state

import android.content.Context
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.SpendingLimit
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.ExpensesSettings
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
import com.voxapps.ipc.VoxLlmRequestQueue
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
import java.util.concurrent.TimeUnit
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
    private val spendingLimitAlertRepo: SpendingLimitAlertRepository,
    private val pendingLlmRequestQueue: VoxLlmRequestQueue
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
    fun setLocationPrefillEnabled(enabled: Boolean) { scope.launch { settingsRepo.setLocationPrefillEnabled(enabled) } }
    fun setDuplicateCheckModeManual(mode: String) { scope.launch { settingsRepo.setDuplicateCheckModeManual(mode) } }
    fun setDuplicateCheckModeAutomatic(mode: String) { scope.launch { settingsRepo.setDuplicateCheckModeAutomatic(mode) } }
    fun setAutoAcceptDuplicateMerges(enabled: Boolean) { scope.launch { settingsRepo.setAutoAcceptDuplicateMerges(enabled) } }
    fun setNearDuplicateFuzzyMatchEnabled(enabled: Boolean) { scope.launch { settingsRepo.setNearDuplicateFuzzyMatchEnabled(enabled) } }
    fun setNearDuplicateTimeWindowMinutes(minutes: Int) { scope.launch { settingsRepo.setNearDuplicateTimeWindowMinutes(minutes) } }
    fun setMerchantCategoryMemoryEnabled(enabled: Boolean) { scope.launch { settingsRepo.setMerchantCategoryMemoryEnabled(enabled) } }
    fun setMerchantCategoryMemoryThreshold(count: Int) { scope.launch { settingsRepo.setMerchantCategoryMemoryThreshold(count) } }
    fun setWidgetBorderEnabled(enabled: Boolean) { scope.launch { settingsRepo.setWidgetBorderEnabled(enabled) } }
    fun setWidgetBorderThicknessDp(thicknessDp: Int) { scope.launch { settingsRepo.setWidgetBorderThicknessDp(thicknessDp) } }
    fun setWidgetBorderColorArgb(colorArgb: Long) { scope.launch { settingsRepo.setWidgetBorderColorArgb(colorArgb) } }

    /** Gate lives here (not in the repository) — mirrors the "repository has zero settings
     *  dependency" convention; [ExpensesRepository.recordManualCategoryChange] itself is
     *  unconditional. */
    fun recordManualCategoryChange(vendor: String?, categoryId: Long?) {
        if (!settingsRepo.getSnapshot().merchantCategoryMemoryEnabled) return
        scope.launch { expensesRepo.recordManualCategoryChange(vendor, categoryId) }
    }
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
        isStub: Boolean = false,
        direction: TransactionDirection = TransactionDirection.OUTGOING,
        // -1L is ExpensesRepository.addExpense's existing sentinel for "not inserted" (a genuine DB
        // failure OR a detected duplicate) — the UI layer can't tell which without this callback,
        // since the call itself is otherwise fire-and-forget. Defaults to a no-op for every existing
        // caller that doesn't care.
        onResult: (Long) -> Unit = {},
        // Only needed to fire the scoped AI duplicate check below (MODE_LOCAL_AND_AI/MODE_AI) — null
        // for callers that don't have one handy (e.g. debug seeding), which simply skips that check.
        context: Context? = null
    ) {
        scope.launch {
            // Resolved here rather than pushed onto every caller — near-duplicate detection is a
            // standing preference, not something each call site should have to know or forward.
            val settings = settingsRepo.getSnapshot()
            val id = expensesRepo.addExpense(
                title, totalAmount, currencyCode, vendor, bank, location, dateTime, comments, categoryId, items, imageName, isStub,
                direction = direction,
                nearDuplicateCheckEnabled = settings.duplicateCheckModeAutomatic != ExpensesSettings.MODE_AI,
                nearDuplicateFuzzyMatch = settings.nearDuplicateFuzzyMatchEnabled,
                nearDuplicateTimeWindowMillis = TimeUnit.MINUTES.toMillis(settings.nearDuplicateTimeWindowMinutes.toLong())
            )
            onResult(id)
            maybeRequestScopedDuplicateCheck(context, settings.duplicateCheckModeAutomatic, id)
        }
    }

    /** Fires an async, scoped AI duplicate check for a freshly-inserted row when the automatic mode
     *  includes AI ([ExpensesSettings.MODE_LOCAL_AND_AI]/[ExpensesSettings.MODE_AI]) — scoped to just
     *  the new row's own same-amount candidate cluster (see
     *  [ExpensesRepository.duplicateCandidateClusters]), not the whole expense list, so this is cheap
     *  and only fires when there's already a plausible peer to compare against. Never auto-applies —
     *  any result lands in the normal review list via [LlmResultReceiver], same as every other AI
     *  suggestion. No-ops silently if [context] is null, [newExpenseId] isn't a real inserted row
     *  (negative sentinel — a rejected/merged duplicate has nothing new to check), or the mode is
     *  Local-only. */
    private fun maybeRequestScopedDuplicateCheck(context: Context?, automaticMode: String, newExpenseId: Long) {
        if (context == null || newExpenseId <= 0 || automaticMode == ExpensesSettings.MODE_LOCAL) return
        scope.launch {
            val candidates = expensesRepo.duplicateCandidateClusters(scopedToId = newExpenseId).flatten()
            if (candidates.size < 2) return@launch
            val summaries = candidates.map {
                ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime)
            }
            ExpenseDeduplicationRequestSender.send(context, pendingLlmRequestQueue, summaries, scoped = true)
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

    fun addCategory(name: String, colorArgb: Long, onResult: (Long) -> Unit = {}) {
        val position = (uiStateCategories()).size
        scope.launch {
            val id = expensesRepo.addCategory(name, colorArgb, position, System.currentTimeMillis())
            onResult(id)
        }
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
        scope.launch { CategoryMergeRequestSender.send(context, pendingLlmRequestQueue, categoryNames, language) }
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

    /** Runs whichever engine(s) [ExpensesSettings.duplicateCheckModeManual] selects — Local
     *  completes synchronously (no Commander dependency) and stages results immediately; the two
     *  AI-involving modes send an async request whose reply lands later via [LlmResultReceiver].
     *  [ExpensesSettings.MODE_LOCAL_AND_AI] narrows what's sent to the AI down to same-amount
     *  candidate clusters (see [ExpensesRepository.duplicateCandidateClusters]) instead of the
     *  entire expense list — both cheaper and far less prone to the AI hallucinating a "duplicate"
     *  that shares nothing in common with the row it's supposedly matching. */
    fun requestDuplicateCheck(context: Context) {
        scope.launch {
            val settings = settingsRepo.getSnapshot()
            when (settings.duplicateCheckModeManual) {
                ExpensesSettings.MODE_LOCAL -> {
                    val groups = expensesRepo.findLocalDuplicateGroups(
                        settings.nearDuplicateFuzzyMatchEnabled,
                        TimeUnit.MINUTES.toMillis(settings.nearDuplicateTimeWindowMinutes.toLong())
                    )
                    expenseDeduplicationRepo.mergePendingGroups(groups)
                }
                ExpensesSettings.MODE_LOCAL_AND_AI -> {
                    val candidates = expensesRepo.duplicateCandidateClusters().flatten().map {
                        ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime)
                    }
                    if (candidates.isNotEmpty()) {
                        ExpenseDeduplicationRequestSender.send(context, pendingLlmRequestQueue, candidates)
                    }
                }
                else -> {
                    val all = expensesRepo.expenses.first().map {
                        ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime)
                    }
                    ExpenseDeduplicationRequestSender.send(context, pendingLlmRequestQueue, all)
                }
            }
        }
    }

    val pendingExpenseDuplicateGroups: Flow<List<DuplicateGroup>> = expenseDeduplicationRepo.pendingGroupsFlow

    fun approveExpenseDeduplication(groups: List<DuplicateGroup>) {
        scope.launch {
            expensesRepo.applyExpenseDeduplication(groups)
            // Only the groups just applied should disappear — clearing everything here used to
            // silently drop any unchecked/unapplied suggestion too (the actual "no way to merge" bug).
            expenseDeduplicationRepo.removeGroups(groups)
        }
    }

    fun dismissExpenseDuplicateGroup(group: DuplicateGroup) {
        scope.launch { expenseDeduplicationRepo.removeGroups(listOf(group)) }
    }

    fun dismissExpenseDeduplication() {
        scope.launch { expenseDeduplicationRepo.clearPendingGroups() }
    }

    val pendingNotificationExpenses: Flow<List<PendingNotificationExpense>> = pendingNotificationExpenseRepo.pendingFlow

    fun approveNotificationExpense(entry: PendingNotificationExpense, context: Context? = null) {
        scope.launch {
            val settings = settingsRepo.getSnapshot()
            val id = expensesRepo.addParsedExpense(
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
                autoCreate = settings.autoCreateVoiceCategory,
                direction = entry.direction,
                nearDuplicateCheckEnabled = settings.duplicateCheckModeAutomatic != ExpensesSettings.MODE_AI,
                nearDuplicateFuzzyMatch = settings.nearDuplicateFuzzyMatchEnabled,
                nearDuplicateTimeWindowMillis = TimeUnit.MINUTES.toMillis(settings.nearDuplicateTimeWindowMinutes.toLong()),
                merchantMemoryEnabled = settings.merchantCategoryMemoryEnabled,
                merchantMemoryThreshold = settings.merchantCategoryMemoryThreshold
            )
            pendingNotificationExpenseRepo.removePending(setOf(entry.id))
            maybeRequestScopedDuplicateCheck(context, settings.duplicateCheckModeAutomatic, id)
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
            spendingLimitAlertRepo: SpendingLimitAlertRepository,
            pendingLlmRequestQueue: VoxLlmRequestQueue
        ): ExpensesStateManager = instance ?: synchronized(this) {
            instance ?: ExpensesStateManager(
                settingsRepo, expensesRepo, sessionManager, pendingCategoryMergeRepo, expenseDeduplicationRepo,
                pendingNotificationExpenseRepo, spendingLimitAlertRepo, pendingLlmRequestQueue
            ).also { instance = it }
        }
    }
}
