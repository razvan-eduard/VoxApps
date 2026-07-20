package com.voxapps.expenses.data

import android.content.Context
import com.voxapps.datahygiene.findDuplicate
import com.voxapps.expenses.domain.llm.DuplicateGroup
import com.voxapps.logging.Logger
import com.voxapps.textmatch.FuzzyNameMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File

/** [ExpensesRepository.addExpense]'s return value when the insert was skipped because
 *  [ExpenseDuplicateChecker] found an exact match already in the database — distinct from the
 *  generic `-1L` "insert threw" failure sentinel so callers can show a precise "Duplicate entry"
 *  message instead of a generic save-failed one. */
const val DUPLICATE_ENTRY_RESULT = -2L

/**
 * Single write point over the Room DAOs.
 */
class ExpensesRepository(
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao,
    private val lineItemDao: ExpenseLineItemDao,
    private val spendingLimitDao: SpendingLimitDao,
    private val appContext: Context
) {
    val expenses: Flow<List<Expense>> = expenseDao.observeAll()
    val expensesWithDetails: Flow<List<ExpenseWithDetails>> = expenseDao.observeExpensesWithDetails()
    val categories: Flow<List<Category>> = categoryDao.observeAll()
    val spendingLimits: Flow<List<SpendingLimit>> = spendingLimitDao.observeAll()

    suspend fun expensesSnapshot(): List<Expense> = expenseDao.observeAll().first()

    suspend fun getExpenseById(id: Long): ExpenseWithDetails? = expenseDao.getWithDetailsById(id)

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
        checkForDuplicate: Boolean = true
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
                receiptImageName = imageName,
                isStub = isStub,
                createdAt = createdAt
            )

            if (checkForDuplicate) {
                // Same exact instant is the cheap, precise pre-filter (ExpenseDuplicateChecker
                // requires dateTime equality anyway) — avoids a full-table scan on every insert.
                val sameInstant = expenseDao.getForDateRange(dateTime, dateTime)
                val duplicate = ExpenseDuplicateChecker.findDuplicate(candidate, sameInstant)
                if (duplicate != null) {
                    Logger.w("ExpensesRepository", "Duplicate entry — skipping insert (matches existing id=${duplicate.id})")
                    return DUPLICATE_ENTRY_RESULT
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

    suspend fun updateExpense(expense: Expense, items: List<ExpenseLineItem>) {
        expenseDao.update(expense)
        lineItemDao.deleteAllForExpense(expense.id)
        if (items.isNotEmpty()) {
            lineItemDao.insertAll(items.mapIndexed { index, item -> item.copy(id = 0, expenseId = expense.id, position = index) })
        }
    }

    suspend fun deleteExpense(expense: Expense) {
        expenseDao.delete(expense)
        deleteReceiptFiles(listOfNotNull(expense.receiptImageName))
    }

    suspend fun deleteExpenseById(id: Long) {
        val imageName = expenseDao.getReceiptImageName(id)
        expenseDao.deleteById(id)
        deleteReceiptFiles(listOfNotNull(imageName))
    }

    suspend fun deleteAllExpenses() {
        val imageNames = expenseDao.getAllReceiptImageNames()
        expenseDao.deleteAll()
        deleteReceiptFiles(imageNames)
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
        imageName: String? = null
    ): Long {
        val cats = categoryDao.observeAll().first()
        var resolved = FuzzyNameMatcher.resolve(
            spokenName = spokenCategory,
            candidates = cats.map { FuzzyNameMatcher.Candidate(it.id, it.name) },
            defaultId = defaultCategoryId
        )

        val spoken = spokenCategory?.trim()?.takeIf { it.isNotEmpty() }
        if (resolved.id == null && autoCreate && spoken != null) {
            val id = addCategory(spoken, CategoryPalette.unusedOrRandomColor(cats.map { it.colorArgb }), cats.size, dateTime)
            if (id > 0) resolved = FuzzyNameMatcher.Resolved(id, spoken)
        }

        return addExpense(title, totalAmount, currencyCode, vendor, bank, location, dateTime, comments, resolved.id, items, imageName)
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

    suspend fun applyExpenseDeduplication(groups: List<DuplicateGroup>) {
        val idsToDelete = groups.flatMap { g -> g.duplicateIds.filter { it != g.keepId } }.distinct()
        if (idsToDelete.isEmpty()) return
        val imageNames = expenseDao.getReceiptImageNames(idsToDelete)
        expenseDao.deleteByIds(idsToDelete)
        deleteReceiptFiles(imageNames)
    }
}
