package com.voxapps.commander.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.voxapps.commander.utils.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest

/**
 * Exported entry point for the generic LLM hook: a first-party satellite (guarded by the shared
 * signature-level `com.voxapps.vox.permission.LLM_PROCESS` permission, declared once in `:core:ipc`'s
 * manifest) can broadcast
 * [VoxIpc.ACTION_LLM_PROCESS] with a [VoxLlmRequest] JSON in [VoxIpc.EXTRA_LLM_PAYLOAD] and get a
 * [com.voxapps.ipc.VoxLlmResult] back later via [VoxIpc.ACTION_LLM_RESULT]. Only fast parse/validate
 * work happens here — the actual LLM call (which can take seconds) is delegated to a one-time
 * [LlmHookWorker] WorkManager job so this receiver never risks an ANR. Uses an expedited
 * WorkRequest to signal high priority to OEM background managers (like Honor's "HN_USER_EXPERIENCE").
 */
class LlmHookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_LLM_PROCESS) return
        val payload = intent.getStringExtra(VoxIpc.EXTRA_LLM_PAYLOAD)
        val request = VoxLlmRequest.fromJson(payload) ?: return

        Logger.log("LLM hook request from ${request.sourcePackage} [${request.task}]", "LlmHookReceiver")
        
        // Use a unique work name based on the task payload (which includes unique image names for scans)
        // to allow parallel execution of multiple scan requests without them being merged or dropped.
        val workName = "LLM_WORK_${request.task.hashCode()}"
        
        val work = OneTimeWorkRequestBuilder<LlmHookWorker>()
            .setInputData(workDataOf(VoxIpc.EXTRA_LLM_PAYLOAD to payload))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(workName)
            .build()

        WorkManager.getInstance(context).enqueue(work)
    }
}
