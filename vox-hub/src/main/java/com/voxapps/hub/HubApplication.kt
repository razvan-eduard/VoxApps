package com.voxapps.hub

import android.app.Application
import com.voxapps.hub.di.HubContainer
import com.voxapps.hub.domain.backup.BackupScheduler
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class HubApplication : Application() {
    lateinit var container: HubContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = HubContainer(this)

        // Re-assert the WorkManager schedule on every process start (idempotent via
        // ExistingPeriodicWorkPolicy.UPDATE) so a setting change made while the process was dead is
        // still honored (mirrors vox-notes' NotesApplication).
        val initialSnapshot = container.settingsRepository.getSnapshot()
        BackupScheduler.reschedule(this, initialSnapshot.backupInterval)

        // Apply the persisted debug-logging flags immediately, then keep them in sync with any
        // later Settings toggle (mirrors every other satellite app's Application class).
        Logger.initialize(this, "VoxHub")
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
