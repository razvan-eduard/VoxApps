package com.voxapps.expenses.data

import com.voxapps.expenses.domain.llm.DuplicateGroup
import com.voxapps.textmatch.FuzzyNameMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Single write point over the Room DAOs (mirrors vox-notes' NotesRepository). ExpensesStateManager
 * observes [expenses] / [categories] and calls the suspend writers.
 */
class ExpensesRepository(
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao,
    private val lineItemDao: ExpenseLineItemDao,
    private val spendingLimitDao: SpendingLimitDao
) {
    val expenses: Flow<List<Expense>> = expenseDao.observeAll()
    val expensesWithDetails: Flow<List<ExpenseWithDetails>> = expenseDao.observeExpensesWithDetails()
    val categories: Flow<List<Category>> = categoryDao.observeAll()
    val spendingLimits: Flow<List<SpendingLimit>> = spendingLimitDao.observeAll()

    /** One-shot snapshot for the headless read path (Commander IPC, Stage 2). */
    suspend fun expensesSnapshot(): List<Expense> = expenseDao.observeAll().first()

    // --- EXPENSES ---

    /**
     * Inserts an expense plus its line items in one call. [totalAmount] is always what's persisted —
     * the caller (UI) is responsible for computing it from [items]' subtotals when items are present;
     * this layer never recomputes or overrides it, so a manual override always sticks.
     */
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
        items: List<ExpenseLineItem> = emptyList()
    ): Long {
        val id = expenseDao.insert(
            Expense(
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                totalAmount = totalAmount,
                currencyCode = currencyCode,
                vendor = vendor?.trim()?.takeIf { it.isNotEmpty() },
                bank = bank?.trim()?.takeIf { it.isNotEmpty() },
                location = location?.trim()?.takeIf { it.isNotEmpty() },
                dateTime = dateTime,
                comments = comments?.trim()?.takeIf { it.isNotEmpty() },
                categoryId = categoryId
            )
        )
        if (items.isNotEmpty()) {
            lineItemDao.insertAll(items.mapIndexed { index, item -> item.copy(id = 0, expenseId = id, position = index) })
        }
        return id
    }

    /** Replaces an expense's fields and its full line-item set (simplest correct update semantics). */
    suspend fun updateExpense(expense: Expense, items: List<ExpenseLineItem>) {
        expenseDao.update(expense)
        lineItemDao.deleteAllForExpense(expense.id)
        if (items.isNotEmpty()) {
            lineItemDao.insertAll(items.mapIndexed { index, item -> item.copy(id = 0, expenseId = expense.id, position = index) })
        }
    }

    suspend fun deleteExpense(expense: Expense) = expenseDao.delete(expense)

    suspend fun deleteExpenseById(id: Long) = expenseDao.deleteById(id)

    /**
     * Headless expense insert from a parsed voice/scan/notification result: resolves the spoken/
     * suggested category name (exact match, then Levenshtein fuzzy match via the shared
     * `:core:textmatch` resolver — same algorithm vox-notes' VoiceCategoryResolver uses) or the
     * configured default, saves, and returns the resolved category id/name so the caller can toast it
     * (mirrors vox-notes' `addVoiceNote`).
     */
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
        items: List<ExpenseLineItem> = emptyList()
    ): FuzzyNameMatcher.Resolved {
        val cats = categoryDao.observeAll().first()
        var resolved = FuzzyNameMatcher.resolve(
            spokenName = spokenCategory,
            candidates = cats.map { FuzzyNameMatcher.Candidate(it.id, it.name) },
            defaultId = defaultCategoryId
        )

        // Unknown spoken category + opt-in -> create it (auto-colored) rather than falling back.
        val spoken = spokenCategory?.trim()?.takeIf { it.isNotEmpty() }
        if (resolved.id == null && autoCreate && spoken != null) {
            val id = addCategory(spoken, CategoryPalette.unusedOrRandomColor(cats.map { it.colorArgb }), cats.size, dateTime)
            if (id > 0) resolved = FuzzyNameMatcher.Resolved(id, spoken)
        }

        addExpense(title, totalAmount, currencyCode, vendor, bank, location, dateTime, comments, resolved.id, items)
        return resolved
    }

    // --- CATEGORIES ---
    suspend fun addCategory(name: String, colorArgb: Long, position: Int, createdAt: Long): Long {
        val clean = name.trim()
        if (clean.isEmpty()) return -1
        return categoryDao.insert(
            Category(name = clean, colorArgb = colorArgb, position = position, createdAt = createdAt)
        )
    }

    suspend fun updateCategory(category: Category) = categoryDao.update(category)

    /** Deleting a category leaves its expenses intact — they become uncategorized. Any spending
     *  limit scoped to it becomes an overall limit rather than being deleted with it. */
    suspend fun deleteCategory(category: Category) {
        expenseDao.clearCategory(category.id)
        spendingLimitDao.clearCategory(category.id)
        categoryDao.delete(category)
    }

    // --- SPENDING LIMITS ---
    suspend fun addSpendingLimit(categoryId: Long?, amountHomeCurrency: Double, period: String): Long =
        spendingLimitDao.insert(SpendingLimit(categoryId = categoryId, amountHomeCurrency = amountHomeCurrency, period = period))

    suspend fun deleteSpendingLimit(limit: SpendingLimit) = spendingLimitDao.delete(limit)

    /**
     * Applies a user-APPROVED category merge mapping (old name -> canonical name) — unlike
     * vox-notes' equivalent, this is only ever called after explicit confirmation in the review UI
     * (see [com.voxapps.expenses.domain.llm.PendingCategoryMergeRepository]), never automatically.
     * Case-insensitive name matching. Entries whose old or canonical name doesn't match any existing
     * category are silently skipped (the LLM may suggest names that no longer exist if categories
     * changed between the request and the reply).
     */
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

    /**
     * Applies a user-approved expense-deduplication resolution: for each [DuplicateGroup], deletes
     * every id in `duplicateIds` except `keepId` (mirrors vox-notes' `applyNoteDeduplication`). Only
     * ever called after explicit user confirmation in the review UI.
     */
    suspend fun applyExpenseDeduplication(groups: List<DuplicateGroup>) {
        for (group in groups) {
            for (duplicateId in group.duplicateIds) {
                if (duplicateId == group.keepId) continue
                expenseDao.deleteById(duplicateId)
            }
        }
    }
}
