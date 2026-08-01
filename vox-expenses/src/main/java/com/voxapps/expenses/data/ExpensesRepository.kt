package com.voxapps.expenses.data

import android.content.Context
import com.voxapps.attachments.AttachmentDao
import com.voxapps.attachments.AttachmentFileStore
import com.voxapps.datahygiene.DuplicateChecker
import com.voxapps.datahygiene.RuleBasedDuplicateChecker
import com.voxapps.datahygiene.RuleCombinator
import com.voxapps.datahygiene.findDuplicate
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.domain.llm.DuplicateGroup
import com.voxapps.logging.Logger
import com.voxapps.textmatch.FuzzyNameMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

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
 * Single write point over the Room DAOs.
 */
class ExpensesRepository(
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao,
    private val lineItemDao: ExpenseLineItemDao,
    private val spendingLimitDao: SpendingLimitDao,
    private val merchantCategoryMemoryDao: MerchantCategoryMemoryDao,
    private val appContext: Context,
    private val attachmentDao: AttachmentDao,
    private val duplicateRuleDao: DuplicateRuleDao,
    private val pendingFieldSuggestionDao: PendingFieldSuggestionDao
) {
    val expenses: Flow<List<Expense>> = expenseDao.observeAll()
    val expensesWithDetails: Flow<List<ExpenseWithDetails>> = expenseDao.observeExpensesWithDetails()
    val categories: Flow<List<Category>> = categoryDao.observeAll()
    val spendingLimits: Flow<List<SpendingLimit>> = spendingLimitDao.observeAll()

    /** See [PendingFieldSuggestion]'s doc comment — the source for ExpenseEditScreen's tappable
     *  field-suggestion chips after a line-items rescan. */
    fun observePendingFieldSuggestion(expenseId: Long): Flow<PendingFieldSuggestion?> =
        pendingFieldSuggestionDao.observe(expenseId)

    suspend fun setPendingFieldSuggestion(suggestion: PendingFieldSuggestion) =
        pendingFieldSuggestionDao.upsert(suggestion)

    suspend fun clearPendingFieldSuggestion(expenseId: Long) =
        pendingFieldSuggestionDao.clear(expenseId)

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
        val rules = duplicateRuleDao.observeAll().first().filter { it.enabled && (!automaticOnly || it.appliesAutomatically) }
        val exactFields = ExpenseRuleFields(fuzzyMatchEnabled = false, timeWindowMillis = config.timeWindowMillis).all
        val fuzzyFields = ExpenseRuleFields(fuzzyMatchEnabled = true, timeWindowMillis = config.timeWindowMillis).all
        return DuplicateChecker { candidate, existing ->
            // Unconditional, not opt-in per rule: an incoming top-up/refund and an outgoing payment of
            // the same amount are two different real transactions, never a duplicate, regardless of
            // which fields a user-configured rule happens to check. Confirmed on-device: a rule that
            // didn't explicitly include "direction" let a 1000 RON top-up and a 1000 RON payment merge.
            if (candidate.direction != existing.direction) return@DuplicateChecker false
            if (rules.isEmpty()) return@DuplicateChecker false
            val results = rules.map { rule ->
                val fields = if (rule.fuzzyMatchEnabled) fuzzyFields else exactFields
                RuleBasedDuplicateChecker(fields, listOf(rule.toDuplicateRule()), RuleCombinator.OR).isDuplicateOf(candidate, existing)
            }
            when (config.globalCombinator) {
                RuleCombinator.AND -> results.all { it }
                RuleCombinator.OR -> results.any { it }
            }
        }
    }

    suspend fun expensesSnapshot(): List<Expense> = expenseDao.observeAll().first()

    suspend fun getExpenseById(id: Long): ExpenseWithDetails? = expenseDao.getWithDetailsById(id)

    /** The color of the most recent expense's category — see [CategoryPalette.unusedOrRandomColor]'s
     *  `precedingColor` param. */
    suspend fun mostRecentCategoryColor(): Long? = expenseDao.getMostRecentCategoryColor()

    // --- Merchant category memory (see MerchantCategoryMemory) ---

    /** Called only for a GENUINE manual category change (see ExpenseEditScreen's save path) — never
     *  for an unchanged re-save. Unconditional/pure: the caller (ExpensesStateManager) is responsible
     *  for gating this on ExpensesSettings.merchantCategoryMemoryEnabled, matching the established
     *  convention that this repository never reads settings itself. */
    suspend fun recordManualCategoryChange(vendor: String?, categoryId: Long?) {
        val vendorKey = MerchantVendorKey.normalize(vendor) ?: return
        if (categoryId == null) {
            merchantCategoryMemoryDao.delete(vendorKey)
            return
        }
        val existing = merchantCategoryMemoryDao.get(vendorKey)
        val newCount = if (existing?.categoryId == categoryId) existing.consecutiveCount + 1 else 1
        merchantCategoryMemoryDao.upsert(MerchantCategoryMemory(vendorKey, categoryId, newCount, System.currentTimeMillis()))
    }

    suspend fun getLearnedCategoryId(vendor: String?, threshold: Int): Long? {
        val vendorKey = MerchantVendorKey.normalize(vendor) ?: return null
        return merchantCategoryMemoryDao.getLearnedCategoryId(vendorKey, threshold)
    }

    suspend fun merchantCategoryMemorySnapshot(): List<MerchantCategoryMemory> = merchantCategoryMemoryDao.getAll()

    suspend fun upsertMerchantCategoryMemory(vendorKey: String, categoryId: Long, consecutiveCount: Int, updatedAt: Long) {
        merchantCategoryMemoryDao.upsert(MerchantCategoryMemory(vendorKey, categoryId, consecutiveCount, updatedAt))
    }

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
        manuallyEdited: Boolean = false
    ): Long {
        return try {
            val candidate = Expense(
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                totalAmount = totalAmount,
                currencyCode = currencyCode,
                vendor = vendor?.trim()?.takeIf { it.isNotEmpty() },
                bank = bank?.trim()?.takeIf { it.isNotEmpty() },
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
                    // The candidate's own receipt photo (if any) is now orphaned unless it got
                    // adopted into the enriched record.
                    if (imageName != null && enriched.receiptImageName != imageName) {
                        deleteReceiptFiles(listOf(imageName))
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
                if (items.isNotEmpty()) {
                    lineItemDao.insertAll(items.mapIndexed { index, item -> item.copy(id = 0, expenseId = id, position = index) })
                }
            } else {
                Logger.e("ExpensesRepository", "DB Insert returned invalid ID: $id")
            }
            id
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
        deleteReceiptFiles(listOfNotNull(expense.receiptImageName))
        deleteAttachmentsFor(expense.id)
    }

    suspend fun deleteExpenseById(id: Long) {
        val expense = expenseDao.getWithDetailsById(id)?.expense
        expenseDao.deleteById(id)
        if (expense != null) {
            expenseDao.insertTombstone(ExpenseTombstone(expense.uid, System.currentTimeMillis()))
        }
        deleteReceiptFiles(listOfNotNull(expense?.receiptImageName))
        deleteAttachmentsFor(id)
    }

    suspend fun deleteAllExpenses() {
        val all = expensesSnapshot()
        expenseDao.deleteAll()
        val now = System.currentTimeMillis()
        expenseDao.insertTombstones(all.map { ExpenseTombstone(it.uid, now) })
        deleteReceiptFiles(all.mapNotNull { it.receiptImageName })
        all.forEach { deleteAttachmentsFor(it.id) }
    }

    /** Best-effort cleanup of manually-added attachments (see :core:attachments) — mirrors
     *  [deleteReceiptFiles]'s "never block/roll back the DB delete on a file-delete failure"
     *  posture. Distinct from [deleteReceiptFiles]: the original receipt scan lives in
     *  filesDir/receipts/ via Expense.receiptImageName, unrelated to this table/dir. */
    private suspend fun deleteAttachmentsFor(expenseId: Long) {
        val rows = attachmentDao.deleteAllFor(ExpensesAttachments.RECORD_TYPE, expenseId)
        for (row in rows) {
            try {
                if (attachmentDao.countByFileName(ExpensesAttachments.RECORD_TYPE, row.fileName) == 0) {
                    AttachmentFileStore.delete(appContext, ExpensesAttachments.DIR, row.fileName)
                }
            } catch (e: Exception) {
                Logger.w("ExpensesRepository", "Failed to delete attachment file for expense $expenseId", e)
            }
        }
    }

    /** Best-effort cleanup: a file-delete failure never blocks/rolls back the DB delete — an orphan
     *  file is a far cheaper failure mode than a stuck delete. Also removes the sibling raw-OCR-text
     *  file staged for stub-expense retry, if any. */
    private fun deleteReceiptFiles(names: List<String>) {
        if (names.isEmpty()) return
        val receiptsDir = File(appContext.filesDir, "receipts")
        for (name in names) {
            try {
                File(receiptsDir, name).delete()
                File(receiptsDir, name.substringBeforeLast('.') + ".txt").delete()
            } catch (e: Exception) {
                Logger.w("ExpensesRepository", "Failed to delete receipt file(s) for $name", e)
            }
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
        merchantMemoryEnabled: Boolean = false,
        merchantMemoryThreshold: Int = ExpensesSettings.MERCHANT_MEMORY_DEFAULT_THRESHOLD,
        source: ExpenseSource = ExpenseSource.VOICE
    ): Long {
        val cats = categoryDao.observeAll().first()

        // A learned merchant mapping is a total short-circuit — it overrides whatever the LLM/spoken
        // category or configured default would otherwise suggest, checked BEFORE resolution runs at
        // all, not as a tie-break afterward. Falls through to normal resolution if the mapping points
        // at a category that's since been deleted.
        var resolved: FuzzyNameMatcher.Resolved? = null
        if (merchantMemoryEnabled) {
            val learnedId = getLearnedCategoryId(vendor, merchantMemoryThreshold)
            val learnedCategory = learnedId?.let { id -> cats.firstOrNull { it.id == id } }
            if (learnedCategory != null) resolved = FuzzyNameMatcher.Resolved(learnedCategory.id, learnedCategory.name)
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
            val id = addCategory(spoken, CategoryPalette.unusedOrRandomColor(cats.map { it.colorArgb }, precedingColor), cats.size, dateTime)
            if (id > 0) resolved = FuzzyNameMatcher.Resolved(id, spoken)
        }

        return addExpense(
            title, totalAmount, currencyCode, vendor, bank, location, dateTime, comments, resolved.id, items, imageName,
            direction = direction,
            nearDuplicateCheckEnabled = nearDuplicateCheckEnabled,
            nearDuplicateConfig = nearDuplicateConfig,
            source = source
        )
    }

    suspend fun addCategory(name: String, colorArgb: Long, position: Int, createdAt: Long): Long {
        val clean = name.trim()
        if (clean.isEmpty()) return -1
        return categoryDao.insert(
            Category(name = clean, colorArgb = colorArgb, position = position, createdAt = createdAt)
        )
    }

    suspend fun updateCategory(category: Category) = categoryDao.update(category)

    suspend fun deleteCategory(category: Category) {
        expenseDao.clearCategory(category.id)
        spendingLimitDao.clearCategory(category.id)
        merchantCategoryMemoryDao.clearCategory(category.id)
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
        val cats = categoryDao.observeAll().first()
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
        val adoptedImageNames = mutableSetOf<String>()
        for (g in groups) {
            val keeper = expenseDao.getWithDetailsById(g.keepId)?.expense ?: continue
            val others = g.duplicateIds.filter { it != g.keepId }.mapNotNull { expenseDao.getWithDetailsById(it)?.expense }
            val merged = others.fold(keeper) { acc, other -> enrichWithNearDuplicate(acc, other) }
            if (merged != keeper) {
                expenseDao.update(merged)
                merged.receiptImageName?.let { adoptedImageNames += it }
            }
        }
        val idsToDelete = groups.flatMap { g -> g.duplicateIds.filter { it != g.keepId } }.distinct()
        if (idsToDelete.isEmpty()) return
        // Excludes any receipt image a merge above just adopted into a surviving row — otherwise this
        // would delete the file out from under the row that now references it.
        val imageNames = expenseDao.getReceiptImageNames(idsToDelete).filterNot { it in adoptedImageNames }
        val uids = expenseDao.getUidsByIds(idsToDelete)
        expenseDao.deleteByIds(idsToDelete)
        val now = System.currentTimeMillis()
        expenseDao.insertTombstones(uids.map { ExpenseTombstone(it, now) })
        deleteReceiptFiles(imageNames)
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
        return groups
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
        fun matches(a: Expense, b: Expense) = detector.isDuplicateOf(a, b) || detector.isDuplicateOf(b, a)
        if (scopedToId != null) {
            val target = all.find { it.id == scopedToId } ?: return emptyList()
            val peers = all.filter { it.id != scopedToId && matches(it, target) }
            return if (peers.isEmpty()) emptyList() else listOf(listOf(target) + peers)
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
        return clusters
    }
}
