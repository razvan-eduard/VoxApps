package com.voxapps.notes

import android.app.Application
import com.voxapps.ipc.PendingLlmRequestScheduler
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxLlmQueueHost
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.ipc.VoxSatelliteSchema
import com.voxapps.logging.Logger
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.di.NotesContainer
import com.voxapps.notes.domain.llm.CategoryAutoMergeScheduler
import com.voxapps.notes.domain.llm.NoteVoiceFlow
import com.voxapps.notes.domain.widget.WidgetMidnightRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class NotesApplication : Application(), VoxLlmQueueHost {
    lateinit var container: NotesContainer
        private set

    override val voxLlmRequestQueue: VoxLlmRequestQueue
        get() = container.pendingLlmRequestQueue


    override fun onCreate() {
        super.onCreate()
        container = NotesContainer(this)
        // Re-assert the WorkManager schedule on every process start (idempotent via
        // ExistingPeriodicWorkPolicy.UPDATE) so a setting change made while the process was dead is
        // still honored.
        CategoryAutoMergeScheduler.reschedule(this, container.settingsRepository.getSnapshot().scheduledMergeInterval)
        PendingLlmRequestScheduler.ensureScheduled(this)
        WidgetMidnightRefreshScheduler.ensureScheduled(this)

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

        // Push a corrected schema to Commander's cache the instant it goes stale — the same
        // verified-event push vox-expenses does for its categories. Two things stale it here: the
        // chosen voice rung (asksModel and the template both follow it) and the category names the
        // full rung's template embeds. drop(1) skips the initial-load emission on both.
        container.settingsRepository.settingsFlow
            .map { it.voiceLlmLevel }
            .distinctUntilChanged()
            .drop(1)
            .onEach { pushVoiceSchema() }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        container.notesRepository.categories
            .drop(1)
            .map { it.map { c -> c.name } }
            .distinctUntilChanged()
            .onEach { pushVoiceSchema() }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    /**
     * Composed by the flow, exactly as the on-demand fetch composes it — a pushed schema and a
     * fetched one describing different arrangements is the failure this one declaration exists to
     * prevent. The flow reads the current categories itself; the rung comes from the setting.
     */
    private suspend fun pushVoiceSchema() {
        val settings = container.settingsRepository.getSnapshot()
        val level = NotesSettings.voiceLevelOf(settings.voiceLlmLevel)
        val flow = NoteVoiceFlow(container)
        val schema = VoxSatelliteSchema.of(
            asksModel = !level.staysOnDevice,
            promptTemplate = if (level.staysOnDevice) null else flow.promptTemplate(level.asks),
            taskId = flow.taskId
        )
        VoxDataTransferClient.pushSchemaChanged(this, schema)
    }
}
