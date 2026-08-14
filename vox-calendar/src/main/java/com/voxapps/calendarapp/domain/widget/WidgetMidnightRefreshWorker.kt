package com.voxapps.calendarapp.domain.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.calendarapp.ui.widget.CalendarWidget

/**
 * Refreshes [CalendarWidget] once a day, aligned to local midnight (scheduled by
 * [WidgetMidnightRefreshScheduler]). Without this, the widget's "Today" label/highlight only
 * updates when unrelated data changes trigger a redraw (see CalendarContainer's reactive
 * updateAll() collector) or the OS's own 30-minute updatePeriodMillis floor happens to fire, so it
 * could otherwise show yesterday's date for a while after midnight.
 */
class WidgetMidnightRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // The new day may re-open routine lists (see ToDoList.routineDaysMask) — reset before the
        // widget refreshes below so they redraw with the cleared checkmarks.
        (applicationContext as com.voxapps.calendarapp.CalendarApplication)
            .container.toDoRepository.resetRoutinesForToday()
        CalendarWidget().updateAll(applicationContext)
        com.voxapps.calendarapp.ui.widget.ToDoListsWidget().updateAll(applicationContext)
        com.voxapps.calendarapp.ui.widget.ToDoListWidget().updateAll(applicationContext)
        return Result.success()
    }
}
