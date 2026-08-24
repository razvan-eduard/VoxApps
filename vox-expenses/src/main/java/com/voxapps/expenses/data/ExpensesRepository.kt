package com.voxapps.expenses.data

import com.voxapps.datahygiene.RemapCondition
import com.voxapps.textmatch.extract.AccountIdentifiers
import com.voxapps.expenses.domain.accounts.BankAccounts
import android.content.Context
import com.voxapps.attachments.AttachmentDao
import com.voxapps.datahygiene.NameCasing
import com.voxapps.attachments.AttachmentEntity
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.attachments.AttachmentSource
import com.voxapps.datahygiene.DuplicateChecker
import com.voxapps.datahygiene.CategoryFallback
import com.voxapps.datahygiene.RemapValueKey
import com.voxapps.expenses.domain.rules.RuleAlert
import com.voxapps.datahygiene.RuleBasedDuplicateChecker
import com.voxapps.datahygiene.RuleCombinator
import com.voxapps.datahygiene.findDuplicate
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.domain.llm.SecondNotice
import com.voxapps.expenses.domain.llm.DuplicateGroup
import com.voxapps.expenses.domain.llm.ExpenseParseResultParser
import com.voxapps.fieldmemory.FieldCorrectionMemory
import com.voxapps.logging.Logger
import com.voxapps.textmatch.FuzzyNameMatcher
import com.voxapps.textmatch.extract.FieldCorrections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.voxapps.design.color.VoxColorPalette

/** [ExpensesRepository.addExpense]'s return value when the insert was skipped because
 *  [ExpenseDuplicateChecker] found an exact match already in the database — distinct from the
 *  generic `-1L` "insert threw" failure sentinel so callers can show a precise "Duplicate entry"
 *  message instead of a generic save-failed one. */
const val DUPLICATE_ENTRY_RESULT = -2L

/** [ExpensesRepository.addExpense]'s return value when no insert happened because
 *  [ExpenseNearDuplicateDetector] merged the candidate's extra data into an already-committed row
 *  instead — distinct from [DUPLICATE_ENTRY_RESULT] (a rejected, unchanged duplicate) since data here
 *  WAS preserved, just not as a new row. */
const val NEAR_DUPLICATE_MERGED_RESULT = -3L

/**
 * The row was already there — [Expense.uid] is unique, and something tried to write the same record
 * twice.
 *
 * Its own answer rather than a failure, because it is not one: the second write was refused exactly
 * as intended and the record the user wanted is present. Reporting it as "couldn't save" told people
 * their payment had been lost while it sat in the list in front of them. A capture can be answered
 * twice — the request queue re-sends a stored row rather than multiplying it, so one notification can
 * come back with two replies — and the duplicate insert is how that is caught.
 */
const val ALREADY_PRESENT_RESULT = -4L

/**
 * The outcomes that are not failures.
 *
 * Each means the record is already in the list — refused as a duplicate, folded into the one it
 * duplicated, or answered a second time for a capture already filed. Naming the set once is what
 * keeps a caller from testing for the two it happens to remember: the last sentinel added was
 * missed by exactly such a test, and a refused duplicate was reported to the user as a payment that
 * failed to save.
 */
val RECOGNIZED_NOT_INSERTED = setOf(
    DUPLICATE_ENTRY_RESULT, NEAR_DUPLICATE_MERGED_RESULT, ALREADY_PRESENT_RESULT
)

/**
 * Single write point over the Room DAOs.
 */

private const val TAG_ACCOUNTS = "BankAccounts"

