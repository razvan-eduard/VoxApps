package com.voxapps.expenses

import android.app.Application
import com.voxapps.expenses.di.ExpensesContainer
import com.voxapps.expenses.domain.limits.SpendingLimitScheduler
import com.voxapps.expenses.domain.llm.CategoryAutoMergeScheduler
import com.voxapps.expenses.domain.llm.ExpenseDeduplicationScheduler
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class ExpensesApplication : Application() {
    lateinit var container: ExpensesContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ExpensesContainer(this)

        // Re-assert both WorkManager schedules on every process start (idempotent via
        // ExistingPeriodicWorkPolicy.UPDATE) so a setting change made while the process was dead is
        // still honored (mirrors vox-notes' NotesApplication).
        val settingsSnapshot = container.settingsRepository.getSnapshot()
        CategoryAutoMergeScheduler.reschedule(this, settingsSnapshot.scheduledMergeInterval)
        ExpenseDeduplicationScheduler.reschedule(this, settingsSnapshot.scheduledExpenseDedupInterval)
        SpendingLimitScheduler.ensureScheduled(this)

        // Apply the persisted debug-logging flag immediately, then keep it in sync with any later
        // Settings toggle (mirrors vox-notes' NotesApplication).
        Logger.setEnabled(settingsSnapshot.debugLoggingEnabled)
        Logger.setToastsEnabled(settingsSnapshot.debugToastsEnabled, this)
        container.settingsRepository.settingsFlow
            .map { it.debugLoggingEnabled to it.debugToastsEnabled }
            .distinctUntilChanged()
            .onEach { (logging, toasts) -> 
                Logger.setEnabled(logging)
                Logger.setToastsEnabled(toasts, this)
            }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
