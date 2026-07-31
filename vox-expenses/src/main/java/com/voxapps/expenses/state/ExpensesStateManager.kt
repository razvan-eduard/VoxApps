package com.voxapps.expenses.state

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.AttachmentSource
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.DuplicateRuleDao
import com.voxapps.expenses.data.DuplicateRuleEntity
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseSource
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.SpendingLimit
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.NearDuplicateConfig
import com.voxapps.expenses.data.toNearDuplicateConfig
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
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
    private val pendingLlmRequestQueue: VoxLlmRequestQueue,
    private val attachmentDao: AttachmentDao,
    private val duplicateRuleDao: DuplicateRuleDao,
    appContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workManager = WorkManager.getInstance(appContext)

    private data class Runtime(
        val selectedCategoryId: Long? = null,
        val sort: SortMode = SortMode.NEWEST,
        val selectedDateMillis: Long = System.currentTimeMillis(),
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
        val nextRunMillisFlow = workManager.getWorkInfosForUniqueWorkFlow("expense_deduplication")
            .map { infoList ->
                infoList.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                    ?.nextScheduleTimeMillis
            }

        combine(
            settingsRepo.settingsFlow,
            expensesRepo.expensesWithDetails,
            expensesRepo.categories,
            _runtime,
            nextRunMillisFlow
        ) { settings, expenses, categories, rt, nextRunMillis ->
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
                    isGridView = settings.isGridView,
                    selectedDateMillis = rt.selectedDateMillis,
                    dateFrom = rt.dateFrom,
                    dateTo = rt.dateTo,
                    selectedBank = rt.selectedBank,
                    selectedVendor = rt.selectedVendor,
                    availableBanks = expenses.mapNotNull { it.expense.bank }.distinct().sorted(),
                    availableVendors = expenses.mapNotNull { it.expense.vendor }.distinct().sorted(),
                    nextScheduledDedupMillis = nextRunMillis
                )
            }
        }.onEach { _uiState.value = it }.launchIn(scope)
    }

    fun setCategoryFilter(categoryId: Long?) = _runtime.update { it.copy(selectedCategoryId = categoryId) }
    fun setSort(sort: SortMode) = _runtime.update { it.copy(sort = sort) }
    fun setSelectedDate(millis: Long) = _runtime.update { it.copy(selectedDateMillis = millis) }
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
    fun setIsGridView(enabled: Boolean) { scope.launch { settingsRepo.setIsGridView(enabled) } }
    fun setDebugToastsEnabled(enabled: Boolean) { scope.launch { settingsRepo.setDebugToastsEnabled(enabled) } }
    fun setAttachPhotoOnScan(enabled: Boolean) { scope.launch { settingsRepo.setAttachPhotoOnScan(enabled) } }
    fun setAttachPhotoOnRetry(enabled: Boolean) { scope.launch { settingsRepo.setAttachPhotoOnRetry(enabled) } }
    fun setAutoOpenScannedExpense(enabled: Boolean) { scope.launch { settingsRepo.setAutoOpenScannedExpense(enabled) } }
    fun setLocationPrefillEnabled(enabled: Boolean) { scope.launch { settingsRepo.setLocationPrefillEnabled(enabled) } }
    fun setDuplicateCheckModeManual(mode: String) { scope.launch { settingsRepo.setDuplicateCheckModeManual(mode) } }
    fun setDuplicateCheckModeAutomatic(mode: String) { scope.launch { settingsRepo.setDuplicateCheckModeAutomatic(mode) } }
    fun setAutoAcceptDuplicateMerges(enabled: Boolean) { scope.launch { settingsRepo.setAutoAcceptDuplicateMerges(enabled) } }
    fun setAutomaticProtectionReviewOnly(enabled: Boolean) { scope.launch { settingsRepo.setAutomaticProtectionReviewOnly(enabled) } }
    fun setNearDuplicateTimeWindowMinutes(minutes: Int) { scope.launch { settingsRepo.setNearDuplicateTimeWindowMinutes(minutes) } }
    fun setDuplicateRuleSetGlobalCombinator(combinator: String) { scope.launch { settingsRepo.setDuplicateRuleSetGlobalCombinator(combinator) } }

    // --- Duplicate rules (see DuplicateRuleEntity/DuplicateRuleDao) ---
    val duplicateRules: Flow<List<DuplicateRuleEntity>> = duplicateRuleDao.observeAll()
    fun upsertDuplicateRule(rule: DuplicateRuleEntity) { scope.launch { duplicateRuleDao.upsert(rule) } }
    fun deleteDuplicateRule(rule: DuplicateRuleEntity) { scope.launch { duplicateRuleDao.delete(rule) } }
    fun setDuplicateRuleEnabled(id: Long, enabled: Boolean) { scope.launch { duplicateRuleDao.setEnabled(id, enabled) } }
    fun setMerchantCategoryMemoryEnabled(enabled: Boolean) { scope.launch { settingsRepo.setMerchantCategoryMemoryEnabled(enabled) } }
    fun setMerchantCategoryMemoryThreshold(count: Int) { scope.launch { settingsRepo.setMerchantCategoryMemoryThreshold(count) } }
    fun setWidgetBorderEnabled(enabled: Boolean) { scope.launch { settingsRepo.setWidgetBorderEnabled(enabled) } }
    fun setWidgetBorderThicknessDp(thicknessDp: Int) { scope.launch { settingsRepo.setWidgetBorderThicknessDp(thicknessDp) } }
    fun setWidgetBorderColorArgb(colorArgb: Long) { scope.launch { settingsRepo.setWidgetBorderColorArgb(colorArgb) } }
    fun setTodayEffect(effect: String) { scope.launch { settingsRepo.setTodayEffect(effect) } }
    fun setTodayEffectStyle(style: String) { scope.launch { settingsRepo.setTodayEffectStyle(style) } }
    fun setTodayEffectColor(colorArgb: Long) { scope.launch { settingsRepo.setTodayEffectColor(colorArgb) } }
    fun setTodayEffectColor2(colorArgb: Long?) { scope.launch { settingsRepo.setTodayEffectColor2(colorArgb) } }
    fun setTodayEffectSpeed(speed: Float) { scope.launch { settingsRepo.setTodayEffectSpeed(speed) } }
    fun setTodayEffectShowInWidget(enabled: Boolean) { scope.launch { settingsRepo.setTodayEffectShowInWidget(enabled) } }

    fun setBatchCleanupManualReview(enabled: Boolean) { scope.launch { settingsRepo.setBatchCleanupManualReview(enabled) } }

    fun setNotificationsSystemDefault(enabled: Boolean) {
        scope.launch {
            settingsRepo.setNotificationsSystemDefault(enabled)
            if (!enabled) incrementNotificationChannelVersion()
        }
    }

    fun setNotificationsVibrationEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepo.setNotificationsVibrationEnabled(enabled)
            incrementNotificationChannelVersion()
        }
    }

    fun setNotificationsSoundUri(uri: String?) {
        scope.launch {
            settingsRepo.setNotificationsSoundUri(uri)
            incrementNotificationChannelVersion()
        }
    }

    fun setNotificationsVolume(volume: Int) {
        scope.launch {
            settingsRepo.setNotificationsVolume(volume)
        }
    }

    fun setNotificationsLength(length: String) {
        scope.launch {
            settingsRepo.setNotificationsLength(length)
            incrementNotificationChannelVersion()
        }
    }

    private suspend fun incrementNotificationChannelVersion() {
        val current = settingsRepo.getSnapshot().notificationsChannelVersion
        settingsRepo.setNotificationsChannelVersion(current + 1)
    }

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
            val localModeActive = settings.duplicateCheckModeAutomatic == ExpensesSettings.MODE_LOCAL ||
                settings.duplicateCheckModeAutomatic == ExpensesSettings.MODE_LOCAL_AND_AI
            // Review-only mode skips the silent check here entirely and instead evaluates the same
            // rules AFTER a normal insert (below) — both rows need to already exist for the review
            // list's Keep/Merge preview, unlike the silent path which never inserts the candidate.
            val silentMergeEnabled = localModeActive && !settings.automaticProtectionReviewOnly
            val nearDuplicateConfig = settings.toNearDuplicateConfig()
            val id = expensesRepo.addExpense(
                title, totalAmount, currencyCode, vendor, bank, location, dateTime, comments, categoryId, items, imageName, isStub,
                direction = direction,
                source = ExpenseSource.MANUAL,
                nearDuplicateCheckEnabled = silentMergeEnabled,
                nearDuplicateConfig = nearDuplicateConfig
            )
            onResult(id)
            if (localModeActive && settings.automaticProtectionReviewOnly && id > 0) {
                val group = expensesRepo.findLocalDuplicateGroupForRow(id, nearDuplicateConfig)
                if (group != null) expenseDeduplicationRepo.mergePendingGroups(listOf(group))
            }
            maybeRequestScopedDuplicateCheck(context, settings.duplicateCheckModeAutomatic, id, nearDuplicateConfig)
        }
    }

    /** Fires an async, scoped AI duplicate check for a freshly-inserted row when the automatic mode
     *  includes AI ([ExpensesSettings.MODE_LOCAL_AND_AI]/[ExpensesSettings.MODE_AI]) — scoped to just
     *  the new row's own candidate cluster, not the whole expense list, so this is cheap and only
     *  fires when there's already a plausible peer to compare against. [MODE_LOCAL_AND_AI] recalls that
     *  cluster via the configured duplicate rules ([ExpensesRepository.ruleBasedCandidateClusters],
     *  automatic-only); pure [MODE_AI] has no local component and recalls via a fixed amount/currency/
     *  direction match instead ([ExpensesRepository.duplicateCandidateClusters]), never consulting the
     *  rules at all. Never auto-applies — any result lands in the normal review list via
     *  [LlmResultReceiver], same as every other AI suggestion. No-ops silently if [context] is null,
     *  [newExpenseId] isn't a real inserted row (negative sentinel — a rejected/merged duplicate has
     *  nothing new to check), or the mode has no AI component ([ExpensesSettings.MODE_OFF]/
     *  [ExpensesSettings.MODE_LOCAL]). */
    private fun maybeRequestScopedDuplicateCheck(
        context: Context?,
        automaticMode: String,
        newExpenseId: Long,
        nearDuplicateConfig: NearDuplicateConfig
    ) {
        val hasAiComponent = automaticMode == ExpensesSettings.MODE_LOCAL_AND_AI || automaticMode == ExpensesSettings.MODE_AI
        if (context == null || newExpenseId <= 0 || !hasAiComponent) return
        scope.launch {
            val candidates = if (automaticMode == ExpensesSettings.MODE_LOCAL_AND_AI) {
                expensesRepo.ruleBasedCandidateClusters(nearDuplicateConfig, automaticOnly = true, scopedToId = newExpenseId).flatten()
            } else {
                expensesRepo.duplicateCandidateClusters(scopedToId = newExpenseId).flatten()
            }
            if (candidates.size < 2) return@launch
            val summaries = candidates.map {
                ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime, it.direction)
            }
            ExpenseDeduplicationRequestSender.send(context, pendingLlmRequestQueue, summaries, scoped = true)
        }
    }

    /** The only genuine manual-edit path — see [ExpensesRepository.updateExpense]'s
     *  [markManuallyEdited] doc for why this always passes true, unlike [LlmResultReceiver]'s
     *  LLM-driven retry-cleanup update. */
    fun updateExpense(expense: Expense, items: List<ExpenseLineItem>) {
        scope.launch { expensesRepo.updateExpense(expense, items, markManuallyEdited = true) }
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
            val autoApply = !settings.batchCleanupManualReview
            when (settings.duplicateCheckModeManual) {
                ExpensesSettings.MODE_LOCAL -> {
                    val groups = expensesRepo.findLocalDuplicateGroups(settings.toNearDuplicateConfig())
                    if (autoApply) {
                        expensesRepo.applyExpenseDeduplication(groups)
                    } else {
                        expenseDeduplicationRepo.mergePendingGroups(groups)
                    }
                }
                ExpensesSettings.MODE_LOCAL_AND_AI -> {
                    val candidates = expensesRepo.ruleBasedCandidateClusters(settings.toNearDuplicateConfig()).flatten().map {
                        ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime, it.direction)
                    }
                    if (candidates.isNotEmpty()) {
                        ExpenseDeduplicationRequestSender.send(context, pendingLlmRequestQueue, candidates, autoApply = autoApply)
                    }
                }
                else -> {
                    val all = expensesRepo.expenses.first().map {
                        ExpenseSummary(it.id, it.title, it.vendor, it.totalAmount, it.currencyCode, it.dateTime, it.direction)
                    }
                    ExpenseDeduplicationRequestSender.send(context, pendingLlmRequestQueue, all, autoApply = autoApply)
                }
            }
        }
    }

    val pendingExpenseDuplicateGroups: Flow<List<DuplicateGroup>> = expenseDeduplicationRepo.pendingGroupsFlow

    /** [effectiveGroups] is what actually gets applied (its `keepId` may differ from the pending
     *  suggestion's if the user picked a different group member to keep in the review UI);
     *  [originalGroups] is the pending suggestion exactly as stored, needed to find and remove the
     *  right entries from [expenseDeduplicationRepo] regardless of that remap. */
    fun approveExpenseDeduplication(originalGroups: List<DuplicateGroup>, effectiveGroups: List<DuplicateGroup>) {
        scope.launch {
            expensesRepo.applyExpenseDeduplication(effectiveGroups)
            // Only the groups just applied should disappear — clearing everything here used to
            // silently drop any unchecked/unapplied suggestion too (the actual "no way to merge" bug).
            expenseDeduplicationRepo.removeGroups(originalGroups)
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
                nearDuplicateConfig = settings.toNearDuplicateConfig(),
                merchantMemoryEnabled = settings.merchantCategoryMemoryEnabled,
                merchantMemoryThreshold = settings.merchantCategoryMemoryThreshold,
                source = ExpenseSource.NOTIFICATION
            )
            pendingNotificationExpenseRepo.removePending(setOf(entry.id))
            maybeRequestScopedDuplicateCheck(context, settings.duplicateCheckModeAutomatic, id, settings.toNearDuplicateConfig())
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

    // --- Attachments (manually-added extras alongside the original receipt scan) ---
    fun observeAttachments(expenseId: Long): Flow<List<AttachmentEntity>> =
        attachmentDao.observeFor(ExpensesAttachments.RECORD_TYPE, expenseId)

    fun addManualAttachment(expenseId: Long, fileName: String) {
        scope.launch {
            attachmentDao.insert(
                AttachmentEntity(
                    recordType = ExpensesAttachments.RECORD_TYPE,
                    recordId = expenseId,
                    fileName = fileName,
                    source = AttachmentSource.MANUAL,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun removeAttachment(entity: AttachmentEntity, context: Context) {
        scope.launch {
            attachmentDao.delete(entity.id)
            AttachmentFileStore.delete(context, ExpensesAttachments.DIR, entity.fileName)
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
            pendingLlmRequestQueue: VoxLlmRequestQueue,
            attachmentDao: AttachmentDao,
            duplicateRuleDao: DuplicateRuleDao,
            appContext: Context
        ): ExpensesStateManager = instance ?: synchronized(this) {
            instance ?: ExpensesStateManager(
                settingsRepo, expensesRepo, sessionManager, pendingCategoryMergeRepo, expenseDeduplicationRepo,
                pendingNotificationExpenseRepo, spendingLimitAlertRepo, pendingLlmRequestQueue, attachmentDao, duplicateRuleDao,
                appContext
            ).also { instance = it }
        }
    }
}
