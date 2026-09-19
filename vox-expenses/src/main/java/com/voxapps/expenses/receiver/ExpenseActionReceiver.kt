package com.voxapps.expenses.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The tap targets a notification action offers — reached as broadcasts so a notification button
 * works whether or not the app's UI is up.
 *
 * [RescanGuard.ACTION_RESCAN] runs while the shade the person tapped from is still open, hands the
 * visible panel to [RedactedStubRecovery], and recovers the figures the platform withheld.
 * [RescanGuard.ACTION_FORCE_RECHECK] re-runs the ordinary capture pipeline over every notification
 * still in the shade — the same thing the settings screen's own button does.
 */
class ExpenseActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RescanGuard.ACTION_RESCAN -> RedactedStubRecovery.recover(context)
            RescanGuard.ACTION_FORCE_RECHECK -> PaymentNotificationListenerService.forceRecheckNow(context)
        }
    }
}
