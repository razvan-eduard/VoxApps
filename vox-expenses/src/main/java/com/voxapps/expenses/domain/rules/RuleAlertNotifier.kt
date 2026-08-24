package com.voxapps.expenses.domain.rules

import android.content.Context
import android.content.Intent
import com.voxapps.design.notifications.VoxNotifier
import com.voxapps.expenses.ExpensesActivity
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.notificationPrefs
import com.voxapps.expenses.domain.localization.LanguageManager
import com.voxapps.ipc.VoxIpc

private const val CHANNEL_ID = "rule_alerts"

/** A rule recognised a record and asked to be told: which rule, and the payment that set it off. */
data class RuleAlert(
    val ruleId: Long,
    val ruleName: String,
    val expenseId: Long,
    val vendor: String?,
    val amount: Double,
    val currency: String
)

/**
 * Tells you a rule of yours just fired.
 *
 * A payment over a figure, from a shop, on a card — whatever the rule recognises. The alert names
 * the rule rather than restating its conditions, because the rule already carries the name its
 * author gave it, and that name is what they meant by it.
 *
 * Tapping it opens the expense that set it off, not the app in general: an alert about a specific
 * payment that lands anywhere else makes the person go find it.
 */
object RuleAlertNotifier {

    /** Kept clear of the recurring-payment reminders' block so two alerts about one row cannot
     *  overwrite each other. */
    private const val ID_OFFSET = 700_000

    fun notify(
        context: Context,
        languageManager: LanguageManager,
        settings: ExpensesSettings,
        alerts: List<RuleAlert>
    ) {
        for (alert in alerts) {
            val what = listOfNotNull(
                alert.vendor?.takeIf { it.isNotBlank() },
                "%.2f %s".format(alert.amount, alert.currency)
            ).joinToString(" · ")

            VoxNotifier.post(
                context = context,
                channelBaseId = CHANNEL_ID,
                channelName = languageManager.getString("rule_alert_channel_name"),
                // One alert per (rule, expense): two rules recognising one payment have something
                // different to say, and neither should silence the other.
                notificationId = ID_OFFSET + (alert.expenseId.toInt() * 31 + alert.ruleId.toInt()),
                title = alert.ruleName.ifBlank { languageManager.getString("rule_alert_title") },
                text = what,
                contentIntent = Intent(context, ExpensesActivity::class.java)
                    .putExtra(VoxIpc.EXTRA_EXPENSE_ID, alert.expenseId),
                prefs = settings.notificationPrefs()
            )
        }
    }
}
