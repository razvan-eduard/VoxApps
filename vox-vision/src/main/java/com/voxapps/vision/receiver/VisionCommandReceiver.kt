package com.voxapps.vision.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.ipc.VoxCommand
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxResult

/**
 * Discovery handshake only for now — Vision doesn't consume commands yet (it's an OCR producer,
 * dispatched via [com.voxapps.vision.domain.ScanTargetDiscovery] and
 * [com.voxapps.vision.domain.OcrResultSender], not a consumer of voice commands). Commander's "Vox
 * Apps" scan pings this to confirm Vision is installed and responding. Guarded by the shared
 * `com.voxapps.vox.permission.COMMAND` custom permission (declared once in `:core:ipc`'s manifest).
 */
class VisionCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_COMMAND) return
        val command = VoxCommand.fromJson(intent.getStringExtra(VoxIpc.EXTRA_PAYLOAD)) ?: return

        if (command.op == VoxIpc.OP_PING) {
            setResult(Activity.RESULT_OK, VoxResult(ok = true, text = "pong").toJson(), null)
        }
    }
}
