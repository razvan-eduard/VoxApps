package com.voxapps.vision.domain.llm

import android.content.Context
import android.content.Intent
import android.util.Log
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest

/**
 * Fires the OCR-cleanup request to Commander's generic LLM hook. Fire-and-forget: Commander replies
 * later via [com.voxapps.vision.receiver.LlmResultReceiver]. Mirrors vox-notes'
 * CategoryMergeRequestSender shape.
 */
object OcrCleanupRequestSender {

    private const val TAG = "OcrCleanupRequestSender"
    private const val COMMANDER_PACKAGE = "com.voxapps.commander"

    fun send(context: Context, rawText: String, languageCode: String) {
        val promptText = OcrCleanupPromptBuilder.build(rawText, languageCode)
        val payload = VoxLlmRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.OCR_CLEANUP,
            promptText = promptText,
            data = listOf(rawText)
        ).toJson()

        Log.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for OCR cleanup")
        context.sendBroadcast(
            Intent(VoxIpc.ACTION_LLM_PROCESS)
                .setPackage(COMMANDER_PACKAGE)
                .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, payload)
        )
    }
}
