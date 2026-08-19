package com.voxapps.calendarapp.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.backup.VoxBackupDispatch
import com.voxapps.backup.VoxImportMode
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.domain.llm.CalendarEventParsePromptBuilder
import com.voxapps.calendarapp.domain.llm.CalendarEventParseRequestSender
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
                        // Read inside the coroutine, not in onReceive: getSnapshot() falls back to a
                        // blocking DataStore read until its cache warms, and a broadcast can be what
                        // cold-starts this process — so on main it was a guaranteed disk read on the
                        // main thread. OP_GET_SCHEMA below already had it in the right place.
                        val settings = container.settingsRepository.getSnapshot()
                        val layerNames = container.calendarRepository.layers.first().map { it.name }
                        val todoListNames = container.toDoRepository.lists.first().map { it.title }
                        CalendarEventParseRequestSender.send(
                            context = context.applicationContext,
                            queue = container.pendingLlmRequestQueue,
                            // command.category carries the explicitly-named target layer, if any — the
                            // manifest's nluHint reuses this field the same way vox-expenses reuses it
                            // for "target category name". Fold it into the raw text as an explicit hint
                            // rather than adding a new VoxCommand field for it.
                            rawText = command.category?.takeIf { it.isNotBlank() }
                                ?.let { "$text (calendar: $it)" }
                                ?: text,
                            existingLayers = layerNames,
                            existingTodoLists = todoListNames,
                            languageCode = settings.language
                        )
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_GET_SCHEMA -> {
                // See the collapsed voice-command plan: Commander fetches and caches this once
                // (Integrations' Refresh button), not per voice command.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settings = container.settingsRepository.getSnapshot()
                        val layerNames = container.calendarRepository.layers.first().map { it.name }
                        val todoListNames = container.toDoRepository.lists.first().map { it.title }
                        // Derived from the flow rather than restated: what this app tells Commander
                        // and what it does locally are then one declaration.
                        val flow = com.voxapps.calendarapp.domain.llm.CalendarVoiceFlow(container)
                        val schema = VoxSatelliteSchema.of(
                            asksModel = !flow.support.default.staysOnDevice,
                            promptTemplate = flow.promptTemplate(flow.support.default.asks),
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
                    container.lockedMessage
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pending.setResultData(handler.export(command.since ?: 0L, command.scopeNames).toJson())
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
                    container.lockedMessage
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pending.setResultData(handler.merge(command.text.orEmpty()).toJson())
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
