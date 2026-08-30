package com.voxapps.calendarapp.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.backup.VoxBackupDispatch
import com.voxapps.backup.VoxImportMode
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.domain.llm.CalendarEventParsePromptBuilder
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.domain.llm.CalendarEventParseRequestSender
import com.voxapps.calendarapp.domain.llm.CalendarVoiceFlow
import com.voxapps.recordflow.RecordFlow
import com.voxapps.calendarapp.domain.llm.GeneratedParsedSchema
import com.voxapps.calendarapp.domain.llm.LlmTasks
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxFormSchema
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.ipc.VoxSatelliteSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The satellite's entry point for Commander's command bus — NO NLU here either, just a `when(op)`
 * (mirrors vox-expenses' VoxCommandReceiver). `export`/`import` land in Phase 6; PING, READ, and CREATE
 * are wired up. `create` never inserts synchronously: a raw utterance needs real structured extraction
 * (date/time/type), so it only fires the generic-LLM-hook parse request (see
 * [CalendarEventParseRequestSender]) — the actual insert happens later in [LlmResultReceiver] once the
 * async reply arrives.
 *
 * Guarded by the shared `com.voxapps.vox.permission.COMMAND` custom permission (declared once in
 * `:core:ipc`'s manifest).
 */
class VoxCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_COMMAND) return
        val command = VoxCommand.fromJson(intent.getStringExtra(VoxIpc.EXTRA_PAYLOAD)) ?: return

        val container = (context.applicationContext as CalendarApplication).container

        when (command.op) {
            VoxIpc.OP_PING -> {
                // Handshake for Commander's "Vox Apps" discovery — no DB, no auth.
                setResult(Activity.RESULT_OK, VoxResult(ok = true, text = "pong").toJson(), null)
            }

            VoxIpc.OP_CREATE -> {
                val text = command.text.orEmpty()
                if (text.isBlank()) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Inside the coroutine, not in onReceive: the flow reads DataStore, which
                        // falls back to a blocking read until its cache warms, and a broadcast can
                        // be what cold-starts this process.
                        //
                        // command.category carries the explicitly-named target layer, if any — the
                        // manifest's nluHint reuses this field the same way vox-expenses reuses it
                        // for "target category name". Folded into the raw text as an explicit hint
                        // rather than adding a new VoxCommand field for it.
                        val spoken = command.category?.takeIf { it.isNotBlank() }
                            ?.let { "$text (calendar: $it)" }
                            ?: text
                        // Through the flow rather than composing here: the same entry every other
                        // capture in this app goes through, leaving this receiver only the question
                        // of how a request travels.
                        val settings = container.settingsRepository.getSnapshot()
                        RecordFlow.dispatch(
                            spec = CalendarVoiceFlow(context.applicationContext, container),
                            input = spoken,
                            level = CalendarSettings.voiceLevelOf(settings.voiceLlmLevel),
                            send = { task, prompt ->
                                CalendarEventParseRequestSender.send(
                                    context = context.applicationContext,
                                    queue = container.pendingLlmRequestQueue,
                                    task = task,
                                    promptText = prompt,
                                    rawText = spoken
                                )
                            }
                        )
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_GET_SCHEMA -> {
                // Commander fetches and caches this (Integrations' Refresh button and the pushed
                // updates in CalendarApplication), not per voice command. asksModel mirrors the
                // chosen voice rung: at the offline one Commander sends the words as they are and
                // the flow files them for review.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settings = container.settingsRepository.getSnapshot()
                        // Derived from the flow rather than restated: what this app tells Commander
                        // and what it does locally are then one declaration.
                        val flow = com.voxapps.calendarapp.domain.llm.CalendarVoiceFlow(context.applicationContext, container)
                        val level = CalendarSettings.voiceLevelOf(settings.voiceLlmLevel)
                        val schema = VoxSatelliteSchema.of(
                            asksModel = !level.staysOnDevice,
                            promptTemplate = flow.promptTemplate(level.asks),
                            taskId = flow.taskId,
                            fieldSchemaVersion = GeneratedParsedSchema.VERSION
                        )
                        pending.setResultData(VoxResult(ok = true, text = schema.toJson()).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_READ -> {
                val responder = CalendarReadResponder(
                    container.settingsRepository,
                    container.sessionManager,
                    container.calendarRepository,
                    container.lockedMessage
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Must use the PendingResult's own setResultData, not the inherited
                        // BroadcastReceiver.setResult() — the latter throws "Call while result is
                        // not pending" once called from outside onReceive()'s synchronous window,
                        // which goAsync()'s whole point is to let us do.
                        pending.setResultData(responder.respond().toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_EXPORT -> {
                val handler = CalendarExportImportHandler(
                    context.applicationContext,
                    container.settingsRepository,
                    container.sessionManager,
                    container.calendarRepository,
                    container.attachmentDao,
                    container.toDoListDao,
                    container.lockedMessage
                )
                val scope = command.exportScope ?: VoxIpc.EXPORT_SCOPE_BOTH
                VoxBackupDispatch.dispatch(this) {
                    handler.export(scope, includePhotos = command.includePhotos)
                }
            }

            VoxIpc.OP_IMPORT -> {
                val handler = CalendarExportImportHandler(
                    context.applicationContext,
                    container.settingsRepository,
                    container.sessionManager,
                    container.calendarRepository,
                    container.attachmentDao,
                    container.toDoListDao,
                    container.lockedMessage
                )
                VoxBackupDispatch.dispatch(this) {
                    handler.import(command.text.orEmpty(), VoxImportMode.fromWireValue(command.importMode))
                }
            }

            VoxIpc.OP_SYNC_EXPORT -> {
                val handler = CalendarSyncHandler(
                    container.settingsRepository,
                    container.sessionManager,
                    container.calendarRepository,
                    container.toDoRepository,
                    container.lockedMessage
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pending.setResultData(handler.export(command).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_SYNC_MERGE -> {
                val handler = CalendarSyncHandler(
                    container.settingsRepository,
                    container.sessionManager,
                    container.calendarRepository,
                    container.toDoRepository,
                    container.lockedMessage
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pending.setResultData(handler.merge(command).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_GET_FIELD_SCHEMA -> {
                // Field keys/types mirror CalendarSyncHandler's export JSON exactly. "Category" here
                // is calendar's own concept — a layer — hence the categoryName-shaped field is keyed
                // layerName, not categoryName, to match the wire shape sync_export/sync_merge use.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val layerNames = container.calendarRepository.layers.first().map { it.name }
                        val schema = VoxFormSchema.domainSchema(
                            domain = "calendar",
                            titleField = "title",
                            subtitleFields = listOf("startMillis", "layerName", "tags"),
                            sortField = "startMillis",
                            sortDescending = false,
                            upcomingOnlyField = "startMillis",
                            fields = listOf(
                                VoxFormSchema.field(
                                    "type", "Type", "enum",
                                    required = true, options = listOf("EVENT", "TASK")
                                ),
                                VoxFormSchema.field("title", "Title", "text", required = true),
                                VoxFormSchema.field("description", "Description", "text"),
                                VoxFormSchema.field("location", "Location", "text"),
                                VoxFormSchema.field("startMillis", "Start", "datetime", required = true),
                                VoxFormSchema.field("endMillis", "End", "datetime"),
                                VoxFormSchema.field("allDay", "All day", "bool"),
                                VoxFormSchema.field("completed", "Completed", "bool"),
                                VoxFormSchema.field(
                                    "recurrenceFrequency", "Repeats", "enum",
                                    options = listOf("NONE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")
                                ),
                                VoxFormSchema.field("layerName", "Layer", "category", options = layerNames),
                                VoxFormSchema.field("tags", "Tags", "tags"),
                            )
                        )
                        pending.setResultData(VoxResult(ok = true, text = schema.toString()).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
