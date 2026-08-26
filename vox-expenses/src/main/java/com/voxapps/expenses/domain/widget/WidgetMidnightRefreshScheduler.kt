package com.voxapps.expenses.domain.widget

import android.content.Context
import com.voxapps.widget.WidgetMidnightRefresh

/** The scheduling logic lives in :core:widget; only this app's worker and work name live here. */
object WidgetMidnightRefreshScheduler {
    private const val UNIQUE_WORK_NAME = "expenses_widget_midnight_refresh"

    fun ensureScheduled(context: Context) =
        WidgetMidnightRefresh.ensureScheduled<WidgetMidnightRefreshWorker>(context, UNIQUE_WORK_NAME)
}
