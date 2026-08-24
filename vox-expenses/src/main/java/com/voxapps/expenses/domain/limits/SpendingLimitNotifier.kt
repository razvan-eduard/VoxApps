package com.voxapps.expenses.domain.limits

import android.content.Context
import android.content.Intent
import com.voxapps.design.notifications.VoxNotifier
import com.voxapps.expenses.ExpensesActivity
import com.voxapps.expenses.data.preferences.ExpensesSettings
import com.voxapps.expenses.data.preferences.notificationPrefs
import com.voxapps.expenses.domain.localization.LanguageManager

private const val CHANNEL_ID = "spending_limit_alerts"

/**
 * Posts a plain local Android notification when a spending limit is exceeded — deliberately NOT a Vox
 * contract broadcast (see [com.voxapps.expenses.domain.limits.SpendingLimitCheckWorker]'s doc comment);
 * this is a device-local alert, not a cross-app message.
 */
object SpendingLimitNotifier {

    fun notify(
        context: Context,
        languageManager: LanguageManager,
        notificationId: Int,
        categoryLabel: String,
        spent: Double,
        limit: Double,
        homeCurrency: String,
        settings: ExpensesSettings
    ) {
        VoxNotifier.post(
            context = context,
            channelBaseId = CHANNEL_ID,
            channelName = languageManager.getString("spending_limit_channel_name"),
            notificationId = notificationId,
            title = languageManager.getString("spending_limit_exceeded_title"),
            text = String.format(
                languageManager.getString("spending_limit_exceeded_text"),
                categoryLabel,
                "%.2f".format(spent),
                "%.2f".format(limit),
                homeCurrency
            ),
            contentIntent = Intent(context, ExpensesActivity::class.java),
            prefs = settings.notificationPrefs()
        )
    }
}
