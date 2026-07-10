package com.voxapps.notes

import android.app.Application
import com.voxapps.logging.Logger
import com.voxapps.notes.di.NotesContainer
import com.voxapps.notes.domain.llm.CategoryAutoMergeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class NotesApplication : Application() {
    lateinit var container: NotesContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = NotesContainer(this)
        // Re-assert the WorkManager schedule on every process start (idempotent via
        // ExistingPeriodicWorkPolicy.UPDATE) so a setting change made while the process was dead is
        // still honored.
        CategoryAutoMergeScheduler.reschedule(this, container.settingsRepository.getSnapshot().scheduledMergeInterval)

        // Apply the persisted debug-logging flag immediately (no lag waiting for the first
        // settingsFlow emission), then keep it in sync with any later Settings toggle.
        Logger.setEnabled(container.settingsRepository.getSnapshot().debugLoggingEnabled)
        container.settingsRepository.settingsFlow
            .map { it.debugLoggingEnabled }
            .distinctUntilChanged()
            .onEach { Logger.setEnabled(it) }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
