package com.voxapps.expenses.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The tap targets a notification action offers — reached as broadcasts so a notification button
 * works whether or not the app's UI is up.
 *
 * [RescanGuard.ACTION_RESCAN] is the one that matters: it runs while the shade the person tapped
 * from is still open, hands the visible panel to [RedactedStubRecovery], and recovers the figures
 * the platform withheld.
 */
class ExpenseActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RescanGuard.ACTION_RESCAN -> RedactedStubRecovery.recover(context)
        }
    }
}
