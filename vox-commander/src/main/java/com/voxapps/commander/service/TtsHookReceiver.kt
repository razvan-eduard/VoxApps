package com.voxapps.commander.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.commander.utils.Logger
import com.voxapps.ipc.VoxIpc

/**
 * Exported entry point for the TTS hook: any authorized app (or a satellite returning note text)
 * can broadcast [VoxIpc.ACTION_SPEAK] with the text in [VoxIpc.EXTRA_QUERY] and Commander speaks it
 * with its configured TTS. Guarded by the `com.voxapps.commander.permission.SPEAK` custom permission.
 */
class TtsHookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_SPEAK) return
        val text = intent.getStringExtra(VoxIpc.EXTRA_QUERY)?.trim().orEmpty()
        if (text.isEmpty()) return

        Logger.log("TTS hook broadcast received (${text.length} chars)", "TtsHookReceiver")
        val serviceIntent = Intent(context, TtsHookService::class.java)
            .putExtra(VoxIpc.EXTRA_QUERY, text)
        context.startService(serviceIntent)
    }
}
