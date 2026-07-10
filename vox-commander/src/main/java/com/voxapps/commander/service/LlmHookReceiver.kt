package com.voxapps.commander.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.voxapps.commander.utils.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest

/**
 * Exported entry point for the generic LLM hook: a first-party satellite (guarded by the signature-
 * level `com.voxapps.commander.permission.LLM_PROCESS` permission) can broadcast
 * [VoxIpc.ACTION_LLM_PROCESS] with a [VoxLlmRequest] JSON in [VoxIpc.EXTRA_LLM_PAYLOAD] and get a
 * [com.voxapps.ipc.VoxLlmResult] back later via [VoxIpc.ACTION_LLM_RESULT]. Only fast parse/validate
 * work happens here — the actual LLM call (which can take seconds) is delegated to a one-time
 * [LlmHookWorker] WorkManager job so this receiver never risks an ANR. Uses WorkManager rather than a
 * plain `Service` (mirrors [TtsHookReceiver]/[TtsHookService] in spirit, but a plain background
 * `Service` started here was found to be silently blocked by OEM/Doze restrictions when Commander has
 * no visible UI — WorkManager's `JobScheduler`-backed execution is exempted from that).
 */
class LlmHookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_LLM_PROCESS) return
        val payload = intent.getStringExtra(VoxIpc.EXTRA_LLM_PAYLOAD)
        val request = VoxLlmRequest.fromJson(payload) ?: return

        Logger.log("LLM hook request from ${request.sourcePackage} [${request.task}]", "LlmHookReceiver")
        val work = OneTimeWorkRequestBuilder<LlmHookWorker>()
            .setInputData(workDataOf(VoxIpc.EXTRA_LLM_PAYLOAD to payload))
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }
}
