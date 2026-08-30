package com.voxapps.expenses.domain.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.expenses.receiver.RescanGuard
import com.voxapps.expenses.ui.widget.ExpensesWidget

/**
 * Refreshes [ExpensesWidget] once a day, aligned to local midnight (scheduled by
 * [WidgetMidnightRefreshScheduler]). Without this, the widget's "Today" label/highlight only
 * updates when unrelated data changes trigger a redraw (see ExpensesContainer's reactive
 * updateAll() collector) or the OS's own 30-minute updatePeriodMillis floor happens to fire, so it
 * could otherwise show yesterday's date for a while after midnight.
 *
 * The standing notification gets the same nudge: its own midnight tick covers a *running*
 * [com.voxapps.expenses.receiver.RescanGuardService], and this covers one an OEM killed overnight —
 * best-effort, since a background service start can be refused (see [RescanGuard.start]); where it
 * is, the next app open brings the panel back.
 */
class WidgetMidnightRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ExpensesWidget().updateAll(applicationContext)
        RescanGuard.startIfNeeded(applicationContext)
        return Result.success()
    }
}
