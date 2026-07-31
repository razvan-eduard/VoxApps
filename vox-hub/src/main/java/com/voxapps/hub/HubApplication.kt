package com.voxapps.hub

import android.app.Application
import com.voxapps.hub.di.HubContainer
import com.voxapps.hub.domain.backup.BackupScheduler
import com.voxapps.hub.domain.sync.ScheduledSyncScheduler
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
        ScheduledSyncScheduler.ensureScheduled(this)

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

        // Start/stop the VoxConnect Bridge to track the enable toggle and port, same
        // flow-collect-and-react shape as the logging flags above — this is the only place the
        // server's lifecycle is driven from, so a setting change while the process is already
        // running is picked up immediately, and a process restart with the toggle already on
        // starts it back up without the user having to re-flip it.
        if (initialSnapshot.voxConnectEnabled) {
            container.voxConnectServer.start(initialSnapshot.voxConnectPort)
        }
        container.settingsRepository.settingsFlow
            .map { it.voxConnectEnabled to it.voxConnectPort }
            .distinctUntilChanged()
            .onEach { (enabled, port) ->
                if (enabled) {
                    if (container.voxConnectServer.isRunning()) container.voxConnectServer.stop()
                    container.voxConnectServer.start(port)
                } else {
                    container.voxConnectServer.stop()
                }
            }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
