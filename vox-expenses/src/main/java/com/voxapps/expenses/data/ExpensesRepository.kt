package com.voxapps.expenses.data

import com.voxapps.expenses.domain.llm.DuplicateGroup
import com.voxapps.logging.Logger
import com.voxapps.textmatch.FuzzyNameMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Single write point over the Room DAOs.
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

    suspend fun expensesSnapshot(): List<Expense> = expenseDao.observeAll().first()

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
        imageName: String? = null
    ): Long {
        return try {
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
                    categoryId = categoryId,
                    receiptImageName = imageName
                )
            )
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

    suspend fun deleteExpense(expense: Expense) = expenseDao.delete(expense)

    suspend fun deleteExpenseById(id: Long) = expenseDao.deleteById(id)

    suspend fun deleteAllExpenses() = expenseDao.deleteAll()

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

    suspend fun addSpendingLimit(categoryId: Long?, amountHomeCurrency: Double, period: String): Long =
        spendingLimitDao.insert(SpendingLimit(categoryId = categoryId, amountHomeCurrency = amountHomeCurrency, period = period))

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
        for (group in groups) {
            for (duplicateId in group.duplicateIds) {
                if (duplicateId == group.keepId) continue
                expenseDao.deleteById(duplicateId)
            }
        }
    }
}
