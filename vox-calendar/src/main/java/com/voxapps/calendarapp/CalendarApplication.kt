package com.voxapps.calendarapp

import android.app.Application
import com.voxapps.ipc.PendingLlmRequestScheduler
import com.voxapps.ipc.VoxLlmQueueHost
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.calendarapp.domain.llm.CalendarEventParsePromptBuilder
import com.voxapps.calendarapp.domain.llm.GeneratedParsedSchema
import com.voxapps.calendarapp.domain.llm.LlmTasks
import com.voxapps.calendarapp.domain.subscription.CalendarSubscriptionSyncScheduler
import com.voxapps.calendarapp.domain.widget.WidgetMidnightRefreshScheduler
import com.voxapps.ipc.VoxDataTransferClient
import com.voxapps.ipc.VoxSatelliteSchema
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class CalendarApplication : Application(), VoxLlmQueueHost {
    lateinit var container: CalendarContainer
        private set

    override val voxLlmRequestQueue: VoxLlmRequestQueue
        get() = container.pendingLlmRequestQueue


    override fun onCreate() {
        super.onCreate()
        container = CalendarContainer(this)
        PendingLlmRequestScheduler.ensureScheduled(this)
        WidgetMidnightRefreshScheduler.ensureScheduled(this)
        CalendarSubscriptionSyncScheduler.ensureScheduled(this)

        // Apply the persisted debug-logging flags immediately (no lag waiting for the first
        // settingsFlow emission), then keep them in sync with any later Settings toggle.
        Logger.initialize(this, "VoxCalendar")
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

        // Push a corrected schema to Commander's cache the instant our own calendar list changes —
        // see vox-expenses' ExpensesApplication for the full reasoning (mirrors it exactly).
        container.calendarRepository.layers
            .drop(1)
            .map { it.map { l -> l.name } }
            .distinctUntilChanged()
            .onEach {
                // Composed by the flow, exactly as the on-demand fetch composes it — a pushed schema
                // and a fetched one describing different arrangements is the failure this one
                // declaration exists to prevent. The layer list is what changed; the flow reads the
                // current one itself.
                val flow = com.voxapps.calendarapp.domain.llm.CalendarVoiceFlow(container)
                val schema = VoxSatelliteSchema.of(
                    asksModel = !flow.support.default.staysOnDevice,
                    promptTemplate = flow.promptTemplate(flow.support.default.asks),
                    taskId = flow.taskId,
                    fieldSchemaVersion = GeneratedParsedSchema.VERSION
                )
                VoxDataTransferClient.pushSchemaChanged(this, schema)
            }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}
