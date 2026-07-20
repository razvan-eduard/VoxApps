package com.voxapps.calendarapp.ui.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The actual manifest-registered `AppWidgetProvider` — trivial glue delegating all real work to
 * [CalendarWidget] (a `GlanceAppWidget`, not a `RemoteViews`/`AppWidgetProvider` subclass itself).
 */
class CalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarWidget()
}
