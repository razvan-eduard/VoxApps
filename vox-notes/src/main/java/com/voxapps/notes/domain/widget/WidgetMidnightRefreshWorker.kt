package com.voxapps.notes.domain.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.notes.ui.widget.NotesWidget

/**
 * Refreshes [NotesWidget] once a day, aligned to local midnight (scheduled by
 * [WidgetMidnightRefreshScheduler]). Without this, the widget's "Today" label/highlight only
 * updates when unrelated data changes trigger a redraw or the OS's own 30-minute
 * updatePeriodMillis floor happens to fire, so it could otherwise show yesterday's date for a
 * while after midnight.
 */
class WidgetMidnightRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        NotesWidget().updateAll(applicationContext)
        return Result.success()
    }
}
