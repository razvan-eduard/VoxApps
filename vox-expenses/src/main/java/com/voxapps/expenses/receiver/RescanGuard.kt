package com.voxapps.expenses.receiver

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.voxapps.expenses.ExpensesApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controls [RescanGuardService] — the presence the whole feature stands on — and announces, once,
 * when a payment arrives hidden by the platform's guard so the person knows to look at the standing
 * notification the service keeps. The notification itself, with its running total and the count of
 * what waits, is the service's to draw; this only starts it, stops it, and nudges.
 */
object RescanGuard {

    const val ACTION_RESCAN = "com.voxapps.expenses.action.RESCAN_REDACTED"

    /** Brings the presence service up. Safe to call repeatedly — the OS folds a re-start into the
     *  running instance, which just keeps drawing its notification. */
    fun start(context: Context) {
        // Starting a foreground service from the background is restricted on Android 12+; a capture
        // arriving while the app is hibernated can hit that. Best-effort: the next app launch (its
        // own onCreate) brings the service up when a background start was refused.
        runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, RescanGuardService::class.java)
            )
        }
    }

    /** Takes the presence service (and its notification) down — the feature was switched off. */
    fun stop(context: Context) {
        context.applicationContext.stopService(
            Intent(context.applicationContext, RescanGuardService::class.java)
        )
    }

    /**
     * Brings the one notification up if it has a reason to exist: the standing dashboard the person
     * asked for, or a redacted payment waiting to be rescanned. The place to call it whenever the
     * app is unambiguously foreground (launch, resume), since a background start can be refused.
     */
    fun startIfNeeded(context: Context) {
        val app = context.applicationContext as ExpensesApplication
        CoroutineScope(Dispatchers.IO).launch {
            val settings = app.container.settingsRepository.getSnapshot()
            val hasStubs = app.container.pendingNotificationExpenseRepository.snapshot().any { it.redactedStub }
            if (settings.permanentNotificationEnabled || (settings.guardNotificationEnabled && hasStubs)) {
                start(context)
            }
        }
    }

    /**
     * A payment came in hidden: say so once, and bring the one notification up to carry the rescan
     * line. A background start can be refused on Android 12+, in which case the toast still lands
     * and the notification appears the next time the app is opened (see [startIfNeeded] on resume).
     */
    fun stubQueued(context: Context) {
        val app = context.applicationContext as ExpensesApplication
        CoroutineScope(Dispatchers.IO).launch {
            val lang = app.container.languageManager
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context, lang.getString("rescan_stub_toast"), android.widget.Toast.LENGTH_LONG
                ).show()
            }
            start(context)
        }
    }
}
