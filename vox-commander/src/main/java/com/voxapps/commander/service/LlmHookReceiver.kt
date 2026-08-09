package com.voxapps.commander.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest
import java.io.File

/** WorkManager `Data` (used for [androidx.work.WorkRequest.Builder.setInputData]) has a hard,
 *  non-configurable 10 KB serialized-size cap — a multi-photo scan's combined OCR text can exceed that
 *  on its own well before the rest of the [VoxLlmRequest] JSON is even counted. [Data.Builder.build]
 *  throws when it's over, uncaught here, which used to crash this receiver (and the whole process)
 *  outright the moment a request's prompt text got large enough. The payload is staged to a small
 *  cache file instead, with only its path (always tiny) going through `Data` — see
 *  [LlmHookWorker.doWork], which reads and deletes it. */
internal const val EXTRA_LLM_PAYLOAD_FILE = "llm_payload_file"

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
        val payload = intent.getStringExtra(VoxIpc.EXTRA_LLM_PAYLOAD) ?: return
        val request = VoxLlmRequest.fromJson(payload) ?: return

        Logger.log("LLM hook request from ${request.sourcePackage} [${request.task}]", "LlmHookReceiver")

        // Use a unique work name based on the task payload (which includes unique image names for scans)
        // to allow parallel execution of multiple scan requests without them being merged or dropped.
        val workName = "LLM_WORK_${request.task.hashCode()}"

        // See EXTRA_LLM_PAYLOAD_FILE's doc comment — the actual payload never goes through Data.
        val payloadFile = File(context.cacheDir, "llm_payload_${System.nanoTime()}.json")
        payloadFile.writeText(payload)

        val work = OneTimeWorkRequestBuilder<LlmHookWorker>()
            .setInputData(workDataOf(EXTRA_LLM_PAYLOAD_FILE to payloadFile.absolutePath))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(workName)
            .build()

        WorkManager.getInstance(context).enqueue(work)
    }
}
