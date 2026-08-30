package com.voxapps.expenses.state

import com.voxapps.datahygiene.NameCasing
import com.voxapps.expenses.domain.accounts.BankAccountTree
import com.voxapps.expenses.data.BankAccount
import com.voxapps.design.filter.VoxRangeBuckets
import com.voxapps.design.filter.VoxRange
import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.AttachmentSource
import com.voxapps.expenses.data.AmountSpan
import com.voxapps.expenses.data.Category
import com.voxapps.expenses.data.DuplicateRuleDao
import com.voxapps.expenses.data.DuplicateRuleEntity
import com.voxapps.expenses.data.ExpensesAttachments
import com.voxapps.expenses.data.Expense
import com.voxapps.suggestions.OfferedSuggestion
import com.voxapps.expenses.data.ExpenseLineItem
import com.voxapps.expenses.data.ExpenseRemapFields
import com.voxapps.expenses.data.ExpenseSource
import com.voxapps.expenses.data.ExpensesRepository
import com.voxapps.expenses.data.FieldVocabularies
import com.voxapps.expenses.data.ExpenseWithDetails
import com.voxapps.expenses.domain.health.ExpenseGaps
import com.voxapps.expenses.data.RemapRuleEntity
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ExpensesStateManager(
    private val settingsRepo: ExpensesSettingsRepository,
    private val expensesRepo: ExpensesRepository,
    private val sessionManager: SessionManager,
    private val pendingCategoryMergeRepo: PendingCategoryMergeRepository,
    private val expenseDeduplicationRepo: ExpenseDeduplicationRepository,
    private val pendingNotificationExpenseRepo: PendingNotificationExpenseRepository,
    private val recurringPaymentRepo: com.voxapps.expenses.domain.recurring.RecurringPaymentRepository,
    private val spendingLimitAlertRepo: SpendingLimitAlertRepository,
    private val pendingLlmRequestQueue: VoxLlmRequestQueue,
    private val pendingCaptureCount: kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flowOf(0),
    private val pendingCaptureRows: kotlinx.coroutines.flow.Flow<List<com.voxapps.ipc.PendingLlmRequestEntity>> =
        kotlinx.coroutines.flow.flowOf(emptyList()),
    private val templateDirectionMemory: com.voxapps.expenses.domain.llm.TemplateDirectionMemory,
    private val attachmentDao: AttachmentDao,
    private val duplicateRuleDao: DuplicateRuleDao,
    private val appContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workManager = WorkManager.getInstance(appContext)

    private data class Runtime(
        val selectedCategoryId: Long? = null,
        val sort: SortMode = SortMode.NEWEST,
        val selectedDateMillis: Long = System.currentTimeMillis(),
        val dateFrom: Long? = null,
        val dateTo: Long? = null,
        val selectedBank: FilterValue? = null,
        val selectedLocation: FilterValue? = null,
        val selectedVendor: FilterValue? = null,
        val selectedAmount: VoxRange? = null,
        val selectedAccountId: Long? = null,
        val selectedCardId: Long? = null,
        val selectedCurrency: String? = null,
        /** Narrowed to the records something is missing from — see [ExpenseGaps]. */
        val onlyNeedsAttention: Boolean = false,
        val sessionTick: Int = 0
    )

    private val _runtime = MutableStateFlow(Runtime())

    /**
     * The narrowing the database is asked to do — [Runtime] projected down to the fields that
     * parameterize the query. Its own type so a date-strip tap or a session tick doesn't re-run
     * the SQL: the list re-subscribes only when one of these changes.
     */
    private data class SqlNarrowing(
        val categoryId: Long?,
        val dateFrom: Long?,
        val dateTo: Long?,
        val bank: FilterValue?,
        val vendor: FilterValue?,
        val location: FilterValue?,
        val amount: VoxRange?,
        val accountId: Long?,
        val cardId: Long?,
        val currency: String?,
        val sort: SortMode
    )

    private fun Runtime.narrowing() = SqlNarrowing(
        selectedCategoryId, dateFrom, dateTo, selectedBank, selectedVendor, selectedLocation,
        selectedAmount, selectedAccountId, selectedCardId, selectedCurrency, sort
    )

    /** What the amount buckets and the currency/location/vendor pickers offer, read from every
     *  record rather than from the narrowed list — brackets that narrowed themselves each time one
     *  was picked would move under the finger — but as column aggregates, not by carrying rows. */
    private data class ListVocabulary(
        val currencies: List<String>,
        val span: AmountSpan,
        val locations: List<String>,
        val vendors: List<String>
    )

    /**
     * The records with something missing, when that is what was asked for.
     *
     * Applied after the ordinary filters rather than inside them: "needs me" is a question about the
     * record's completeness, not about which shop or month it belongs to, and the two compose —
     * "August, and the ones that need me" is a reasonable thing to ask.
     */
    private fun withAttentionFilter(
        rt: Runtime,
        categories: List<Category>,
        accounts: List<BankAccount>,
        records: List<ExpenseWithDetails>
    ): List<ExpenseWithDetails> =
        if (!rt.onlyNeedsAttention) records
        else ExpenseGaps.needingAttention(
            records,
            categories.firstOrNull { it.isDefault }?.id,
            accountsInUse = accounts.isNotEmpty()
        )

    private val _uiState = MutableStateFlow<ExpensesUiState>(ExpensesUiState.Loading)
    val uiState: StateFlow<ExpensesUiState> = _uiState.asStateFlow()

    init {
        val nextRunMillisFlow = workManager.getWorkInfosForUniqueWorkFlow("expense_deduplication")
            .map { infoList ->
                infoList.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                    ?.nextScheduleTimeMillis
            }

        // The rows arrive already narrowed and ordered by the query; only what SQL cannot say
        // faithfully — FilterValue's Unicode matching, the bank resolved through the account —
        // remains to check up here, on the small remainder. Re-keyed on the narrowing AND the
        // accounts, since the account family and the bank names both resolve through the latter.
        val filteredFlow = combine(
            _runtime.map { it.narrowing() }.distinctUntilChanged(),
            expensesRepo.bankAccounts
        ) { narrowing, accounts -> narrowing to accounts }
            .distinctUntilChanged()
            .flatMapLatest { (f, accounts) ->
                expensesRepo.observeFiltered(
                    categoryId = f.categoryId,
                    dateFrom = f.dateFrom,
                    dateTo = f.dateTo,
                    amountMin = f.amount?.from,
                    amountMax = f.amount?.to,
                    currency = f.currency,
                    // A card answers for itself; an account answers for its cards too. The
                    // narrower choice wins because it is the one made second.
                    accountIds = (f.cardId ?: f.accountId)?.let { BankAccountTree.familyOf(it, accounts) },
                    sortName = f.sort.name
                ).map { rows ->
                    ExpenseFilter.residual(
                        rows, f.bank, { id -> BankAccountTree.bankNameFor(id, accounts) },
                        f.vendor, f.location
                    )
                }
            }

        val vocabularyFlow = combine(
            expensesRepo.currenciesInUse,
            expensesRepo.amountSpan,
            expensesRepo.locationsInUse,
            expensesRepo.vendorsInUse
        ) { currencies, span, locations, vendors -> ListVocabulary(currencies, span, locations, vendors) }

        // Paired rather than passed separately: combine is typed up to five sources, and the lists
        // that name a record's category and its account travel with the pickers' vocabularies.
        val namesFlow = combine(expensesRepo.categories, expensesRepo.bankAccounts, vocabularyFlow) {
            c, a, v -> Triple(c, a, v)
        }

        combine(
            settingsRepo.settingsFlow,
            filteredFlow,
            namesFlow,
            _runtime,
            nextRunMillisFlow
        ) { settings, expenses, names, rt, nextRunMillis ->
            val (categories, accounts, vocabulary) = names
            val locked = settings.isBiometricRequired &&
                !sessionManager.isSessionValid(settings.sessionTimeoutMinutes)
            if (locked) {
                ExpensesUiState.Locked
            } else {
                ExpensesUiState.Unlocked(
                    expenses = withAttentionFilter(rt, categories, accounts, expenses),
                    categories = categories,
                    selectedCategoryId = rt.selectedCategoryId,
                    sort = rt.sort,
                    isGridView = settings.isGridView,
                    selectedDateMillis = rt.selectedDateMillis,
                    dateFrom = rt.dateFrom,
                    dateTo = rt.dateTo,
                    selectedBank = rt.selectedBank,
                    selectedLocation = rt.selectedLocation,
                    selectedVendor = rt.selectedVendor,
                    selectedAmount = rt.selectedAmount,
                    selectedAccountId = rt.selectedAccountId,
                    selectedCardId = rt.selectedCardId,
                    selectedCurrency = rt.selectedCurrency,
                    onlyNeedsAttention = rt.onlyNeedsAttention,
                    bankAccounts = accounts,
                    availableCurrencies = vocabulary.currencies,
                    amountBuckets = VoxRangeBuckets.of(
                        vocabulary.span.min ?: 0.0,
                        vocabulary.span.max ?: 0.0
                    ),
                    // The banks this device deals with are its accounts, not a column on its
                    // records: one list, and one place it can be wrong.
                    availableBanks = accounts.filter { it.isAccount }
                        .mapNotNull { BankAccountTree.bankNameOf(it, accounts) }.distinct().sorted(),
                    availableLocations = vocabulary.locations,
                    availableVendors = vocabulary.vendors,
                    nextScheduledDedupMillis = nextRunMillis
                )
            }
        }
        // Deliberately NOT flowOn(Default) and NOT stateIn/WhileSubscribed, though the combine
        // above is real CPU work on the scope's Main dispatcher:
        //
        //  - flowOn moves the transform off this thread, which makes _uiState update
        //    asynchronously. Today a change published into _runtime settles into _uiState within
        //    the same call, and the synchronous accessors below rely on that; adding flowOn broke
        //    exactly those expectations in NotesStateManagerTest.
        //  - WhileSubscribed would leave uiState.value at its initial Loading value whenever no UI
        //    is attached, and it is read synchronously, with no subscription, by headless callers
        //    (IPC read/export responders and the widget refresh).
        //
        // Both are worth revisiting only behind a measurement showing this combine actually costs
        // frames, together with a plan for those synchronous readers.
            .onEach { _uiState.value = it }.launchIn(scope)
    }

    /** Narrows the list to records with something missing — see [ExpenseGaps]. */
    fun setNeedsAttentionFilter(only: Boolean) = _runtime.update { it.copy(onlyNeedsAttention = only) }

    fun setCategoryFilter(categoryId: Long?) = _runtime.update { it.copy(selectedCategoryId = categoryId) }
    fun setSort(sort: SortMode) = _runtime.update { it.copy(sort = sort) }
    fun setAmountFilter(range: VoxRange?) = _runtime.update { it.copy(selectedAmount = range) }

    /**
     * Narrowing to one account, or to one currency — never both at once.
     *
     * An account holds exactly one currency, so the two questions overlap: asking for a EUR account
     * and then for RON returns nothing, and the person is left looking at an empty list with two
     * filters on and no hint which one emptied it. Choosing either therefore lets go of the other,
     * and the screen greys out whichever is not in force.
     */
    fun setAccountFilter(accountId: Long?) =
        _runtime.update {
            // The card belonged to the account that was chosen before; changing or clearing the
            // account takes the card with it, since a card under a different account is not a
            // narrowing of this one — it is a different question.
            it.copy(selectedAccountId = accountId, selectedCardId = null, selectedCurrency = null)
        }

    /** One card under the account already chosen. The account stays, and shows greyed: it is no
     *  longer a choice, it is the thing the card belongs to. */
    fun setCardFilter(cardId: Long?) =
        _runtime.update { it.copy(selectedCardId = cardId, selectedCurrency = null) }

    fun setCurrencyFilter(code: String?) =
        _runtime.update { it.copy(selectedCurrency = code, selectedAccountId = null, selectedCardId = null) }
    fun setSelectedDate(millis: Long) = _runtime.update { it.copy(selectedDateMillis = millis) }
    fun setDateFilter(from: Long?, to: Long?) = _runtime.update { it.copy(dateFrom = from, dateTo = to) }
    fun clearDateFilter() = _runtime.update { it.copy(dateFrom = null, dateTo = null) }

    /** Every narrowing undone at once, sort included: a list nobody has narrowed is also a list
     *  nobody has reordered, and clearing all but one leaves the button still reporting a filter. */
    fun clearAllFilters() = _runtime.update {
        it.copy(
            selectedCategoryId = null, dateFrom = null, dateTo = null,
            selectedBank = null, selectedVendor = null, selectedLocation = null,
            selectedAmount = null, selectedAccountId = null, selectedCardId = null,
            selectedCurrency = null,
            onlyNeedsAttention = false,
            sort = SortMode.NEWEST
        )
    }
    fun setBankFilter(bank: FilterValue?) = _runtime.update { it.copy(selectedBank = bank) }
    fun setLocationFilter(location: FilterValue?) = _runtime.update { it.copy(selectedLocation = location) }
    fun setVendorFilter(vendor: FilterValue?) = _runtime.update { it.copy(selectedVendor = vendor) }

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
    /** Which declared currency service supplies rates — see ExternalServiceConfig.currencyServices. */
    fun setExchangeRateServiceId(id: String) { scope.launch { settingsRepo.setExchangeRateServiceId(id) } }
    fun setPaymentSourcePackages(packages: Set<String>) { scope.launch { settingsRepo.setPaymentSourcePackages(packages) } }
    fun setBankingSourcePackages(packages: Set<String>) { scope.launch { settingsRepo.setBankingSourcePackages(packages) } }
    fun setAutoAcceptNotificationExpenses(enabled: Boolean) { scope.launch { settingsRepo.setAutoAcceptNotificationExpenses(enabled) } }
    fun setDebugLoggingEnabled(enabled: Boolean) {
        Logger.setEnabled(enabled)
        scope.launch { settingsRepo.setDebugLoggingEnabled(enabled) }
    }
    fun setVatDisplay(mode: String) { scope.launch { settingsRepo.setVatDisplay(mode) } }
    fun setDecimalSeparator(separator: String) { scope.launch { settingsRepo.setDecimalSeparator(separator) } }
    fun setCalendarViewEnabled(enabled: Boolean) { scope.launch { settingsRepo.setCalendarViewEnabled(enabled) } }
    fun setIsGridView(enabled: Boolean) { scope.launch { settingsRepo.setIsGridView(enabled) } }
    fun setDebugToastsEnabled(enabled: Boolean) { scope.launch { settingsRepo.setDebugToastsEnabled(enabled) } }
    fun setAttachPhotoOnScan(enabled: Boolean) { scope.launch { settingsRepo.setAttachPhotoOnScan(enabled) } }
    fun setScanModelUse(mode: String) { scope.launch { settingsRepo.setScanModelUse(mode) } }
    fun setNotificationModelUse(mode: String) { scope.launch { settingsRepo.setNotificationModelUse(mode) } }
    fun setVoiceModelUse(mode: String) { scope.launch { settingsRepo.setVoiceModelUse(mode) } }
    /**
     * The tour runs again, and every settings page explains itself again.
     *
     * Both halves, because replaying is a decision about the whole app: a tutorial that ran again
     * while every page stayed silent would be half an answer. See
     * [com.voxapps.onboarding.VoxHintStore].
     */
    fun replayTutorial() {
        scope.launch {
            hintStore.resetAll()
            settingsRepo.setOnboardingCompleted(false)
        }
    }

    private val hintStore = com.voxapps.onboarding.VoxHintStore(
        com.voxapps.expenses.data.preferences.DataStoreProvider.get(appContext)
    )

    fun setCaptureAmountlessPayments(enabled: Boolean) { scope.launch { settingsRepo.setCaptureAmountlessPayments(enabled) } }
    fun setGuardNotificationEnabled(enabled: Boolean) { scope.launch { settingsRepo.setGuardNotificationEnabled(enabled) } }
    fun setPermanentNotificationEnabled(enabled: Boolean) { scope.launch { settingsRepo.setPermanentNotificationEnabled(enabled) } }
    fun setNotifShowToday(enabled: Boolean) { scope.launch { settingsRepo.setNotifShowToday(enabled) } }
    fun setNotifShowWeek(enabled: Boolean) { scope.launch { settingsRepo.setNotifShowWeek(enabled) } }
    fun setNotifShowMonth(enabled: Boolean) { scope.launch { settingsRepo.setNotifShowMonth(enabled) } }
    fun setNotifShowTodayCount(enabled: Boolean) { scope.launch { settingsRepo.setNotifShowTodayCount(enabled) } }
    fun setNotifShowTodayIncome(enabled: Boolean) { scope.launch { settingsRepo.setNotifShowTodayIncome(enabled) } }
    fun setNotifShowReviewCount(enabled: Boolean) { scope.launch { settingsRepo.setNotifShowReviewCount(enabled) } }

    // --- cards and accounts (see BankAccount) ---

    val bankAccountsFlow: Flow<List<BankAccount>> = expensesRepo.bankAccounts

    fun setAutoCreateAccountsFromScans(enabled: Boolean) { scope.launch { settingsRepo.setAutoCreateAccountsFromScans(enabled) } }
    fun setAutoCreateAccountsFromNotifications(enabled: Boolean) { scope.launch { settingsRepo.setAutoCreateAccountsFromNotifications(enabled) } }
    fun setLearnNamesFromNotifications(enabled: Boolean) { scope.launch { settingsRepo.setLearnNamesFromNotifications(enabled) } }
    fun setLearnNamesFromScans(enabled: Boolean) { scope.launch { settingsRepo.setLearnNamesFromScans(enabled) } }
    fun setDefaultAccountCurrency(code: String) { scope.launch { settingsRepo.setDefaultAccountCurrency(code) } }
    fun addTypedBankAccount(text: String, currencyCode: String) {
        scope.launch { expensesRepo.addTypedBankAccount(text, currencyCode) }
    }
    /** The account at a bank named by hand: the one already there, or a new one. [onReady] takes
     *  its id, for the screen that asked to point at it. */
    fun accountNamed(bankName: String, currencyCode: String, onReady: (Long?) -> Unit = {}) {
        scope.launch { onReady(expensesRepo.accountNamed(bankName, currencyCode)) }
    }

    fun updateBankAccount(account: BankAccount) { scope.launch { expensesRepo.updateBankAccount(account) } }
    fun deleteBankAccount(account: BankAccount) { scope.launch { expensesRepo.deleteBankAccount(account) } }

    // --- who transactions paid (see Recipient) ---

    val recipientsFlow: Flow<List<com.voxapps.expenses.data.Recipient>> = expensesRepo.recipients

    /** [onReady] takes the new row's id, for the editor that wants to point at it right away. */
    fun addTypedRecipient(name: String, bankName: String?, iban: String?, onReady: (Long?) -> Unit = {}) {
        scope.launch { onReady(expensesRepo.addTypedRecipient(name, bankName, iban).takeIf { it > 0 }) }
    }
    fun updateRecipient(recipient: com.voxapps.expenses.data.Recipient) { scope.launch { expensesRepo.updateRecipient(recipient) } }
    fun deleteRecipient(recipient: com.voxapps.expenses.data.Recipient) { scope.launch { expensesRepo.deleteRecipient(recipient) } }
    fun setNotificationAssumedDirection(mode: String) { scope.launch { settingsRepo.setNotificationAssumedDirection(mode) } }
    fun setAttachPhotoOnRetry(enabled: Boolean) { scope.launch { settingsRepo.setAttachPhotoOnRetry(enabled) } }
    fun setAutoRescanOnFirstAttachment(enabled: Boolean) { scope.launch { settingsRepo.setAutoRescanOnFirstAttachment(enabled) } }
    fun setAutoOpenScannedExpense(enabled: Boolean) { scope.launch { settingsRepo.setAutoOpenScannedExpense(enabled) } }
    fun setLocationPrefillEnabled(enabled: Boolean) { scope.launch { settingsRepo.setLocationPrefillEnabled(enabled) } }
    fun setDuplicateCheckModeManual(mode: String) { scope.launch { settingsRepo.setDuplicateCheckModeManual(mode) } }
    fun setDuplicateCheckModeAutomatic(mode: String) { scope.launch { settingsRepo.setDuplicateCheckModeAutomatic(mode) } }
    fun setAutoAcceptDuplicateMerges(enabled: Boolean) { scope.launch { settingsRepo.setAutoAcceptDuplicateMerges(enabled) } }
    fun setAutomaticProtectionReviewOnly(enabled: Boolean) { scope.launch { settingsRepo.setAutomaticProtectionReviewOnly(enabled) } }
    fun setNearDuplicateTimeWindowMinutes(minutes: Int) { scope.launch { settingsRepo.setNearDuplicateTimeWindowMinutes(minutes) } }
    fun setDuplicateRuleSetGlobalCombinator(combinator: String) { scope.launch { settingsRepo.setDuplicateRuleSetGlobalCombinator(combinator) } }

    // --- Duplicate rules (see DuplicateRuleEntity/DuplicateRuleDao) ---
    val duplicateRules: Flow<List<DuplicateRuleEntity>> = duplicateRuleDao.observeAll().distinctUntilChanged()
    fun upsertDuplicateRule(rule: DuplicateRuleEntity) { scope.launch { duplicateRuleDao.upsert(rule) } }
    fun deleteDuplicateRule(rule: DuplicateRuleEntity) { scope.launch { duplicateRuleDao.delete(rule) } }
    fun setDuplicateRuleEnabled(id: Long, enabled: Boolean) { scope.launch { duplicateRuleDao.setEnabled(id, enabled) } }
    fun setRemapProposalsEnabled(enabled: Boolean) { scope.launch { settingsRepo.setRemapProposalsEnabled(enabled) } }
    fun setRemapLearningSpeed(count: Int) { scope.launch { settingsRepo.setRemapLearningSpeed(count) } }
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
     *  dependency" convention; [ExpensesRepository.recordRemapPatternSightings] itself is
     *  unconditional. */
    fun recordFieldEditPatterns(old: ExpenseWithDetails, new: Expense) {
        val settings = settingsRepo.getSnapshot()
        if (!settings.remapProposalsEnabled) return
        scope.launch { expensesRepo.recordRemapPatternSightings(old, new, settings.remapLearningSpeed) }
    }

    /** One edit across every selected record. [onDone] takes how many actually changed, for the
     *  screen to say so. */
    fun applyBulkEdit(ids: Collection<Long>, edit: com.voxapps.expenses.domain.bulk.BulkEdit, onDone: (Int) -> Unit = {}) {
        scope.launch { onDone(expensesRepo.applyBulkEdit(ids, edit)) }
    }

    val remapRules: Flow<List<com.voxapps.expenses.data.RemapRuleEntity>> get() = expensesRepo.observeRemapRules()
    fun upsertRemapRule(rule: com.voxapps.expenses.data.RemapRuleEntity) = scope.launch { expensesRepo.upsertRemapRule(rule) }
    fun deleteRemapRule(rule: com.voxapps.expenses.data.RemapRuleEntity) = scope.launch { expensesRepo.deleteRemapRule(rule) }
    fun setRemapRuleEnabled(rule: com.voxapps.expenses.data.RemapRuleEntity, enabled: Boolean) =
        scope.launch { expensesRepo.upsertRemapRule(rule.copy(enabled = enabled)) }

    fun reorderRemapRules(orderedIds: List<Long>) = scope.launch { expensesRepo.reorderRemapRules(orderedIds) }
    fun setAllRemapRulesEnabled(enabled: Boolean) = scope.launch { expensesRepo.setAllRemapRulesEnabled(enabled) }
    fun deleteAllRemapRules() = scope.launch { expensesRepo.deleteAllRemapRules() }

    /** Same gate convention as [recordFieldEditPatterns];
     *  [ExpensesRepository.recordFieldCorrections] itself is unconditional. */
    fun recordFieldCorrections(old: ExpenseWithDetails, new: Expense, newItems: List<ExpenseLineItem>) {
        if (!settingsRepo.getSnapshot().fieldCorrectionMemoryEnabled) return
        scope.launch { expensesRepo.recordFieldCorrections(old, new, newItems) }
    }

    fun setFieldCorrectionMemoryEnabled(enabled: Boolean) = scope.launch { settingsRepo.setFieldCorrectionMemoryEnabled(enabled) }
    /** The learned notification templates for the settings list — suspend snapshot, refreshed by
     *  the screen after every action rather than observed (the store is a small DataStore blob
     *  written from several processes' wake-ups; a poll-on-action screen shows fresh truth
     *  without another always-on collector). */
    suspend fun learnedTemplatesSnapshot() = templateDirectionMemory.snapshot()
    suspend fun forgetLearnedTemplate(hash: String) = templateDirectionMemory.forget(hash)
    suspend fun reteachLearnedTemplate(hash: String) = templateDirectionMemory.reteach(hash)

    fun setFieldCorrectionThreshold(count: Int) = scope.launch { settingsRepo.setFieldCorrectionThreshold(count) }
    fun setFieldCorrectionApplyMode(mode: String) = scope.launch { settingsRepo.setFieldCorrectionApplyMode(mode) }
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
        /** The account or card this went through — the bank is its name, never a field of its own. */
        bankAccountId: Long? = null,
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
                title, totalAmount, currencyCode, vendor, location, dateTime, comments, categoryId, items, imageName, isStub,
                bankAccountId = bankAccountId,
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
        scope.launch {
            expensesRepo.updateExpense(expense, items, markManuallyEdited = true)
            // Saving an auto-created notification record from the editor is the human confirmation
            // its template never got at creation; see TemplateDirectionMemory.
            templateDirectionMemory.consumeLink(expense.id)?.let {
                templateDirectionMemory.confirm(it, expense.direction)
                Logger.d("TemplateMemory", "edit-save confirmed ${expense.direction} for template $it (record ${expense.id})")
            }
        }
    }

    fun deleteExpense(expense: Expense) { scope.launch { expensesRepo.deleteExpense(expense) } }
    fun deleteExpenseById(id: Long) { scope.launch { expensesRepo.deleteExpenseById(id) } }
    /** Every record in a selection, gone. [onDone] takes how many, for the screen to say so. */
    fun deleteExpenses(ids: Collection<Long>, onDone: (Int) -> Unit = {}) {
        scope.launch { onDone(expensesRepo.deleteExpenses(ids)) }
    }

    /** What has been put out of the way, newest first — see [Expense.archivedAt]. */
    val archivedRecords: Flow<List<ExpenseWithDetails>> = expensesRepo.archivedWithDetails

    fun archiveExpenses(ids: Collection<Long>, onDone: (Int) -> Unit = {}) {
        scope.launch { onDone(expensesRepo.archiveExpenses(ids)) }
    }

    fun restoreExpenses(ids: Collection<Long>, onDone: (Int) -> Unit = {}) {
        scope.launch { onDone(expensesRepo.restoreExpenses(ids)) }
    }

    /**
     * How long the archive keeps things, and the sweep that follows from changing it.
     *
     * The sweep runs here rather than waiting for tomorrow's worker: somebody who has just said
     * "keep these thirty days" means the ones already older than thirty days, and an archive that
     * still lists them until the next daily pass looks like the setting did nothing.
     */
    fun setArchiveRetentionDays(days: Int) {
        scope.launch {
            settingsRepo.setArchiveRetentionDays(days)
            com.voxapps.expenses.domain.archive.ArchiveRetention
                .cutoff(days, System.currentTimeMillis())
                ?.let { expensesRepo.purgeArchivedBefore(it) }
        }
    }

    fun deleteAllExpenses() {
        scope.launch { expensesRepo.deleteAllExpenses() }
    }

    fun addCategory(name: String, colorArgb: Long, icon: String? = null, onResult: (Long) -> Unit = {}) {
        val position = (uiStateCategories()).size
        scope.launch {
            val id = expensesRepo.addCategory(name, colorArgb, position, System.currentTimeMillis(), icon)
            onResult(id)
        }
    }

    fun updateCategory(category: Category) { scope.launch { expensesRepo.updateCategory(category) } }

    /** Moves the star; the repository keeps exactly one. See [Category.isDefault]. */
    fun setDefaultCategory(categoryId: Long) { scope.launch { expensesRepo.setDefaultCategory(categoryId) } }

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

    /** Everything noticed about payments that come back — observations first, then arrangements. */
    val recurringPayments: Flow<List<com.voxapps.expenses.data.RecurringPayment>> =
        recurringPaymentRepo.all

    /** What has repeated often enough to be worth asking about, at the threshold you chose. */
    suspend fun recurringProposals(): List<com.voxapps.expenses.data.RecurringPayment> =
        recurringPaymentRepo.proposals(settingsRepo.getSnapshot().recurringProposalThreshold)

    /** Arrangements that have gone quiet for as many cycles as it took to propose them. */
    suspend fun recurringGoneQuiet(): List<com.voxapps.expenses.data.RecurringPayment> =
        recurringPaymentRepo.stale(settingsRepo.getSnapshot().recurringProposalThreshold)

    fun confirmRecurringPayment(id: Long) { scope.launch { recurringPaymentRepo.confirm(id) } }

    /** Also the answer to "this has stopped": dismissing is what a person says once, and it is not
     *  asked again. */
    fun dismissRecurringPayment(id: Long) { scope.launch { recurringPaymentRepo.dismiss(id) } }

    /**
     * Adds a term of this device's own, or says why it was refused.
     *
     * Refusal is checked here rather than in the screen because the reason a term is refused is a
     * property of the merged vocabulary, not of what was typed — and letting a colliding term
     * through would disable the whole vocabulary silently.
     */
    suspend fun addVocabularyTerm(vocabulary: String, term: String): FieldVocabularies.Rejection? {
        val cleaned = NameCasing.capitalized(term).orEmpty()
        val settings = settingsRepo.getSnapshot()
        FieldVocabularies.rejectionFor(cleaned, vocabulary, appContext, settings)?.let { return it }
        settingsRepo.setCustomVocabulary(vocabulary, customOf(settings, vocabulary) + cleaned)
        return null
    }

    /**
     * The names a capture just wrote, kept for the next one to be read by.
     *
     * The same act as filing a card read from a message: what a capture established about the world
     * outlives the record it produced. Per route, because a scan is deliberate and a notification
     * arrives on its own, and the person may want one and not the other.
     *
     * Rejections are silent by design — a term already listed, or one the vocabulary refuses, is not
     * a failure worth reporting to somebody who never asked for this to happen.
     */
    suspend fun learnNamesFrom(vendor: String?, bank: String?, fromScan: Boolean) {
        val settings = settingsRepo.getSnapshot()
        val allowed = if (fromScan) settings.learnNamesFromScans else settings.learnNamesFromNotifications
        if (!allowed) return
        vendor?.trim()?.takeIf { it.isNotEmpty() }?.let { addVocabularyTerm(FieldVocabularies.VOCAB_VENDOR, it) }
        bank?.trim()?.takeIf { it.isNotEmpty() }?.let { addVocabularyTerm(FieldVocabularies.VOCAB_BANK, it) }
    }

    /**
     * A term of one's own, spelled differently.
     *
     * The list is a list of names, and a name is the kind of thing that gets typed wrong once and
     * lived with for a year. Renaming is the old one out and the new one in, in that order, so a
     * spelling that only differs in case or punctuation is not refused as already present.
     *
     * Only terms this device added: a supplied word is not this app's to rewrite — switch it off
     * and add your own if you want it spelled another way.
     */
    suspend fun renameVocabularyTerm(
        vocabulary: String,
        from: String,
        to: String
    ): FieldVocabularies.Rejection? {
        val cleaned = to.trim()
        if (cleaned.isEmpty()) return FieldVocabularies.Rejection.EMPTY
        if (cleaned == from.trim()) return null
        val settings = settingsRepo.getSnapshot()
        val without = customOf(settings, vocabulary) - from
        settingsRepo.setCustomVocabulary(vocabulary, without)
        return addVocabularyTerm(vocabulary, cleaned).also { rejection ->
            // Put it back rather than leaving the person with neither spelling.
            if (rejection != null) settingsRepo.setCustomVocabulary(vocabulary, without + from)
        }
    }

    fun removeVocabularyTerm(vocabulary: String, term: String) {
        scope.launch {
            val settings = settingsRepo.getSnapshot()
            settingsRepo.setCustomVocabulary(vocabulary, customOf(settings, vocabulary) - term)
        }
    }

    /** Switches a whole section off, or back on — the supplied words or this device's own, named by
     *  the screen rather than recomputed here, so what is switched is exactly what was listed. */
    fun setVocabularySectionEnabled(vocabulary: String, terms: Collection<String>, enabled: Boolean) {
        scope.launch {
            val keys = FieldVocabularies.keysOf(terms)
            val settings = settingsRepo.getSnapshot()
            val current = disabledOf(settings, vocabulary)
            settingsRepo.setDisabledVocabulary(vocabulary, if (enabled) current - keys else current + keys)
        }
    }

    /** Switches a supplied term off, or back on. Keyed by the classifier's own normalization, so the
     *  choice survives the list underneath being replaced by a newer one. */
    fun setVocabularyTermEnabled(vocabulary: String, term: String, enabled: Boolean) {
        scope.launch {
            val key = com.voxapps.textmatch.extract.VocabularyClassifier.termKey(term)
            val settings = settingsRepo.getSnapshot()
            val current = disabledOf(settings, vocabulary)
            settingsRepo.setDisabledVocabulary(vocabulary, if (enabled) current - key else current + key)
        }
    }

    private fun customOf(settings: ExpensesSettings, vocabulary: String) = when (vocabulary) {
        FieldVocabularies.VOCAB_BANK -> settings.customBanks
        FieldVocabularies.VOCAB_STOP -> settings.customStopWords
        FieldVocabularies.VOCAB_VENDOR -> settings.customVendors
        else -> settings.customLegalForms
    }

    private fun disabledOf(settings: ExpensesSettings, vocabulary: String) = when (vocabulary) {
        FieldVocabularies.VOCAB_BANK -> settings.disabledBanks
        FieldVocabularies.VOCAB_STOP -> settings.disabledStopWords
        FieldVocabularies.VOCAB_VENDOR -> settings.disabledVendors
        else -> settings.disabledLegalForms
    }

    fun setDismissNotificationOnCapture(enabled: Boolean) { scope.launch { settingsRepo.setDismissNotificationOnCapture(enabled) } }

    fun setRecurringProposalThreshold(times: Int) { scope.launch { settingsRepo.setRecurringProposalThreshold(times) } }

    fun setRecurringRemindersEnabled(enabled: Boolean) { scope.launch { settingsRepo.setRecurringRemindersEnabled(enabled) } }

    /**
     * Files a reviewed capture.
     *
     * An entry that never carried an amount cannot be filed as it stands, and the screen does not
     * offer to — an expense of nothing is not a record, it is a gap wearing one. The figure the
     * reviewer typed arrives as [amountOverride], and this refuses rather than inventing a zero.
     */
    /**
     * Files a reviewed capture, and takes with it whatever the person settled while reviewing it.
     *
     * [learnBank] and [learnVendor] are words they picked on the entry — the app had no way to know
     * these, which is why they were asked rather than offered. Written on approval and not on the
     * tap: a chip tapped and then dismissed teaches nothing, and a list that grew from a glance
     * would be a list nobody could account for.
     */
    fun approveNotificationExpense(
        entry: PendingNotificationExpense,
        context: Context? = null,
        amountOverride: Double? = null,
        learnBank: String? = null,
        learnVendor: String? = null,
        renameVendor: Boolean = false,
        renameBank: Boolean = false
    ) {
        val amount = amountOverride ?: entry.totalAmount ?: return
        scope.launch {
            learnBank?.let { addVocabularyTerm(FieldVocabularies.VOCAB_BANK, it) }
            learnVendor?.let { addVocabularyTerm(FieldVocabularies.VOCAB_VENDOR, it) }
            // Before the record is written, so the rule that outlives this entry also reaches it:
            // addParsedExpense runs enabled re-map rules on the way in, and a rename accepted on a
            // record the person is looking at should be visible on that record.
            if (renameVendor) entry.vendorRenameTo?.let { to ->
                entry.vendorSpelling()?.let { expensesRepo.addAcceptedRename(ExpenseRemapFields.ID_VENDOR, it, to) }
            }
            if (renameBank) entry.bankRenameTo?.let { to ->
                entry.bank?.let { expensesRepo.addAcceptedRename(ExpenseRemapFields.ID_BANK, it, to) }
            }
            val settings = settingsRepo.getSnapshot()
            val id = expensesRepo.addParsedExpense(
                title = entry.title,
                totalAmount = amount,
                currencyCode = entry.currency,
                // An accepted rename names this record too, not only the ones after it. Where the
                // entry never resolved a merchant there is no field for a rule to rewrite, so the
                // answer the person just gave is written here directly.
                vendor = entry.vendorRenameTo?.takeIf { renameVendor } ?: entry.vendor,
                bank = entry.bankRenameTo?.takeIf { renameBank } ?: entry.bank,
                location = null,
                comments = null,
                dateTime = entry.capturedAt,
                spokenCategory = entry.category,
                defaultCategoryId = settings.defaultVoiceCategoryId,
                autoCreate = settings.autoCreateVoiceCategory,
                direction = entry.direction,
                nearDuplicateCheckEnabled = settings.duplicateCheckModeAutomatic != ExpensesSettings.MODE_AI,
                nearDuplicateConfig = settings.toNearDuplicateConfig(),
                correctionsEnabled = settings.fieldCorrectionMemoryEnabled,
                correctionsThreshold = settings.fieldCorrectionThreshold,
                correctionsApplyMode = settings.fieldCorrectionApplyMode,
                source = ExpenseSource.NOTIFICATION
            )
            // Approval is the human confirmation the template memory feeds on.
            templateDirectionMemory.confirm(entry.templateHash, entry.direction)
            if (entry.templateHash != null) Logger.d("TemplateMemory", "approve confirmed ${entry.direction} for template ${entry.templateHash}")
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

    fun setWidgetBudgetMode(mode: String) { scope.launch { settingsRepo.setWidgetBudgetMode(mode) } }

    fun setWidgetBudgetAccountIds(ids: Set<Long>) { scope.launch { settingsRepo.setWidgetBudgetAccountIds(ids) } }

    /**
     * Captures waiting for an answer.
     *
     * A person who dictated an expense and sees nothing assumes they were not heard, and says it
     * again — which is how one utterance becomes three queued requests. One line saying the app is
     * still working on it is the whole fix.
     */
    val pendingCaptures = pendingCaptureCount

    /** The waiting captures themselves, for the sheet the strip opens. */
    val pendingCaptureList = pendingCaptureRows

    /**
     * Asks for every queued capture to be re-sent now rather than at the worker's next turn.
     *
     * The staleness window is zero here on purpose: a person who opened this list and pressed the
     * button is telling the app that whatever was wrong — no signal, an engine that was not running
     * — is no longer wrong.
     */
    fun retryPendingCapturesNow(context: Context) {
        scope.launch { pendingLlmRequestQueue.retryEverythingNow(context) }
    }

    /** Drops the captures the app gave up on — see [VoxLlmRequestQueue.forgetGivenUp]. */
    fun forgetGivenUpCaptures() {
        scope.launch { pendingLlmRequestQueue.forgetGivenUp() }
    }

    fun forgetPendingCapture(requestId: String) {
        scope.launch { pendingLlmRequestQueue.forget(requestId) }
    }

    private val attentionDismissals = com.voxapps.expenses.data.preferences.AttentionDismissals(appContext)

    /** What has been seen, per kind — see [com.voxapps.expenses.data.preferences.AttentionDismissals]. */
    val dismissals = attentionDismissals.flow

    /** Marks one kind as seen: everything of it older than now stops being counted, everything that
     *  arrives afterwards is counted again. */
    fun dismissAttention(kind: com.voxapps.expenses.data.preferences.AttentionKind) {
        scope.launch { attentionDismissals.dismiss(kind) }
    }

    fun dismissAllAttention() {
        scope.launch { attentionDismissals.dismissAll() }
    }

    /** Drafted rules nobody has approved yet — see [RemapRuleEntity.ORIGIN_PROPOSED]. */
    val proposedRuleCount: kotlinx.coroutines.flow.Flow<Int> =
        combine(expensesRepo.observeRemapRules(), attentionDismissals.flow) { rules, seen ->
            rules.count {
                it.origin == RemapRuleEntity.ORIGIN_PROPOSED && !it.enabled && it.updatedAt > seen.rulesBefore
            }
        }

    /** Names this device has actually used — see [ExpensesRepository.banksInUse]. */
    val banksInUse = expensesRepo.banksInUse
    val vendorsInUse = expensesRepo.vendorsInUse

    // --- budgets: what there is left to spend, per account and currency ---

    val accountBudgets = expensesRepo.accountBudgets

    fun upsertAccountBudget(budget: com.voxapps.expenses.data.AccountBudget) {
        scope.launch { expensesRepo.upsertAccountBudget(budget) }
    }

    fun deleteAccountBudget(budget: com.voxapps.expenses.data.AccountBudget) {
        scope.launch { expensesRepo.deleteAccountBudget(budget) }
    }

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

    /** One-shot counterpart for callers that just need the current rows once (the edit screen's
     *  opening snapshot), rather than collecting the flow's first emission and cancelling it. */
    suspend fun getAttachments(expenseId: Long): List<AttachmentEntity> =
        attachmentDao.getFor(ExpensesAttachments.RECORD_TYPE, expenseId)

    fun addManualAttachment(expenseId: Long, fileName: String, groupId: String? = null, groupOrder: Int = 0) {
        scope.launch {
            attachmentDao.insert(
                AttachmentEntity(
                    recordType = ExpensesAttachments.RECORD_TYPE,
                    recordId = expenseId,
                    fileName = fileName,
                    source = AttachmentSource.MANUAL,
                    createdAt = System.currentTimeMillis(),
                    groupId = groupId,
                    groupOrder = groupOrder
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

    /** Cancels a burst mid-capture (see [com.voxapps.attachments.ui.rememberBurstCaptureLauncher]) —
     *  deletes every row+file already committed under [groupId] for this expense. */
    fun deleteAttachmentGroup(expenseId: Long, groupId: String, context: Context) {
        scope.launch {
            val deleted = attachmentDao.deleteGroup(ExpensesAttachments.RECORD_TYPE, expenseId, groupId)
            deleted.forEach { AttachmentFileStore.delete(context, ExpensesAttachments.DIR, it.fileName) }
        }
    }

    // --- Proposals a record holds until someone accepts them (see :core:suggestions) ---

    /**
     * What is being offered for this record, live.
     *
     * Read only, deliberately. Taking a proposal puts its value in the draft the edit screen is
     * holding and nowhere else — that screen reaches the database through Save and through nothing
     * else, so a proposal taken and then abandoned is abandoned along with the rest of the edit.
     * See [com.voxapps.expenses.data.ExpenseSuggestionTarget], which refuses to write for the same
     * reason.
     */
    fun observeSuggestions(expenseId: Long): Flow<List<OfferedSuggestion>> =
        expensesRepo.suggestions.offered(expenseId)

    /** Refuse one. Disposes of whatever produced it, once its last proposal is gone. */
    fun dismissSuggestion(expenseId: Long, fieldKey: String) {
        scope.launch { expensesRepo.suggestions.dismiss(expenseId, fieldKey) }
    }

    /** Everything for this record, gone — which is what saving it means. */
    fun clearSuggestions(expenseId: Long) {
        scope.launch { expensesRepo.suggestions.clear(expenseId) }
    }
}
