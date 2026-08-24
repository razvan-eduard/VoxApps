package com.voxapps.expenses.domain.recurring

import android.content.Context
import android.content.Intent
import com.voxapps.design.notifications.VoxNotifier
import com.voxapps.expenses.ExpensesActivity
import com.voxapps.expenses.data.RecurringPayment
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.notificationPrefs
import com.voxapps.expenses.domain.localization.LanguageManager
import java.text.DateFormat
import java.util.Date

private const val CHANNEL_ID = "recurring_payment_reminders"

/**
 * Tells you a bill is nearly due.
 *
 * A device-local alert and nothing more — no Vox broadcast, no calendar entry, no expense. This app
 * does not depend on any other being installed, so the reminder it owes you is one it posts itself.
 *
 * The figure is named as last month's rather than as what you owe, because that is all it is. A
 * reminder that states an amount with confidence is a reminder that will eventually state the wrong
 * one, and being confidently wrong about money is worse than being vague about it.
 */
object RecurringPaymentNotifier {

    /**
     * Notification ids are offset from the payment id so a reminder and a spending-limit alert for
     * the same row cannot overwrite each other — both call `notify` on the same manager.
     */
    private const val ID_OFFSET = 900_000

    fun notify(
        context: Context,
        languageManager: LanguageManager,
        payment: RecurringPayment,
        dueAtMillis: Long,
        settings: ExpensesSettings
    ) {
        val due = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dueAtMillis))
        val text = payment.expectedAmount
            ?.let {
                languageManager.getString("recurring_reminder_text_amount")
                    .format(payment.vendorLabel, due, "%.2f".format(it), payment.currency.orEmpty())
            }
            ?: languageManager.getString("recurring_reminder_text").format(payment.vendorLabel, due)

        VoxNotifier.post(
            context = context,
            channelBaseId = CHANNEL_ID,
            channelName = languageManager.getString("recurring_reminder_channel_name"),
            notificationId = ID_OFFSET + payment.id.toInt(),
            title = languageManager.getString("recurring_reminder_title"),
            text = text,
            contentIntent = Intent(context, ExpensesActivity::class.java),
            prefs = settings.notificationPrefs(),
            smallIcon = android.R.drawable.ic_popup_reminder
        )
    }
}
