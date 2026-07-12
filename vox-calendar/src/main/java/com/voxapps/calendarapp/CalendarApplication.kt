package com.voxapps.calendarapp

import android.app.Application
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class CalendarApplication : Application() {
    lateinit var container: CalendarContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = CalendarContainer(this)

        // Apply the persisted debug-logging flag immediately, then keep it in sync with any later
        // Settings toggle (mirrors vox-expenses' ExpensesApplication).
        Logger.setEnabled(container.settingsRepository.getSnapshot().debugLoggingEnabled)
        container.settingsRepository.settingsFlow
            .map { it.debugLoggingEnabled }
            .distinctUntilChanged()
            .onEach { Logger.setEnabled(it) }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
