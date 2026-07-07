package com.voxapps.notes.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import com.voxapps.notes.NotesApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The satellite's entire "brain" — NO NLU/LLM. It receives a Commander-authored [VoxCommand] JSON,
 * runs a `when(op)`, and (for reads) returns a [VoxResult] as the ordered-broadcast result data.
 *
 * - create: always allowed (append; the DB is available, no UI is woken).
 * - read: refused with a spoken message when biometric is required and the session has expired —
 *   the DB is never touched in that case; otherwise the notes text is returned.
 *
 * Guarded by the `com.voxapps.notes.permission.COMMAND` custom permission (declared in the manifest).
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
                container.notesStateManager.addNote(
                    title = command.title,
                    text = command.text.orEmpty(),
                    categoryId = null
                )
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
                        setResult(Activity.RESULT_OK, responder.respond().toJson(), null)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
