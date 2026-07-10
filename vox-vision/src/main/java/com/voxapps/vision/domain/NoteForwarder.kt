package com.voxapps.vision.domain

import android.content.Context
import android.content.Intent
import com.voxapps.logging.Logger
import android.widget.Toast
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.vision.VisionApplication

private const val TAG = "NoteForwarder"
private const val NOTES_PACKAGE = "com.voxapps.notes"

/**
 * Sends a create-note command to Vox Notes — the exact same call VoxCommander itself makes to save
 * a note by voice ([VoxIpc.OP_CREATE] on the "notes" domain), made directly by Vision instead of
 * being routed through Commander. Guarded by Notes' own `com.voxapps.notes.permission.COMMAND`
 * signature permission (held via a `<uses-permission>` in Vision's manifest).
 */
object NoteForwarder {
    fun send(context: Context, text: String, title: String?, category: String?) {
        val command = VoxCommand(
            op = VoxIpc.OP_CREATE,
            domain = VoxIpc.DOMAIN_NOTES,
            text = text,
            title = title?.takeIf { it.isNotBlank() },
            category = category?.takeIf { it.isNotBlank() }
        )
        Logger.d(TAG, "Sending create-note command to $NOTES_PACKAGE: title=$title category=$category")
        context.sendBroadcast(
            Intent(VoxIpc.ACTION_COMMAND)
                .setPackage(NOTES_PACKAGE)
                .putExtra(VoxIpc.EXTRA_PAYLOAD, command.toJson())
        )
        val languageManager = (context.applicationContext as VisionApplication).container.languageManager
        Toast.makeText(context.applicationContext, languageManager.getString("sent_to_notes"), Toast.LENGTH_SHORT).show()
    }
}