class ExpensesRepository(
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao,
    private val lineItemDao: ExpenseLineItemDao,
    private val spendingLimitDao: SpendingLimitDao,
    private val remapRuleDao: RemapRuleDao,
    private val remapPatternSightingDao: RemapPatternSightingDao,
    private val appContext: Context,
    private val attachmentDao: AttachmentDao,
    private val duplicateRuleDao: DuplicateRuleDao,
    private val fieldCorrectionMemory: FieldCorrectionMemory,
    /** Notices that payments to a vendor keep coming back. Nothing it records changes this record;
     *  it only counts, and only a person turns a count into an arrangement. */
    private val recurringPayments: com.voxapps.expenses.domain.recurring.RecurringPaymentRepository? = null,
    private val bankAccountDao: BankAccountDao? = null,
    /**
     * Told when rules that asked to be heard recognised a newly captured record.
     *
     * Injected rather than called directly: that a rule fired is this class's business, that it
     * becomes a notification is not, and a repository that posts alerts cannot be tested without a
     * notification manager.
     */
    private val onRuleAlerts: (suspend (List<RuleAlert>) -> Unit)? = null
) {

    // --- cards and accounts money moved through (see BankAccount) ---

    val bankAccounts: Flow<List<BankAccount>> =
        bankAccountDao?.observeAll() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    val accountCurrencies: Flow<List<String>> =
        bankAccountDao?.observeCurrencies() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun bankAccountsSnapshot(): List<BankAccount> = bankAccountDao?.getAll().orEmpty()

    /**
     * The account [text] names, creating it where that is allowed — the one route both inputs take.
     *
     * Returns null whenever the text names no account, names more than one, or names an unfamiliar
     * one this source may not add. Null is the ordinary answer, not a failure: most captures never
     * carry an account's format at all.
     *
     * A known account seen more fully than before is widened rather than duplicated, so a card first
     * met as two digits in a notification becomes the full number once a receipt shows it, and the
     * next two-digit message still finds it.
     */
    suspend fun resolveBankAccount(
        text: String?,
        autoCreate: Boolean,
        defaultCurrency: String,
        bankName: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): Long? {
        val dao = bankAccountDao ?: return null
        val existing = dao.getAll()
        return when (val outcome = BankAccounts.resolve(text, existing)) {
            is BankAccounts.Outcome.None -> null
            is BankAccounts.Outcome.Known -> {
                val ref = AccountIdentifiers.single(text)
                if (ref != null && BankAccounts.widens(outcome.account, ref)) {
                    dao.update(outcome.account.copy(digits = ref.digits, kind = ref.kind.name))
                    Logger.d(TAG_ACCOUNTS, "Widened account ${outcome.account.id} to ${ref.kind}")
                }
                outcome.account.id
            }
            is BankAccounts.Outcome.Unknown -> {
                if (!autoCreate) return null
                val created = BankAccounts.newAccount(outcome.ref, defaultCurrency, bankName, nowMillis)
                // IGNORE on conflict, so a race between two captures naming one card leaves one row
                // rather than failing the second capture outright.
                val id = dao.insert(created)
                if (id > 0) id else dao.getAll().firstOrNull { it.asRef().sameAs(outcome.ref) }?.id
            }
        }
    }

    suspend fun addBankAccount(account: BankAccount): Long = bankAccountDao?.insert(account) ?: -1L

    /**
     * An account somebody typed rather than one a capture brought.
     *
     * Judged by the same reader the captures use, so a hand-made row is the same kind of thing as a
     * captured one and matches the same messages later. Refuses text that names no account, or more
     * than one. [BankAccount.autoCreated] stays false: the two are undone differently, since deleting
     * one a person made is deleting their work.
     */
    suspend fun addTypedBankAccount(text: String, currencyCode: String): Long {
        val dao = bankAccountDao ?: return -1L
        val ref = AccountIdentifiers.single(text) ?: return -1L
        dao.getAll().firstOrNull { it.asRef().sameAs(ref) }?.let { return it.id }
        return dao.insert(
            BankAccount(
                digits = ref.digits,
                kind = ref.kind.name,
                currencyCode = currencyCode,
                createdAt = System.currentTimeMillis(),
                autoCreated = false
            )
        )
    }

    suspend fun updateBankAccount(account: BankAccount) {
        bankAccountDao?.update(account)
    }

    /**
     * Removes the account and lets go of the records that pointed at it.
     *
     * The records stay: an expense happened whether or not the account it went through is still
     * listed, and deleting the row is a statement about the list rather than about the spending.
     */
    suspend fun deleteBankAccount(account: BankAccount) {
        bankAccountDao ?: return
        expenseDao.clearBankAccount(account.id)
        bankAccountDao.delete(account)
    }
    val expenses: Flow<List<Expense>> = expenseDao.observeAll()
    val expensesWithDetails: Flow<List<ExpenseWithDetails>> =
        expenseDao.observeExpensesWithDetails().distinctUntilChanged()
    val categories: Flow<List<Category>> = categoryDao.observeAll().distinctUntilChanged()
    val spendingLimits: Flow<List<SpendingLimit>> = spendingLimitDao.observeAll().distinctUntilChanged()

    /**
     * Where a proposal goes. Set once by the container rather than injected, because the target this
     * store consults reads records back through this repository — one of the two has to be late,
     * and nothing offers a suggestion while the graph is still being built.
     */
    lateinit var suggestions: com.voxapps.suggestions.SuggestionStore

    /**
     * Folds another announcement of an already-filed payment into the record it belongs to.
     *
     * Returns the id it folded into, or null when this is a payment of its own. Nothing is inserted
     * on the folding path — that is the point: a second announcement must never build a row and be
     * turned away, because being turned away is reported as a failure and because whichever message
     * happened to arrive first would otherwise decide what the record says.
     *
     * The merge itself is the one already used for near-duplicates, so the better-populated side
     * wins field by field regardless of order — the announcement naming the merchant supplies it
     * whether it came first or second.
     */
    suspend fun foldSecondNotice(candidate: Expense): Long? {
        val nearby = expenseDao.getForDateRange(
            candidate.dateTime - SecondNotice.WINDOW_MILLIS,
            candidate.dateTime + SecondNotice.WINDOW_MILLIS
        )
        val existing = nearby.firstOrNull { SecondNotice.isAnotherNoticeOf(it, candidate) } ?: return null
        val enriched = enrichWithNearDuplicate(existing, candidate)
        if (enriched !== existing) {
            expenseDao.update(enriched)
            Logger.d("ExpensesRepository", "A second notice of id=${existing.id} added what it was missing")
        } else {
            Logger.d("ExpensesRepository", "A second notice of id=${existing.id} added nothing new")
        }
        return existing.id
    }

    /** Builds the current duplicate checker from whatever rules are persisted right now — fetched
     *  fresh on every call rather than cached, since rules can change between checks and this
     *  repository already establishes the pattern of reading its own DAOs directly (never settings).
     *  [automaticOnly] narrows to rules with [DuplicateRuleEntity.appliesAutomatically] set — used for
     *  the insert-time silent check; manual/scheduled checks pass false to use every *enabled* rule
     *  regardless, since those are always staged for review (see [DuplicateRuleEntity]'s doc comment).
     *  Each rule fuzzy-matches its own string fields independently ([DuplicateRuleEntity.fuzzyMatchEnabled]
     *  is per-rule, not global) — evaluated one rule at a time against the field set matching its own
     *  fuzzy setting, then combined via [NearDuplicateConfig.globalCombinator], rather than the single
     *  shared field list [com.voxapps.datahygiene.RuleBasedDuplicateChecker] alone would allow. */
    private suspend fun buildDuplicateChecker(config: NearDuplicateConfig, automaticOnly: Boolean = false): DuplicateChecker<Expense> {
        val rules = duplicateRuleDao.getAll().filter { it.enabled && (!automaticOnly || it.appliesAutomatically) }
        val exactFields = ExpenseRuleFields(fuzzyMatchEnabled = false, timeWindowMillis = config.timeWindowMillis).all
        val fuzzyFields = ExpenseRuleFields(fuzzyMatchEnabled = true, timeWindowMillis = config.timeWindowMillis).all
        // Hoisted out of the lambda below: it runs once per expense *pair* in the O(n²) scans, so
        // building a checker per rule in there allocated O(n² · rules) of them for objects that are
        // identical every time.
        val ruleCheckers = rules.map { rule ->
            val fields = if (rule.fuzzyMatchEnabled) fuzzyFields else exactFields
            RuleBasedDuplicateChecker(fields, listOf(rule.toDuplicateRule()), RuleCombinator.OR)
        }
        return DuplicateChecker { candidate, existing ->
            // Unconditional, not opt-in per rule: an incoming top-up/refund and an outgoing payment of
            // the same amount are two different real transactions, never a duplicate, regardless of
            // which fields a user-configured rule happens to check. Confirmed on-device: a rule that
            // didn't explicitly include "direction" let a 1000 RON top-up and a 1000 RON payment merge.
            if (candidate.direction != existing.direction) return@DuplicateChecker false
            if (ruleCheckers.isEmpty()) return@DuplicateChecker false
            val results = ruleCheckers.map { it.isDuplicateOf(candidate, existing) }
            when (config.globalCombinator) {
                RuleCombinator.AND -> results.all { it }
                RuleCombinator.OR -> results.any { it }
            }
        }
    }

    suspend fun expensesSnapshot(): List<Expense> = expenseDao.getAll()

    suspend fun getExpenseById(id: Long): ExpenseWithDetails? = expenseDao.getWithDetailsById(id)

    /** The color of the most recent expense's category — see [VoxColorPalette.unusedOrRandomColor]'s
     *  `precedingColor` param. */
    suspend fun mostRecentCategoryColor(): Long? = expenseDao.getMostRecentCategoryColor()

    // --- Re-map rules (see RemapRuleEntity; the engine lives in :core:datahygiene) ---

    /**
     * The edit-pattern learner: called for every genuine manual edit-save (see ExpenseEditScreen's
     * save path), it records one sighting per changed non-numeric field — "in this record, [field]
     * was renamed from X to Y" — with every OTHER changed field of the same save carried as a
     * companion. One record contributes at most one sighting per pattern, however often it is
     * re-saved (REPLACE on the (pattern, record) key). When [threshold] distinct records exhibit
     * the exact same (field, X→Y) pair, a DISABLED rule is drafted: trigger = the field with X,
     * set = the field with Y, plus every companion that was edited to the same value in ALL
     * sightings (an inconsistent companion is a guess and stays out). The proposal never acts
     * until the user enables it in the rules list. Unconditional/pure: the caller
     * (ExpensesStateManager) gates on ExpensesSettings.remapProposalsEnabled.
     */
    suspend fun recordRemapPatternSightings(old: ExpenseWithDetails, new: Expense, threshold: Int) {
        val cats = categoryDao.getAll()
        fun catName(id: Long?): String? = id?.let { cid -> cats.firstOrNull { it.id == cid }?.name }

        // A PATTERN needs a real value on both sides — filling an empty field or clearing one is
        // not a rename and identifies nothing. A COMPANION only needs a changed, non-blank result:
        // setting the category on a previously uncategorized record during the same session is
        // exactly the habit a proposal should carry, so `before` stays nullable here and the
        // pattern loop below skips null-before edits without discarding them as companions.
        data class Edit(val before: String?, val after: String, val setFieldId: String, val setValue: String)
        val edits = mutableMapOf<String, Edit>()
        fun note(fieldId: String, before: String?, after: String?) {
            val b = before?.trim().takeUnless { it.isNullOrEmpty() }
            val a = after?.trim().takeUnless { it.isNullOrEmpty() } ?: return
            if (b == a) return
            edits[fieldId] = Edit(b, a, fieldId, a)
        }
        note(ExpenseRemapFields.ID_TITLE, old.expense.title, new.title)
        note(ExpenseRemapFields.ID_VENDOR, old.expense.vendor, new.vendor)
        note(ExpenseRemapFields.ID_BANK, old.expense.bank, new.bank)
        note(ExpenseRemapFields.ID_LOCATION, old.expense.location, new.location)
        note(ExpenseRemapFields.ID_COMMENTS, old.expense.comments, new.comments)
        val oldCat = catName(old.expense.categoryId)
        val newCat = catName(new.categoryId)
        if (newCat != null && oldCat != newCat) {
            // Category triggers on the NAME (all a pre-resolution draft has) but sets the ID.
            edits[ExpenseRemapFields.ID_CATEGORY] =
                Edit(oldCat, newCat, ExpenseRemapFields.ID_CATEGORY_ID, new.categoryId.toString())
        }
        if (edits.isEmpty()) return

        // What identifies the record is what the rule triggers on, and that is the merchant.
        //
        // A field somebody corrected cannot be the trigger. Changing a category and having the rule
        // come out as "old category → new category" reads as nonsense because it is: the old
        // category is what the app guessed, not something true about the spending, and a rule keyed
        // on a guess fires on every record that guess was ever wrong about. The vendor is the thing
        // the record IS; everything corrected alongside it is what should follow from it.
        //
        // No vendor, no proposal. A capture with nothing to identify it has nothing a later capture
        // could be recognised by, and a rule that cannot recognise anything is worse than none.
        // The vendor AS THE CAPTURE CARRIED IT, before anything was corrected — that is what a later
        // capture of the same shop will arrive with, and therefore the only thing a rule can
        // recognise it by. Renaming the vendor is then an ordinary set like any other: trigger on
        // the name that arrives, write the name you use.
        val vendor = old.expense.vendor?.trim()?.takeUnless { it.isEmpty() } ?: return
        val triggerValue = RemapValueKey.normalize(vendor) ?: return
        val setsFromEdits = edits
        if (setsFromEdits.isEmpty()) return

        // What the record carried in the fields nobody touched. These are not conditions and not
        // results — they are the other true things about the capture, and the only honest use for
        // them is to offer them. A corrected field is excluded: its value is what the correction
        // produced, so triggering on it would ask a later capture to already be fixed.
        val observed = ExpenseRemapFields.matchFields
            .filter { it.id != ExpenseRemapFields.ID_VENDOR && it.id !in edits }
            .mapNotNull { field ->
                field.valueOf(
                    ExpenseRemapFields.Draft(
                        totalAmount = old.expense.totalAmount,
                        title = old.expense.title, vendor = old.expense.vendor,
                        bank = old.expense.bank, location = old.expense.location,
                        comments = old.expense.comments, category = oldCat
                    )
                )?.let { v -> RemapValueKey.normalize(v)?.let { field.id to it } }
            }.toMap()

        val now = System.currentTimeMillis()
        val patternKey = "${ExpenseRemapFields.ID_VENDOR}\u0000$triggerValue"
        val proposed = setsFromEdits.map { (_, e) -> e.setFieldId to e.setValue }.toMap()
        remapPatternSightingDao.upsert(
            RemapPatternSighting(
                patternKey = patternKey, recordId = new.id,
                fieldId = ExpenseRemapFields.ID_VENDOR,
                beforeText = vendor, afterText = vendor,
                setFieldId = proposed.keys.first(), setValue = proposed.values.first(),
                companionsJson = RemapRuleJson.encode(proposed), createdAt = now,
                observedJson = RemapRuleJson.encode(observed)
            )
        )
        val sightings = remapPatternSightingDao.getForPattern(patternKey)
        if (sightings.size < threshold) return

        val trigger = RemapConditionsJson.encode(
            listOf(listOf(RemapCondition(ExpenseRemapFields.ID_VENDOR, triggerValue)))
        )
        if (remapRuleDao.getByMatch(trigger) == null) {
            // A set survives only where every sighting made the same edit. One person changing a
            // category twice for the same shop is a habit; changing it to something different each
            // time is not, and proposing either would be proposing a coin toss.
            val consistent = sightings
                .map { RemapRuleJson.decode(it.companionsJson) }
                .reduce { acc, m -> acc.filter { (k, v) -> m[k] == v } }
            // Only what every sighting carried is offered. A value one of them had and another did
            // not is evidence against narrowing on it, not for.
            val shared = sightings
                .map { RemapRuleJson.decode(it.observedJson) }
                .reduce { acc, m -> acc.filter { (k, v) -> m[k] == v } }
            if (consistent.isNotEmpty()) {
                remapRuleDao.upsert(
                    RemapRuleEntity(
                        name = vendor,
                        matchJson = trigger,
                        setJson = RemapRuleJson.encode(consistent),
                        origin = RemapRuleEntity.ORIGIN_PROPOSED,
                        enabled = false,
                        sortOrder = remapRuleDao.getAll().size,
                        updatedAt = now,
                        suggestJson = RemapRuleJson.encode(shared)
                    )
                )
            }
        }
        remapPatternSightingDao.deleteForPattern(patternKey)
    }

    /**
     * A rename someone accepted: from now on this spelling becomes the name they already use.
     *
     * Enabled, unlike the rules the edit-pattern learner drafts, and the difference is who decided.
     * A drafted rule is the app saying it noticed a habit and waiting to be told it was right; this
     * one is the answer to a question that was asked and returned. Asking again, in a list, for a
     * confirmation already given is how a proposal becomes a chore.
     *
     * Exact match on the trigger, never fuzzy — the resemblance was judged once, by a person, and
     * baking it into a standing rule would apply that judgement to names nobody looked at.
     */
    suspend fun addAcceptedRename(fieldId: String, from: String, to: String) {
        val matchJson = RemapConditionsJson.encode(
            listOf(listOf(RemapCondition(fieldId, RemapValueKey.normalize(from) ?: return)))
        )
        if (remapRuleDao.getByMatch(matchJson) != null) return
        remapRuleDao.upsert(
            RemapRuleEntity(
                name = "$from → $to",
                matchJson = matchJson,
                setJson = RemapRuleJson.encode(mapOf(fieldId to to)),
                origin = RemapRuleEntity.ORIGIN_PROPOSED,
                enabled = true,
                sortOrder = remapRuleDao.getAll().size,
                updatedAt = System.currentTimeMillis()
            )
        )
        Logger.d("ExpensesRepository", "Accepted rename: $fieldId '$from' -> '$to'")
    }

    suspend fun remapRulesSnapshot(): List<RemapRuleEntity> = remapRuleDao.getAll()

    /** Restore-side merge by matchJson — an imported rule updates the existing row for the same
     *  trigger (the update-in-place the duplicate-rules restore does), or inserts fresh. */
    suspend fun mergeRemapRule(rule: RemapRuleEntity) {
        val existing = remapRuleDao.getByMatch(rule.matchJson)
        if (existing == null) {
            remapRuleDao.upsert(rule.copy(id = 0))
        } else {
            remapRuleDao.update(
                existing.copy(name = rule.name, setJson = rule.setJson, fuzzJson = rule.fuzzJson, suggestJson = rule.suggestJson, alertEnabled = rule.alertEnabled, enabled = rule.enabled, sortOrder = rule.sortOrder, updatedAt = rule.updatedAt)
            )
        }
    }

    fun observeRemapRules(): Flow<List<RemapRuleEntity>> = remapRuleDao.observeAll()

    suspend fun upsertRemapRule(rule: RemapRuleEntity) = remapRuleDao.upsert(rule)

    suspend fun deleteRemapRule(rule: RemapRuleEntity) = remapRuleDao.delete(rule)

    suspend fun reorderRemapRules(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> remapRuleDao.setSortOrder(id, index) }
    }

    suspend fun setAllRemapRulesEnabled(enabled: Boolean) = remapRuleDao.setAllEnabled(enabled)

    suspend fun deleteAllRemapRules() = remapRuleDao.deleteAll()

    /** Called only for a genuine manual edit-save (see ExpenseEditScreen's save path) — the diff
     *  rules in FieldCorrections decide what, if anything, the edit teaches. Unconditional/pure:
     *  the caller (ExpensesStateManager) gates on ExpensesSettings.fieldCorrectionMemoryEnabled,
     *  matching the convention that this repository never reads settings itself. */
    suspend fun recordFieldCorrections(old: ExpenseWithDetails, new: Expense, newItems: List<ExpenseLineItem>) {
        val oldItems = old.items.sortedBy { it.position }
        val oldFields = mutableListOf(old.expense.title, old.expense.vendor, old.expense.bank, old.expense.location, old.expense.comments)
        val newFields = mutableListOf(new.title, new.vendor, new.bank, new.location, new.comments)
        // Item lists pair by position, and only when no item was added or removed — a shifted list
        // pairs unrelated names, which is exactly the mislabel the equal-size rule removes.
        if (oldItems.size == newItems.size) {
            oldFields += oldItems.map { it.name }
            newFields += newItems.map { it.name }
        }
        fieldCorrectionMemory.learn(oldFields, newFields)
    }

    suspend fun learnedFieldCorrectionsSnapshot() = fieldCorrectionMemory.snapshot()

    suspend fun restoreLearnedFieldCorrection(row: com.voxapps.fieldmemory.LearnedFieldCorrection) =
        fieldCorrectionMemory.restore(row)

    // --- Peer-to-peer sync (see :core:datahygiene's SyncMerge and ExpensesSyncHandler) ---

    suspend fun tombstonesSince(since: Long): List<ExpenseTombstone> = expenseDao.getTombstonesSince(since)

    suspend fun getIdByUid(uid: String): Long? = expenseDao.getIdByUid(uid)

    /** Insert-side of a sync merge: preserves [expense]'s uid/updatedAt verbatim — unlike [addExpense],
     *  which always mints a fresh uid and stamps updatedAt to "now" (correct for a locally *created*
     *  row, wrong for one being replicated from a peer that already has real sync identity). [items]
     *  travel with the expense rather than getting their own sync identity — see
     *  [ExpensesSyncHandler]'s doc comment for why that's the correct model, not a shortcut. */
    suspend fun insertSyncedExpense(expense: Expense, items: List<ExpenseLineItem> = emptyList()): Long {
        val id = expenseDao.insert(expense.copy(id = 0))
        if (id > 0 && items.isNotEmpty()) {
            lineItemDao.insertAll(items.mapIndexed { index, item -> item.copy(id = 0, expenseId = id, position = index) })
        }
        return id
    }

    /** Update-side of a sync merge: [expense] must already carry the *local* row's id (resolved via
     *  [getIdByUid] before calling this) — every other field, including updatedAt, comes from the
     *  peer's newer version, since it won the last-write-wins comparison that got us here. [items]
     *  unconditionally replace the local set (an empty list is a valid, correct result — it means the
     *  peer's current state for this expense genuinely has no line items), mirroring [updateExpense]'s
     *  delete-then-reinsert pattern exactly. */
    suspend fun updateSyncedExpense(expense: Expense, items: List<ExpenseLineItem> = emptyList()) {
        expenseDao.update(expense)
        lineItemDao.deleteAllForExpense(expense.id)
        if (items.isNotEmpty()) {
            lineItemDao.insertAll(items.mapIndexed { index, item -> item.copy(id = 0, expenseId = expense.id, position = index) })
        }
    }

    /** Applies an incoming sync tombstone: deletes the local row by uid (a no-op if it's already
     *  gone or was never synced here) via the normal [deleteExpenseById] path, so a fresh local
     *  tombstone is written too — letting the deletion propagate transitively to a third device. */
    suspend fun deleteExpenseByUid(uid: String) {
        val id = expenseDao.getIdByUid(uid) ?: return
        deleteExpenseById(id)
    }

    suspend fun expensesForDateRange(from: Long, to: Long): List<Expense> = expenseDao.getForDateRange(from, to)

    suspend fun addExpense(
        title: String?,
        totalAmount: Double,
        currencyCode: String,
        vendor: String?,
        bank: String?,
        location: String?,
        dateTime: Long,
        comments: String?,
        categoryId: Long?,
        items: List<ExpenseLineItem> = emptyList(),
        imageName: String? = null,
        isStub: Boolean = false,
        createdAt: Long = System.currentTimeMillis(),
        // Hub import is the one caller that must pass false: it inserts every imported row BEFORE
        // deleting the pre-existing rows it's replacing (see ExpensesExportImportHandler — order
        // matters there for its own createdAt-based cleanup), so the old rows are still present
        // during the insert loop and would otherwise get misdetected as duplicates of the very
        // rows they're about to be replaced by. Matches RecordSource.HUB_IMPORT's documented
        // "never touched, another install's already-validated data" rule in :core:datahygiene.
        checkForDuplicate: Boolean = true,
        direction: TransactionDirection = TransactionDirection.OUTGOING,
        nearDuplicateCheckEnabled: Boolean = false,
        nearDuplicateConfig: NearDuplicateConfig = NearDuplicateConfig(
            timeWindowMillis = TimeUnit.MINUTES.toMillis(ExpensesSettings.NEAR_DUP_DEFAULT_WINDOW_MINUTES.toLong())
        ),
        source: ExpenseSource = ExpenseSource.MANUAL,
        // Only Hub import passes true — preserving the flag from the source device's row, since it
        // was already a genuine human edit there. Every other caller creates a brand-new record no
        // one has touched yet.
        manuallyEdited: Boolean = false,
        previousBalanceAmount: Double? = null,
        invoiceOwnAmount: Double? = null,
        /** The card or account the source text named — see [com.voxapps.expenses.domain.accounts.BankAccounts]. */
        bankAccountId: Long? = null
    ): Long {
        return try {
            val candidate = Expense(
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                totalAmount = totalAmount,
                previousBalanceAmount = previousBalanceAmount,
                invoiceOwnAmount = invoiceOwnAmount,
                currencyCode = currencyCode,
                vendor = vendor?.trim()?.takeIf { it.isNotEmpty() },
                bank = bank?.trim()?.takeIf { it.isNotEmpty() },
                bankAccountId = bankAccountId,
                location = location?.trim()?.takeIf { it.isNotEmpty() },
                dateTime = dateTime,
                comments = comments?.trim()?.takeIf { it.isNotEmpty() },
                categoryId = categoryId,
                direction = direction,
                receiptImageName = imageName,
                isStub = isStub,
                createdAt = createdAt,
                source = source,
                manuallyEdited = manuallyEdited
            )

            if (checkForDuplicate && nearDuplicateCheckEnabled) {
                val nearby = expenseDao.getForDateRange(
                    dateTime - nearDuplicateConfig.timeWindowMillis,
                    dateTime + nearDuplicateConfig.timeWindowMillis
                )
                val checker = buildDuplicateChecker(nearDuplicateConfig, automaticOnly = true)
                val match = checker.findDuplicate(candidate, nearby)
                if (match != null) {
                    val enriched = enrichWithNearDuplicate(match, candidate)
                    if (enriched !== match) expenseDao.update(enriched)
                    // The candidate itself is never persisted as its own row here — its receipt photo
                    // (if any) either got adopted onto the existing match (track it there) or is now
                    // orphaned with nothing else able to reference it (safe to delete outright).
                    if (imageName != null) {
                        if (enriched.receiptImageName == imageName) {
                            attachmentDao.insert(
                                AttachmentEntity(
                                    recordType = ExpensesAttachments.RECORD_TYPE,
                                    recordId = match.id,
                                    fileName = imageName,
                                    source = AttachmentSource.SCANNED,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                        } else {
                            deleteReceiptFileRaw(imageName)
                        }
                    }
                    return if (enriched === match) {
                        Logger.w("ExpensesRepository", "Duplicate entry — skipping insert (matches existing id=${match.id})")
                        DUPLICATE_ENTRY_RESULT
                    } else {
                        Logger.w("ExpensesRepository", "Near-duplicate merged into existing id=${match.id}")
                        NEAR_DUPLICATE_MERGED_RESULT
                    }
                }
            }

            val id = expenseDao.insert(candidate)
            if (id > 0) {
                Logger.d("ExpensesRepository", "DB Insert SUCCESS - ID: $id")
                // Counted after the write, never before: a payment that failed to store is not a
                // payment, and evidence gathered from one would propose an arrangement built on a
                // record nobody has. Failure here must not lose the expense either, which is why it
                // is guarded — noticing a pattern is worth nothing beside keeping what was spent.
                if (candidate.direction == TransactionDirection.OUTGOING) {
                    runCatching {
                        recurringPayments?.observe(
                            vendor = candidate.vendor,
                            atMillis = candidate.dateTime,
                            amount = candidate.totalAmount,
                            currency = candidate.currencyCode,
                            categoryId = candidate.categoryId
                        )
                    }.onFailure { Logger.w("ExpensesRepository", "Recurrence bookkeeping failed: ${it.message}") }
                }
                if (items.isNotEmpty()) {
                    lineItemDao.insertAll(items.mapIndexed { index, item -> item.copy(id = 0, expenseId = id, position = index) })
                }
                if (imageName != null) {
                    attachmentDao.insert(
                        AttachmentEntity(
                            recordType = ExpensesAttachments.RECORD_TYPE,
                            recordId = id,
                            fileName = imageName,
                            source = AttachmentSource.SCANNED,
                            createdAt = createdAt
                        )
                    )
                }
            } else {
                Logger.e("ExpensesRepository", "DB Insert returned invalid ID: $id")
            }
            id
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // A uid clash means this exact record is already stored; anything else that violates a
            // constraint is a real failure and falls through to the generic arm below.
            Logger.d("ExpensesRepository", "Insert refused — this record is already present")
            ALREADY_PRESENT_RESULT
        } catch (e: Exception) {
            Logger.e("ExpensesRepository", "DB Insert FAILED", e)
            -1L
        }
    }

    /** Bumps [Expense.updatedAt] to now — never trust a caller-supplied value here, since that's
     *  exactly the field peer-to-peer sync's last-write-wins conflict resolution relies on.
     *  [markManuallyEdited] should be true only for a genuine human edit (the manual edit screen's
     *  Save button) — an LLM-driven rewrite (e.g. the scan "retry cleanup" path) is not a human
     *  confirming the data is correct, so it must not set [Expense.manuallyEdited], which
     *  [dataScore] treats as an unconditional trust pin. */
    suspend fun updateExpense(expense: Expense, items: List<ExpenseLineItem>, markManuallyEdited: Boolean = false) {
        val updated = expense.copy(
            updatedAt = System.currentTimeMillis(),
            manuallyEdited = expense.manuallyEdited || markManuallyEdited
        )
        expenseDao.update(updated)
        lineItemDao.deleteAllForExpense(expense.id)
        if (items.isNotEmpty()) {
            lineItemDao.insertAll(items.mapIndexed { index, item -> item.copy(id = 0, expenseId = expense.id, position = index) })
        }
    }

    suspend fun deleteExpense(expense: Expense) {
        expenseDao.delete(expense)
        expenseDao.insertTombstone(ExpenseTombstone(expense.uid, System.currentTimeMillis()))
        deleteAttachmentsFor(expense.id)
    }

    suspend fun deleteExpenseById(id: Long) {
        val expense = expenseDao.getWithDetailsById(id)?.expense
        expenseDao.deleteById(id)
        if (expense != null) {
            expenseDao.insertTombstone(ExpenseTombstone(expense.uid, System.currentTimeMillis()))
        }
        deleteAttachmentsFor(id)
    }

    suspend fun deleteAllExpenses() {
        val all = expensesSnapshot()
        expenseDao.deleteAll()
        val now = System.currentTimeMillis()
        expenseDao.insertTombstones(all.map { ExpenseTombstone(it.uid, now) })
        all.forEach { deleteAttachmentsFor(it.id) }
    }

    /** Best-effort cleanup of every attachment on a deleted expense — both manually-added ones
     *  (filesDir/attachments/) and the original receipt scan (filesDir/receipts/, distinguished by
     *  [AttachmentSource.SCANNED]), now that both are rows in the same table. Checks
     *  [AttachmentDao.countByFileName] before touching a file — an import's insert-then-delete-old
     *  reconciliation can re-insert a row under a new id while reusing an old row's fileName, and
     *  [applyExpenseDeduplication] reassigns a merge-adopted receipt's row onto the keeper — so this
     *  never deletes a file another row still needs. A file-delete failure never blocks/rolls back
     *  the DB delete — an orphan file is a far cheaper failure mode than a stuck delete. */
    /**
     * The photographs one rescan was made from, gone.
     *
     * Called when the last suggestion carrying that scan's tag is dismissed — the scan has nothing
     * left to offer, and left attached it is indistinguishable from one somebody took on purpose.
     * A file another row still references is kept, and a failed file delete never blocks the row
     * delete: an orphan file is the cheaper failure.
     */
    suspend fun deleteAttachmentGroup(expenseId: Long, groupId: String) {
        val rows = attachmentDao.deleteGroup(ExpensesAttachments.RECORD_TYPE, expenseId, groupId)
        for (row in rows) {
            try {
                if (attachmentDao.countByFileName(ExpensesAttachments.RECORD_TYPE, row.fileName) == 0) {
                    val dir = if (row.source == AttachmentSource.SCANNED) "receipts" else ExpensesAttachments.DIR
                    AttachmentFileStore.delete(appContext, dir, row.fileName)
                }
            } catch (e: Exception) {
                Logger.w("ExpensesRepository", "Failed to delete a rescan's file for expense $expenseId", e)
            }
        }
    }

    private suspend fun deleteAttachmentsFor(expenseId: Long) {
        val rows = attachmentDao.deleteAllFor(ExpensesAttachments.RECORD_TYPE, expenseId)
        for (row in rows) {
            try {
                if (attachmentDao.countByFileName(ExpensesAttachments.RECORD_TYPE, row.fileName) == 0) {
                    val dir = if (row.source == AttachmentSource.SCANNED) "receipts" else ExpensesAttachments.DIR
                    AttachmentFileStore.delete(appContext, dir, row.fileName)
                }
            } catch (e: Exception) {
                Logger.w("ExpensesRepository", "Failed to delete attachment file for expense $expenseId", e)
            }
        }
    }

    /** Deletes a scanned receipt's file(s) with no reference-count guard — safe only for a
     *  near-duplicate candidate merged away in [addExpense] without ever becoming its own persisted
     *  row (see that branch's own comment): nothing else can reference a filename that was never
     *  attached to any row in the first place. Every other deletion path goes through
     *  [deleteAttachmentsFor]'s guarded, table-backed cleanup instead. */
    private fun deleteReceiptFileRaw(name: String) {
        try {
            AttachmentFileStore.delete(appContext, "receipts", name)
        } catch (e: Exception) {
            Logger.w("ExpensesRepository", "Failed to delete receipt file(s) for $name", e)
        }
    }

    suspend fun addParsedExpense(
        title: String?,
        totalAmount: Double,
        currencyCode: String,
        vendor: String?,
        bank: String?,
        location: String?,
        comments: String?,
        dateTime: Long,
        spokenCategory: String?,
        defaultCategoryId: Long?,
        autoCreate: Boolean,
        items: List<ExpenseLineItem> = emptyList(),
        imageName: String? = null,
        direction: TransactionDirection = TransactionDirection.OUTGOING,
        nearDuplicateCheckEnabled: Boolean = false,
        nearDuplicateConfig: NearDuplicateConfig = NearDuplicateConfig(
            timeWindowMillis = TimeUnit.MINUTES.toMillis(ExpensesSettings.NEAR_DUP_DEFAULT_WINDOW_MINUTES.toLong())
        ),
        correctionsEnabled: Boolean = false,
        correctionsThreshold: Int = ExpensesSettings.CORRECTION_SPEED_MEDIUM,
        correctionsApplyMode: String = ExpensesSettings.CORRECTION_APPLY_SUGGEST,
        source: ExpenseSource = ExpenseSource.VOICE,
        previousBalanceAmount: Double? = null,
        invoiceOwnAmount: Double? = null,
        /** The card or account the source text named — see [com.voxapps.expenses.domain.accounts.BankAccounts]. */
        bankAccountId: Long? = null
    ): Long {
        // Learned spelling corrections run before anything reads the text fields, so a learned
        // merchant mapping keyed on the clean vendor spelling still fires on a garbled arrival.
        // Exact-tier corrections rewrite silently only in AUTO mode; in SUGGEST mode, and for
        // fuzzy-tier resemblance hits in EITHER mode, the corrected text is offered as a tappable
        // suggestion on the created record instead (see FieldCorrections' two-tier doc).
        var effTitle = title; var effVendor = vendor; var effBank = bank
        var effLocation = location; var effComments = comments; var effItems = items
        var suggested: List<String?>? = null
        var suggestedItems: List<ExpenseLineItem>? = null
        if (correctionsEnabled) {
            val corrections = fieldCorrectionMemory.activeCorrections(correctionsThreshold)
            if (corrections.isNotEmpty()) {
                val exact = listOf(title, vendor, bank, location, comments)
                    .map { FieldCorrections.apply(it, corrections) }
                val exactItems = items.map { it.copy(name = FieldCorrections.apply(it.name, corrections) ?: it.name) }
                if (correctionsApplyMode == ExpensesSettings.CORRECTION_APPLY_AUTO) {
                    effTitle = exact[0]; effVendor = exact[1]; effBank = exact[2]
                    effLocation = exact[3]; effComments = exact[4]; effItems = exactItems
                }
                suggested = exact.map { f ->
                    FieldCorrections.applyHits(f, FieldCorrections.fuzzyCandidates(f, corrections))
                }
                suggestedItems = exactItems.map { item ->
                    item.copy(name = FieldCorrections.applyHits(item.name, FieldCorrections.fuzzyCandidates(item.name, corrections)) ?: item.name)
                }
            }
        }

        val cats = categoryDao.getAll()

        // Re-map rules run on the corrected text (spelling repair first, value semantics second)
        // and BEFORE category resolution: a rule-set category is a total short-circuit overriding
        // whatever the LLM/spoken category or configured default would suggest. An enabled rule is
        // standing intent and always applies — proposals sit disabled until the user enables them.
        // A rule pointing at a since-deleted category declines in the setter and resolution
        // proceeds normally.
        var resolved: FuzzyNameMatcher.Resolved? = null
        var alerting: List<RemapRuleEntity> = emptyList()
        run {
            val enabled = remapRuleDao.getAll().filter { it.enabled }
            val active = enabled.map { it.toRemapRule() }
            if (active.isNotEmpty()) {
                val outcome = ExpenseRemapFields.engine(cats).evaluate(
                    ExpenseRemapFields.Draft(
                        totalAmount, effTitle, effVendor, effBank, effLocation, effComments, spokenCategory
                    ),
                    active
                )
                val draft = outcome.record
                // A rule that asked to be heard is answered after the record exists, so the alert
                // can point at something real.
                val byId = enabled.associateBy { it.id }
                alerting = outcome.fired.filter { it.alert }.mapNotNull { byId[it.id] }
                effTitle = draft.title; effVendor = draft.vendor; effBank = draft.bank
                effLocation = draft.location; effComments = draft.comments
                draft.categoryId?.let { id -> cats.firstOrNull { it.id == id } }?.let {
                    resolved = FuzzyNameMatcher.Resolved(it.id, it.name)
                }
            }
        }
        if (resolved == null) {
            resolved = FuzzyNameMatcher.resolve(
                spokenName = spokenCategory,
                candidates = cats.map { FuzzyNameMatcher.Candidate(it.id, it.name) },
                defaultId = defaultCategoryId
            )
        }

        val spoken = spokenCategory?.trim()?.takeIf { it.isNotEmpty() }
        if (resolved.id == null && autoCreate && spoken != null) {
            val precedingColor = expenseDao.getMostRecentCategoryColor()
            val id = addCategory(spoken, VoxColorPalette.unusedOrRandomColor(cats.map { it.colorArgb }, precedingColor), cats.size, dateTime)
            if (id > 0) resolved = FuzzyNameMatcher.Resolved(id, spoken)
        }

        val newId = addExpense(
            effTitle, totalAmount, currencyCode, effVendor, effBank, effLocation, dateTime, effComments, resolved.id, effItems, imageName,
            direction = direction,
            nearDuplicateCheckEnabled = nearDuplicateCheckEnabled,
            nearDuplicateConfig = nearDuplicateConfig,
            source = source,
            previousBalanceAmount = previousBalanceAmount,
            invoiceOwnAmount = invoiceOwnAmount,
            bankAccountId = bankAccountId
        )

        if (newId > 0 && alerting.isNotEmpty()) {
            onRuleAlerts?.invoke(
                alerting.map { rule ->
                    RuleAlert(
                        ruleId = rule.id,
                        ruleName = rule.name,
                        expenseId = newId,
                        vendor = effVendor,
                        amount = totalAmount,
                        currency = currencyCode
                    )
                }
            )
        }

        // Whatever correction text was NOT written into the row (all of it in SUGGEST mode, the
        // fuzzy tier in AUTO mode) becomes a per-field suggestion. Upserted with REPLACE, so a
        // later receipt rescan's suggestion overwrites this one — newest wins, same lifecycle.
        if (newId > 0 && suggested != null) {
            val inserted = listOf(effTitle, effVendor, effBank, effLocation, effComments)
            val diff = suggested.mapIndexed { i, s -> s?.takeIf { it != inserted[i] } }
            val itemsDiffer = suggestedItems != null && suggestedItems.map { it.name } != effItems.map { it.name }
            if (diff.any { it != null } || itemsDiffer) {
                suggestions.offer(
                    newId,
                    mapOf(
                        ExpenseSuggestionTarget.KEY_TITLE to diff[0],
                        ExpenseSuggestionTarget.KEY_VENDOR to diff[1],
                        ExpenseSuggestionTarget.KEY_BANK to diff[2],
                        ExpenseSuggestionTarget.KEY_LOCATION to diff[3],
                        ExpenseSuggestionTarget.KEY_COMMENTS to diff[4],
                        ExpenseSuggestionTarget.KEY_ITEMS to if (itemsDiffer) PendingLineItemsJson.encode(
                            suggestedItems.map {
                                ExpenseParseResultParser.ParsedItem(
                                    it.name, it.quantity, it.unitPrice, it.netAmount, it.vatAmount, it.grossAmount
                                )
                            }
                        ) else null
                    ).filterValues { it != null }
                )
            }
        }
        return newId
    }

    suspend fun addCategory(
        name: String,
        colorArgb: Long,
        position: Int,
        createdAt: Long,
        icon: String? = null
    ): Long {
        // Cased here rather than at each caller: a category is created from a typed name, a spoken
        // one and a synced one, and a list is only uniform if every route through it agrees.
        val clean = NameCasing.titleCased(name).orEmpty()
        if (clean.isEmpty()) return -1
        // The very first category becomes the fallback, so an install always has one without anybody
        // being asked to choose. See [Category.isDefault].
        val isFirst = categoryDao.getAll().isEmpty()
        return categoryDao.insert(
            Category(
                name = clean,
                colorArgb = colorArgb,
                position = position,
                createdAt = createdAt,
                isDefault = isFirst,
                icon = icon
            )
        )
    }

    /** The category a record falls back to, or null on an install with no categories at all. */
    suspend fun defaultCategory(): Category? =
        categoryDao.getAll().let { all -> all.firstOrNull { it.isDefault } ?: all.minByOrNull { it.position } }

    /**
     * Moves the fallback, keeping exactly one.
     *
     * The invariant lives here rather than in the schema, as the calendar's does: a unique index
     * would make the intermediate state of any move illegal, and there is no moment at which having
     * none is acceptable either.
     */
    suspend fun setDefaultCategory(categoryId: Long) {
        val all = categoryDao.getAll()
        if (all.none { it.id == categoryId }) return
        for (category in all) {
            val shouldBeDefault = category.id == categoryId
            if (category.isDefault != shouldBeDefault) {
                categoryDao.update(category.copy(isDefault = shouldBeDefault))
            }
        }
    }

    suspend fun updateCategory(category: Category) =
        categoryDao.update(category.copy(name = NameCasing.titleCased(category.name) ?: category.name))

    suspend fun deleteCategory(category: Category) {
        // The fallback is never deleted — there has to be somewhere for a record with no opinion to
        // land, and silently choosing a new one would move every future record without saying so.
        if (category.isDefault) return
        // The records outlive the category. They land on the fallback — the category records with no
        // opinion are filed under — rather than being left with none at all: a record with a null
        // category drops out of every per-category total and reads as though it was never filed,
        // when what actually happened is that somebody deleted a label.
        val fallback = CategoryFallback.destinationFor(
            categories = categoryDao.getAll(),
            deletingId = category.id,
            idOf = { it.id },
            isFallback = { it.isDefault }
        )
        if (fallback != null) expenseDao.reassignCategory(category.id, fallback.id)
        else expenseDao.clearCategory(category.id)
        spendingLimitDao.clearCategory(category.id)
        // Referential cleanup mirroring the old memory's clearCategory: rules lose the set-entry
        // pointing at the deleted category, and a rule with nothing left to set is deleted — every
        // LEARNED rule is, since category is all it ever sets; an authored rule that also rewrites
        // other fields survives minus its category entry.
        for (rule in remapRuleDao.getAll()) {
            val set = RemapRuleJson.decode(rule.setJson)
            if (set[ExpenseRemapFields.ID_CATEGORY_ID] != category.id.toString()) continue
            val remaining = set - ExpenseRemapFields.ID_CATEGORY_ID
            if (remaining.isEmpty()) remapRuleDao.delete(rule)
            else remapRuleDao.update(rule.copy(setJson = RemapRuleJson.encode(remaining), updatedAt = System.currentTimeMillis()))
        }
        categoryDao.delete(category)
    }

    suspend fun addSpendingLimit(
        categoryId: Long?,
        amountHomeCurrency: Double,
        period: String,
        createdAt: Long = System.currentTimeMillis()
    ): Long =
        spendingLimitDao.insert(
            SpendingLimit(categoryId = categoryId, amountHomeCurrency = amountHomeCurrency, period = period, createdAt = createdAt)
        )

    suspend fun deleteSpendingLimit(limit: SpendingLimit) = spendingLimitDao.delete(limit)

    suspend fun applyCategoryMerge(mapping: Map<String, String>) {
        val cats = categoryDao.getAll()
        for ((oldName, canonicalName) in mapping) {
            if (oldName.equals(canonicalName, ignoreCase = true)) continue
            val old = cats.firstOrNull { it.name.equals(oldName, ignoreCase = true) } ?: continue
            val canonical = cats.firstOrNull { it.name.equals(canonicalName, ignoreCase = true) } ?: continue
            expenseDao.reassignCategory(old.id, canonical.id)
            categoryDao.delete(old)
        }
    }

    /** Before deleting the non-kept rows, backfills the kept row's blank fields from theirs (see
     *  [enrichWithNearDuplicate]), preferring whichever member actually has the better data per
     *  [Expense.dataScore] rather than always keeping the kept row's own values untouched — approving
     *  a review group used to just discard every field the discarded rows had. */
    suspend fun applyExpenseDeduplication(groups: List<DuplicateGroup>) {
        for (g in groups) {
            val keeper = expenseDao.getWithDetailsById(g.keepId)?.expense ?: continue
            val others = g.duplicateIds.filter { it != g.keepId }.mapNotNull { expenseDao.getWithDetailsById(it)?.expense }
            val merged = others.fold(keeper) { acc, other -> enrichWithNearDuplicate(acc, other) }
            if (merged != keeper) {
                expenseDao.update(merged)
                // A blank receiptImageName can only ever be filled in by enrichWithNearDuplicate,
                // never overwritten (see its own doc comment) — so if it changed at all, exactly one
                // losing row donated it. Move that row's attachment record onto the keeper too,
                // otherwise the cleanup below (deleteAttachmentsFor per loser) would delete the file
                // this row now solely depends on.
                if (merged.receiptImageName != null && merged.receiptImageName != keeper.receiptImageName) {
                    val donorId = others.firstOrNull { it.receiptImageName == merged.receiptImageName }?.id
                    if (donorId != null) {
                        attachmentDao.reassignRecordId(ExpensesAttachments.RECORD_TYPE, donorId, g.keepId, merged.receiptImageName!!)
                    }
                }
            }
        }
        val idsToDelete = groups.flatMap { g -> g.duplicateIds.filter { it != g.keepId } }.distinct()
        if (idsToDelete.isEmpty()) return
        val uids = expenseDao.getUidsByIds(idsToDelete)
        expenseDao.deleteByIds(idsToDelete)
        val now = System.currentTimeMillis()
        expenseDao.insertTombstones(uids.map { ExpenseTombstone(it, now) })
        // Cleans up every remaining attachment (receipt + manual) on each deleted loser — the
        // reassignment above already moved anything still needed onto the keeper first.
        idsToDelete.forEach { deleteAttachmentsFor(it) }
    }

    /** Retroactive scan for [ExpensesSettings.MODE_LOCAL]'s manual "Check for duplicates
     *  now" path — [buildDuplicateChecker] otherwise only ever compares one new candidate
     *  against a narrow DB window at single-insert time, with no way to catch rows already sitting
     *  in the table. Greedy grouping: the earliest-created row in a cluster is always [keepId] (same
     *  "first-arrived record stays authoritative" precedent as [enrichWithNearDuplicate]), and once
     *  a row is consumed into a group it's never re-grouped into another. */
    suspend fun findLocalDuplicateGroups(nearDuplicateConfig: NearDuplicateConfig): List<DuplicateGroup> {
        val detector = buildDuplicateChecker(nearDuplicateConfig)
        val all = expensesSnapshot().sortedBy { it.createdAt }
        // Explicitly off the caller's dispatcher: the manual-check entry point launches on
        // CalendarStateManager-style Main.immediate, and this is an O(n²) pass whose comparator can
        // run a full Levenshtein matrix per pair — on the main thread that is a guaranteed ANR once
        // the expense list gets into the low thousands. Room's suspend DAOs hop on their own; pure
        // CPU work like this does not.
        return withContext(Dispatchers.Default) {
            val consumed = mutableSetOf<Long>()
            val groups = mutableListOf<DuplicateGroup>()
            for (keep in all) {
                if (keep.id in consumed) continue
                val dups = all.filter { it.id != keep.id && it.id !in consumed && detector.isDuplicateOf(it, keep) }
                if (dups.isNotEmpty()) {
                    groups += DuplicateGroup(keep.id, dups.map { it.id })
                    consumed += dups.map { it.id }
                    consumed += keep.id
                }
            }
            groups
        }
    }

    /** Scoped single-row counterpart to [findLocalDuplicateGroups], for automatic protection's
     *  "stage for review instead of silently merging" mode
     *  ([com.voxapps.expenses.data.preferences.ExpensesSettings.automaticProtectionReviewOnly]) —
     *  checks [candidateId] (already inserted, unlike the silent path which checks *before* inserting)
     *  against a nearby-in-time window using only auto-apply rules, without modifying either row.
     *  Null if [candidateId] doesn't exist or nothing matches. */
    suspend fun findLocalDuplicateGroupForRow(candidateId: Long, nearDuplicateConfig: NearDuplicateConfig): DuplicateGroup? {
        val candidate = expenseDao.getWithDetailsById(candidateId)?.expense ?: return null
        val nearby = expenseDao.getForDateRange(
            candidate.dateTime - nearDuplicateConfig.timeWindowMillis,
            candidate.dateTime + nearDuplicateConfig.timeWindowMillis
        ).filter { it.id != candidateId }
        val checker = buildDuplicateChecker(nearDuplicateConfig, automaticOnly = true)
        val match = checker.findDuplicate(candidate, nearby) ?: return null
        return DuplicateGroup(keepId = match.id, duplicateIds = listOf(candidateId))
    }

    /** Wide-net recall pass for pure [ExpensesSettings.MODE_AI] only — that mode has no local
     *  component and deliberately never consults the configured duplicate rules at all, so this groups
     *  by a fixed amount/currency/direction match instead, ignoring the time window: this step only
     *  narrows what the AI has to reason over, it never decides anything itself, so casting a wider
     *  net than any rule's own window is intentional (the AI is what has to draw the line between
     *  a genuine duplicate and a legitimate recurring same-amount charge, using the fuller context of
     *  a whole cluster rather than a single ambiguous pair). Pass [scopedToId] to narrow to just the
     *  cluster containing one specific (freshly-inserted) row, for the insert-time automatic check.
     *  [ExpensesSettings.MODE_LOCAL_AND_AI] uses [ruleBasedCandidateClusters] instead — its local half
     *  is expected to actually respect the user's configured rules. */
    suspend fun duplicateCandidateClusters(scopedToId: Long? = null): List<List<Expense>> {
        val all = expensesSnapshot()
        val clusters = all.groupBy { Triple(it.totalAmount, it.currencyCode, it.direction) }
            .values.filter { it.size >= 2 }
        return if (scopedToId == null) clusters else clusters.filter { cluster -> cluster.any { it.id == scopedToId } }
    }

    /** [ExpensesSettings.MODE_LOCAL_AND_AI]'s recall pass — same rule engine [buildDuplicateChecker]
     *  gives [ExpensesSettings.MODE_LOCAL] (any enabled rule matching a pair puts them in the same
     *  cluster, respecting the user's own field selection and time window), rather than
     *  [duplicateCandidateClusters]'s fixed amount/currency/direction grouping. The AI still makes the
     *  final keep/duplicate call from each cluster; this only decides which expenses are worth showing
     *  it. [automaticOnly] mirrors [findLocalDuplicateGroupForRow]'s filter — true for the insert-time
     *  automatic check (only rules marked "applies automatically at save" apply), false for manual/
     *  scheduled (every enabled rule applies, same as [findLocalDuplicateGroups]). Pass [scopedToId] to
     *  narrow to just the cluster containing one specific (freshly-inserted) row. */
    suspend fun ruleBasedCandidateClusters(
        nearDuplicateConfig: NearDuplicateConfig,
        automaticOnly: Boolean = false,
        scopedToId: Long? = null
    ): List<List<Expense>> {
        val detector = buildDuplicateChecker(nearDuplicateConfig, automaticOnly)
        val all = expensesSnapshot()
        // Same reasoning as findLocalDuplicateGroups: unscoped, this is another O(n²) CPU pass
        // reachable from a Main-dispatched launch.
        return withContext(Dispatchers.Default) {
            fun matches(a: Expense, b: Expense) = detector.isDuplicateOf(a, b) || detector.isDuplicateOf(b, a)
            if (scopedToId != null) {
                val target = all.find { it.id == scopedToId } ?: return@withContext emptyList()
                val peers = all.filter { it.id != scopedToId && matches(it, target) }
                return@withContext if (peers.isEmpty()) emptyList() else listOf(listOf(target) + peers)
            }
            val consumed = mutableSetOf<Long>()
            val clusters = mutableListOf<List<Expense>>()
            for (anchor in all.sortedBy { it.createdAt }) {
                if (anchor.id in consumed) continue
                val peers = all.filter { it.id != anchor.id && it.id !in consumed && matches(it, anchor) }
                if (peers.isNotEmpty()) {
                    clusters += listOf(anchor) + peers
                    consumed += anchor.id
                    consumed += peers.map { it.id }
                }
            }
            clusters
        }
    }
}
