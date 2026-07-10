package com.voxapps.expenses.domain.limits

import com.voxapps.expenses.data.Expense
import com.voxapps.expenses.data.SpendingLimit
import java.time.LocalDate

/** One limit found to be exceeded by the current period's spend. */
data class ExceededLimit(val limit: SpendingLimit, val spent: Double, val periodKey: String)

/**
 * Sums expenses against each configured [SpendingLimit] and reports which are exceeded. Kept
 * Android-free (aside from the suspend converter callback) so the window/summation logic is
 * unit-testable without needing a real [com.voxapps.expenses.data.ExchangeRateRepository] or network.
 */
object SpendingLimitChecker {
    /**
     * [convertToHome] mirrors [com.voxapps.expenses.data.ExchangeRateRepository.convertToHome]'s
     * signature exactly so the real repository can be passed as a method reference. An expense whose
     * currency can't be converted (no API key configured, fetch failed) is skipped from the sum rather
     * than failing the whole check — better to under-count than to crash a background job.
     */
    suspend fun findExceeded(
        expenses: List<Expense>,
        limits: List<SpendingLimit>,
        homeCurrency: String,
        today: LocalDate = LocalDate.now(),
        convertToHome: suspend (amount: Double, fromCurrency: String, homeCurrency: String) -> Double?
    ): List<ExceededLimit> {
        val results = mutableListOf<ExceededLimit>()
        for (limit in limits) {
            val windowStart = SpendingPeriod.windowStartMillis(limit.period, today)
            val matching = expenses.filter {
                it.dateTime >= windowStart && (limit.categoryId == null || it.categoryId == limit.categoryId)
            }
            var spent = 0.0
            for (expense in matching) {
                val converted = convertToHome(expense.totalAmount, expense.currencyCode, homeCurrency) ?: continue
                spent += converted
            }
            if (spent > limit.amountHomeCurrency) {
                results += ExceededLimit(limit, spent, SpendingPeriod.periodKey(limit.period, today))
            }
        }
        return results
    }
}
