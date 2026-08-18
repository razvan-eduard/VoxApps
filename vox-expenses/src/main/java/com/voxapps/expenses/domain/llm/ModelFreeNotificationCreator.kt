package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.logging.Logger

private const val TAG = "ModelFreeNotification"

/**
 * Turns a payment notification into a record without sending it anywhere.
 *
 * What the device can establish alone is narrower here than on a scanned page. A receipt carries
 * arithmetic that proves a reading; a notification carries one sentence, and the only things that
 * can be taken from it without interpretation are the figures — the amount, and the bank or
 * merchant where a vocabulary already names them. What the sentence *means* — whether money left
 * the account or arrived in it — is not in the figures, and no rule can be written for it that
 * does not eventually mislabel a message.
 *
 * So it is not guessed. The template memory answers where a human has already confirmed this exact
 * sentence shape, and everything else goes to the review queue, which is where a notification-derived
 * record was always meant to start. Approving one there confirms its template, so the second message
 * of a shape a person has judged once can be created without asking again — the memory fills in
 * where the model used to.
 *
 * The direction that is *known* is honoured rather than assumed: a template confirmed as incoming
 * produces no expense at all, because money arriving is not a purchase.
 */
object ModelFreeNotificationCreator {

    /** What became of a captured notification, for the log and for the caller's own accounting. */
    enum class Outcome { CREATED, QUEUED_FOR_REVIEW, SKIPPED_INCOMING, SKIPPED_NO_AMOUNT }

    suspend fun handle(
        context: Context,
        container: ExpensesContainer,
        preParse: NotificationPreParse.Result,
        templateHash: String?,
        knownBank: String?,
        title: String?
    ): Outcome {
        val settings = container.settingsRepository.getSnapshot()

        val amount = preParse.amount
        if (amount == null || amount <= 0.0) {
            // No figure, and nothing here may invent one. A promotional message reaches this point
            // too, and this is what makes it harmless.
            Logger.d(TAG, "No amount in the notification — nothing created, nothing sent")
            return Outcome.SKIPPED_NO_AMOUNT
        }

        val direction = container.templateDirectionMemory.lookup(templateHash)
        if (direction == TransactionDirection.INCOMING) {
            Logger.d(TAG, "Template is known to be incoming — no expense created")
            return Outcome.SKIPPED_INCOMING
        }

        val vendor = preParse.vendor
        val bank = preParse.bank ?: knownBank
        val category = container.expensesRepository.defaultCategory()

        // Known to be a payment, known which way, and the user asked for those to be filed: the two
        // questions the model used to answer are both already answered, so there is nothing left to
        // review. Anything short of that goes to the queue.
        val settled = direction == TransactionDirection.OUTGOING &&
            container.templateDirectionMemory.lookupIsPayment(templateHash)
        if (settled && settings.autoAcceptNotificationExpenses) {
            val parsed = ExpenseParseResultParser.Parsed(
                title = title(vendor, bank, category?.name),
                totalAmount = amount,
                currency = settings.defaultCurrency,
                vendor = vendor,
                bank = bank,
                location = null,
                category = category?.name,
                date = null,
                time = null,
                items = emptyList()
            )
            val newId = com.voxapps.expenses.receiver.LlmResultReceiver().createExpenseFromParsed(
                appContext = context.applicationContext,
                container = container,
                parsed = parsed,
                imageName = null,
                preParse = null
            )
            // Same link the model path writes, so editing the record still teaches its template.
            container.templateDirectionMemory.linkRecord(newId, templateHash.orEmpty())
            Logger.d(TAG, "Created without a model: $amount ${settings.defaultCurrency}, vendor ${vendor ?: "—"}")
            return Outcome.CREATED
        }

        container.pendingNotificationExpenseRepository.addPending(
            PendingNotificationExpense(
                id = System.currentTimeMillis(),
                title = title(vendor, bank, category?.name),
                totalAmount = amount,
                currency = settings.defaultCurrency,
                vendor = vendor,
                category = category?.name,
                capturedAt = System.currentTimeMillis(),
                bank = bank,
                // The queue's own default, and the thing approving the entry confirms. It is a
                // starting position for the reviewer, not a claim about the message.
                direction = direction ?: TransactionDirection.OUTGOING,
                templateHash = templateHash
            )
        )
        Logger.d(TAG, "Queued for review without a model: $amount ${settings.defaultCurrency}")
        return Outcome.QUEUED_FOR_REVIEW
    }

    /**
     * The words that are known to be true, in the order they identify the record. Deliberately not
     * a sentence — a phrase would have to be composed, and composing is the one thing this path
     * refuses to do.
     */
    private fun title(vendor: String?, bank: String?, category: String?): String? =
        listOfNotNull(
            vendor?.takeIf { it.isNotBlank() },
            bank?.takeIf { it.isNotBlank() && it != vendor },
            category?.takeIf { it.isNotBlank() }
        ).firstOrNull()

    /** Whether captured notifications should skip the model entirely. */
    fun isEnabled(settings: ExpensesSettings): Boolean =
        settings.notificationModelUse == ExpensesSettings.NOTIFICATION_MODEL_NONE
}
