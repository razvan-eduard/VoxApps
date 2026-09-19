package com.voxapps.expenses.domain.budget

import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.logging.Logger

private const val TAG = "NotificationBalanceReconciler"

/**
 * What the second figure in a two-figure payment notification becomes.
 *
 * [com.voxapps.textmatch.extract.TwoFieldPreParse]'s own certainty rule already resolved which
 * figure this is — the account's stated remaining balance, never a second transaction — so nothing
 * here re-reads the message. What is left to decide is only this app's: whether the resolved account
 * is one already carrying a budget, and whether the person wants the figure believed outright or
 * offered first. Every step declines rather than guesses: an unresolved account, an unbudgeted one,
 * or a message stating its currency ambiguously all leave the budget exactly as it was.
 */
object NotificationBalanceReconciler {

    suspend fun reconcile(
        container: ExpensesContainer,
        settings: ExpensesSettings,
        /** The person's own standing statement that this source announces their money moving — the
         *  same gate [com.voxapps.expenses.domain.llm.CapturedNotification.fromStarredBank] is. A
         *  figure this consequential, written with no review in AUTO mode, is not read from a
         *  source that was never declared a bank. */
        fromStarredBank: Boolean,
        /** [android.service.notification.StatusBarNotification.getPostTime] — when the bank says the
         *  figure was true, not the moment this device got around to reading it, so a delayed
         *  catch-up scan or a forced re-check of an old notification can never look newer than a
         *  statement that actually came later. Used both as the ordering key against any existing
         *  pending suggestion and as the budget's own [com.voxapps.expenses.data.AccountBudget.reconciledAt]. */
        statedAt: Long,
        bankName: String?,
        sourceText: String?,
        secondAmount: Double?,
        /** Null whenever the message stated more than one currency — see
         *  [com.voxapps.textmatch.extract.TwoFieldPreParse.Result.currency]. Without this there is no
         *  way to know which currency [secondAmount] is in, so it is left unused rather than assumed
         *  to match the transaction's own. */
        currency: String?
    ) {
        if (settings.notificationBalanceReconcileMode == ExpensesSettings.BALANCE_RECONCILE_OFF) return
        if (!fromStarredBank) return
        val remaining = secondAmount?.takeIf { it > 0.0 } ?: return
        val currencyCode = currency ?: return
        val accountId = container.expensesRepository.resolveBankAccount(
            text = sourceText,
            // Never — a balance update is not the kind of thing that should be the reason a new
            // account row gets created.
            autoCreate = false,
            defaultCurrency = currencyCode,
            bankName = bankName
        ) ?: return
        container.expensesRepository.accountBudgetFor(accountId, currencyCode) ?: return
        when (settings.notificationBalanceReconcileMode) {
            ExpensesSettings.BALANCE_RECONCILE_AUTO ->
                container.expensesRepository.reconcileAccountBudget(accountId, currencyCode, remaining, statedAt)
            else ->
                container.pendingBudgetReconcileRepository.addPending(
                    PendingBudgetReconcile(accountId, currencyCode, remaining, statedAt)
                )
        }
        Logger.d(TAG, "Balance from a notification: $remaining $currencyCode on account $accountId")
    }
}
