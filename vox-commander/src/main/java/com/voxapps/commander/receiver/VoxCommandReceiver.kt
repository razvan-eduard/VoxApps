package com.voxapps.commander.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.commander.VoxApplication
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Commander's own entry point on the Vox command bus (mirrors vox-notes'/vox-expenses'
 * VoxCommandReceiver) — lets Vox Hub discover Commander too and export its settings. Commander
 * doesn't consume [VoxIpc.OP_CREATE]/[VoxIpc.OP_READ] (it's the orchestrator that sends those to
 * satellites, not a domain-owning satellite itself), so only ping/export are handled.
 *
 * Guarded by the shared `com.voxapps.vox.permission.COMMAND` custom permission (declared once in
 * `:core:ipc`'s manifest).
 */
class VoxCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_COMMAND) return
        val command = VoxCommand.fromJson(intent.getStringExtra(VoxIpc.EXTRA_PAYLOAD)) ?: return

        val container = (context.applicationContext as VoxApplication).container

        when (command.op) {
            VoxIpc.OP_PING -> {
                setResult(Activity.RESULT_OK, VoxResult(ok = true, text = "pong").toJson(), null)
            }

            VoxIpc.OP_EXPORT -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settings = container.settingsRepository.getSettingsSnapshot()
                        val json = CommanderExportHandler.buildExportJson(settings)
                        // Must use the PendingResult's own setResultData, not the inherited
                        // BroadcastReceiver.setResult() — the latter throws "Call while result is
                        // not pending" once called from outside onReceive()'s synchronous window,
                        // which goAsync()'s whole point is to let us do.
                        pending.setResultData(VoxResult(ok = true, text = json).toJson())
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
