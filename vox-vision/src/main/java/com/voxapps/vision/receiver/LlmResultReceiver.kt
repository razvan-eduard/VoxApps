package com.voxapps.vision.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmResult
import com.voxapps.vision.domain.NoteForwarder
import com.voxapps.vision.domain.llm.LlmTasks
import com.voxapps.vision.domain.llm.OcrCleanupResultParser

private const val TAG = "LlmResultReceiver"

/**
 * Vision's end of Commander's generic LLM hook: receives the async [VoxIpc.ACTION_LLM_RESULT] reply
 * and routes it by [VoxLlmResult.task]. Parsing + forwarding is synchronous and fast (no DB, no
 * network) — unlike Notes' equivalent receiver, no `goAsync()`/coroutine is needed here. Guarded by
 * Vision's own `com.voxapps.vision.permission.LLM_RESULT` signature permission.
 */
class LlmResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_LLM_RESULT) return
        val result = VoxLlmResult.fromJson(intent.getStringExtra(VoxIpc.EXTRA_LLM_PAYLOAD)) ?: return

        when (result.task) {
            LlmTasks.OCR_CLEANUP -> {
                val rawJson = result.rawJson
                if (result.status != VoxLlmResult.STATUS_SUCCESS || rawJson == null) {
                    Logger.w(TAG, "OCR cleanup failed: ${result.error}")
                    return
                }
                val cleaned = OcrCleanupResultParser.parse(rawJson) ?: run {
                    Logger.w(TAG, "OCR cleanup: could not parse LLM result. rawJson=$rawJson")
                    return
                }
                Logger.d(TAG, "OCR cleanup: forwarding cleaned note title=${cleaned.title}")
                NoteForwarder.send(context, text = cleaned.text, title = cleaned.title, category = null)
            }
            // Future LLM-backed features add a branch here — zero Commander/:core:ipc changes needed.
            else -> Logger.d(TAG, "Ignoring unknown LLM task: ${result.task}")
        }
    }
}
