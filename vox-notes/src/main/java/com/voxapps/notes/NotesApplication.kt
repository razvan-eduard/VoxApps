package com.voxapps.notes

import android.app.Application
import com.voxapps.logging.Logger
import com.voxapps.notes.di.NotesContainer
import com.voxapps.notes.domain.llm.CategoryAutoMergeScheduler
import com.voxapps.notes.domain.llm.PendingLlmRequestScheduler
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
        PendingLlmRequestScheduler.ensureScheduled(this)

        // Apply the persisted debug-logging flags immediately (no lag waiting for the first
        // settingsFlow emission), then keep them in sync with any later Settings toggle.
        Logger.initialize(this, "VoxNotes")
        val initialSnapshot = container.settingsRepository.getSnapshot()
        Logger.setEnabled(initialSnapshot.debugLoggingEnabled)
        Logger.setToastsEnabled(initialSnapshot.debugToastsEnabled, this)
        container.settingsRepository.settingsFlow
            .map { it.debugLoggingEnabled to it.debugToastsEnabled }
            .distinctUntilChanged()
            .onEach { (loggingEnabled, toastsEnabled) ->
                Logger.setEnabled(loggingEnabled)
                Logger.setToastsEnabled(toastsEnabled)
            }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
