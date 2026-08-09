package com.voxapps.expenses.domain.limits

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.expenses.ExpensesApplication
import kotlinx.coroutines.flow.first

/**
 * Runs daily (see [SpendingLimitScheduler]) regardless of whether any limit is actually configured —
 * a no-op pass over an empty list is cheap, and this avoids needing a separate "is the feature
 * enabled" setting just to turn the schedule on/off. For each configured limit, sums matching expenses
 * within the current period window (converted to the home currency via
 * [com.voxapps.expenses.data.ExchangeRateRepository] — the same cached-rate infrastructure Stage 5's
 * reports use) and posts a plain local Android notification (via [SpendingLimitNotifier]) the first
 * time it's found exceeded in a given period — never a Vox-contract broadcast, this is purely a
 * device-local alert with no cross-app meaning.
 */
class SpendingLimitCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ExpensesApplication).container
        val limits = container.expensesRepository.spendingLimits.first()
        if (limits.isEmpty()) return Result.success()

        val expenses = container.expensesRepository.expenses.first()
        val categories = container.expensesRepository.categories.first()
        val settings = container.settingsRepository.getSnapshot()
        val homeCurrency = settings.homeCurrency

        val exceeded = SpendingLimitChecker.findExceeded(
            expenses = expenses,
            limits = limits,
            homeCurrency = homeCurrency,
            convertToHome = container.exchangeRateRepository::convertToHome
        )

        for (result in exceeded) {
            val alertRepo = container.spendingLimitAlertRepository
            if (alertRepo.wasAlreadyAlerted(result.limit.id, result.periodKey)) continue

            val categoryLabel = result.limit.categoryId
                ?.let { id -> categories.firstOrNull { it.id == id }?.name }
                ?: container.languageManager.getString("overall_spending_label")

            SpendingLimitNotifier.notify(
                context = applicationContext,
                languageManager = container.languageManager,
                notificationId = result.limit.id.toInt(),
                categoryLabel = categoryLabel,
                spent = result.spent,
                limit = result.limit.amountHomeCurrency,
                homeCurrency = homeCurrency,
                settings = settings
            )
            alertRepo.markAlerted(result.limit.id, result.periodKey)
        }

        return Result.success()
    }
}
