package com.voxapps.expenses.domain.recurring

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.voxapps.expenses.ExpensesApplication
import java.util.concurrent.TimeUnit

/**
 * The daily pass over payments that come back: notice what did not arrive, and warn about what is
 * about to be due.
 *
 * Both halves are recomputed from dates rather than accumulated, so a phone that was off for a week
 * gets the same answer as one that ran every day. Nothing here writes an expense and nothing here
 * confirms an arrangement — an app that quietly books money nobody spent is worse than one that
 * forgets to remind you.
 */
class RecurringPaymentWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ExpensesApplication).container
        val repo = container.recurringPaymentRepository
        val now = System.currentTimeMillis()

        // Runs whether or not reminders are wanted: the count of missed due dates is what turns a
        // row red and eventually proposes dropping it, and that is bookkeeping, not a notification.
        repo.refreshMissedCycles(now)

        val settings = container.settingsRepository.getSnapshot()
        if (!settings.recurringRemindersEnabled) return Result.success()

        for (reminder in RecurringReminders.due(repo.confirmedArrangements(), now)) {
            RecurringPaymentNotifier.notify(
                context = applicationContext,
                languageManager = container.languageManager,
                payment = reminder.payment,
                dueAtMillis = reminder.dueAtMillis,
                settings = settings
            )
            repo.markReminded(reminder.payment.id, reminder.dueAtMillis)
        }

        return Result.success()
    }
}

/**
 * Keeps the daily pass scheduled.
 *
 * No on/off setting of its own, for the same reason [com.voxapps.expenses.domain.limits.SpendingLimitScheduler]
 * has none: with no confirmed arrangements the worker is a pass over an empty list, and a toggle
 * whose only effect is to skip nothing is a setting that exists to be misread. Whether *reminders*
 * are wanted is a real choice, and it is asked separately.
 */
object RecurringPaymentScheduler {
    private const val UNIQUE_WORK_NAME = "recurring_payment_check"

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecurringPaymentWorker>(1, TimeUnit.DAYS)
            // Deliberately unconstrained — unlike the spending-limit check there is no exchange-rate
            // lookup here, so nothing about it needs a network, and requiring one would delay a
            // reminder past the day it was for.
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
