package com.voxapps.notes.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.ipc.VoxSatelliteSchema
import com.voxapps.notes.NotesApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
                val settings = container.settingsRepository.getSnapshot()
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val resolved = container.notesRepository.addVoiceNote(
                            title = command.title,
                            text = text,
                            spokenCategory = command.category,
                            defaultCategoryId = settings.defaultVoiceCategoryId,
                            autoCreate = settings.autoCreateVoiceCategory,
                            createdAt = System.currentTimeMillis()
                        )
                        if (settings.voiceSaveToastEnabled) {
                            val label = command.title?.takeIf { it.isNotBlank() } ?: text
                            val template = container.languageManager.getString("toast_note_saved")
                            val msg = String.format(template, label) +
                                (resolved.categoryName?.let { " · $it" } ?: "")
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
                // Notes' voice flow has no extraction prompt builder at all — the raw transcript IS
                // the note body, category comes straight from Commander's own classification call.
                // needsExtractionPass=false, declared explicitly rather than left as a Commander-side
                // implicit default (see the collapsed voice-command plan, section 1b/6a).
                setResult(Activity.RESULT_OK, VoxResult(ok = true, text = VoxSatelliteSchema(needsExtractionPass = false).toJson()).toJson(), null)
            }

            VoxIpc.OP_READ -> {
                // Async within the ordered-broadcast window; the lock decision (and whether the DB
                // is touched at all) lives in NotesReadResponder so it's unit-testable.
                val responder = NotesReadResponder(
                    container.settingsRepository,
                    container.sessionManager,
                    container.notesRepository
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
                    container.settingsRepository,
                    container.sessionManager,
                    container.notesRepository
                )
                val pending = goAsync()
                val scope = command.exportScope ?: VoxIpc.EXPORT_SCOPE_BOTH
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pending.setResultData(handler.export(scope).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_IMPORT -> {
                val handler = NotesExportImportHandler(
                    container.settingsRepository,
                    container.sessionManager,
                    container.notesRepository
                )
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        pending.setResultData(handler.import(command.text.orEmpty()).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }

            VoxIpc.OP_SYNC_EXPORT -> {
                val handler = NotesSyncHandler(
                    container.settingsRepository,
                    container.sessionManager,
                    container.notesRepository
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
                    container.notesRepository
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
        }
    }
}
