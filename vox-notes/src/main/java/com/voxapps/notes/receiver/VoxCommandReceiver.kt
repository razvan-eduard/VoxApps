package com.voxapps.notes.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.voxapps.backup.VoxBackupDispatch
import com.voxapps.backup.VoxImportMode
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxFormSchema
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.ipc.VoxSatelliteSchema
import com.voxapps.notes.NotesApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The satellite's entire "brain" — NO NLU/LLM. It receives a Commander-authored [VoxCommand] JSON,
 * runs a `when(op)`, and (for reads) returns a [VoxResult] as the ordered-broadcast result data.
 *
 * - create: always allowed (append; the DB is available, no UI is woken).
 * - read: refused with a spoken message when biometric is required and the session has expired —
 *   the DB is never touched in that case; otherwise the notes text is returned.
 *
 * Guarded by the shared `com.voxapps.vox.permission.COMMAND` custom permission (declared once in
 * `:core:ipc`'s manifest).
 */
class VoxCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_COMMAND) return
        val command = VoxCommand.fromJson(intent.getStringExtra(VoxIpc.EXTRA_PAYLOAD)) ?: return

        val container = (context.applicationContext as NotesApplication).container

        when (command.op) {
            VoxIpc.OP_PING -> {
                // Handshake for Commander's "Vox Apps" discovery — no DB, no auth.
                setResult(Activity.RESULT_OK, VoxResult(ok = true, text = "pong").toJson(), null)
            }

            VoxIpc.OP_CREATE -> {
                val text = command.text.orEmpty()
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Read inside the coroutine — getSnapshot() blocks on DataStore until its
                        // cache warms, and a broadcast can be what cold-starts this process.
                        val settings = container.settingsRepository.getSnapshot()
                        // The same template every other capture runs through; for a spoken note it
                        // decides only one thing, but it decides it in the one place.
                        var resolved: com.voxapps.notes.data.VoiceNoteResult? = null
                        com.voxapps.recordflow.RecordFlow.dispatch(
                            spec = com.voxapps.notes.domain.llm.NoteVoiceFlow(container) { resolved = it },
                            input = command,
                            level = com.voxapps.notes.data.preferences.NotesSettings.VOICE_FLOW_SUPPORT.default
                        ) { _, _ -> }
                        if (settings.voiceSaveToastEnabled) {
                            val label = command.title?.takeIf { it.isNotBlank() } ?: text
                            val template = container.languageManager.getString("toast_note_saved")
                            val msg = String.format(template, label) +
                                (resolved?.categoryName?.let { " · $it" } ?: "")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_GET_SCHEMA -> {
                // Derived from the flow rather than restated here: what this app tells Commander and
                // what it actually does are then one declaration. Notes asks nothing — the raw
                // transcript IS the note body — so what Commander is handed says exactly that.
                val flow = com.voxapps.notes.domain.llm.NoteVoiceFlow(container)
                val schema = VoxSatelliteSchema.of(
                    asksModel = !flow.support.default.staysOnDevice,
                    promptTemplate = null,
                    taskId = flow.taskId
                )
                setResult(Activity.RESULT_OK, VoxResult(ok = true, text = schema.toJson()).toJson(), null)
            }

            VoxIpc.OP_READ -> {
                // Async within the ordered-broadcast window; the lock decision (and whether the DB
                // is touched at all) lives in NotesReadResponder so it's unit-testable.
                val responder = NotesReadResponder(
                    container.settingsRepository,
                    container.sessionManager,
                    container.notesRepository,
                    container.lockedMessage
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Must use the PendingResult's own setResultData, not the inherited
                        // BroadcastReceiver.setResult() — the latter throws "Call while result is
                        // not pending" once called from outside onReceive()'s synchronous window,
                        // which goAsync()'s whole point is to let us do.
                        pending.setResultData(responder.respond(command.dateFrom, command.dateTo).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_EXPORT -> {
                val handler = NotesExportImportHandler(
                    context.applicationContext,
                    container.settingsRepository,
                    container.sessionManager,
                    container.notesRepository,
                    container.attachmentDao,
                    container.lockedMessage
                )
                val scope = command.exportScope ?: VoxIpc.EXPORT_SCOPE_BOTH
                VoxBackupDispatch.dispatch(this) {
                    handler.export(scope, includePhotos = command.includePhotos)
                }
            }

            VoxIpc.OP_IMPORT -> {
                val handler = NotesExportImportHandler(
                    context.applicationContext,
                    container.settingsRepository,
                    container.sessionManager,
                    container.notesRepository,
                    container.attachmentDao,
                    container.lockedMessage
                )
                VoxBackupDispatch.dispatch(this) {
                    handler.import(command.text.orEmpty(), VoxImportMode.fromWireValue(command.importMode))
                }
            }

            VoxIpc.OP_SYNC_EXPORT -> {
                val handler = NotesSyncHandler(
                    container.settingsRepository,
                    container.sessionManager,
                    container.notesRepository,
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
                val handler = NotesSyncHandler(
                    container.settingsRepository,
                    container.sessionManager,
                    container.notesRepository,
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
                // Field keys/types mirror NotesSyncHandler's export JSON exactly.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val categoryNames = container.notesRepository.categories.first().map { it.name }
                        val schema = VoxFormSchema.domainSchema(
                            domain = "notes",
                            titleField = "title",
                            titleFallbackField = "text",
                            subtitleFields = listOf("categoryName"),
                            sortField = "updatedAt",
                            fields = listOf(
                                VoxFormSchema.field("title", "Title", "text"),
                                VoxFormSchema.field("text", "Text", "text", required = true),
                                VoxFormSchema.field("categoryName", "Category", "category", options = categoryNames),
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
