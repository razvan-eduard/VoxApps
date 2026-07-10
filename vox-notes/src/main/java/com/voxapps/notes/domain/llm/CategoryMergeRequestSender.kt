package com.voxapps.notes.domain.llm

import android.content.Context
import android.content.Intent
import android.util.Log
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest

/**
 * The single reusable "fire the category-merge request" call — used identically by the manual
 * "Auto-Merge Categories" button and the scheduled WorkManager job, so both trigger sources share
 * 100% of the same code path. Fire-and-forget: Commander replies later via [LlmResultReceiver].
 */
object CategoryMergeRequestSender {

    private const val TAG = "CategoryMergeRequestSender"
    private const val COMMANDER_PACKAGE = "com.voxapps.commander"

    fun send(context: Context, categoryNames: List<String>, languageCode: String) {
        if (categoryNames.size < 2) {
            Log.w(TAG, "Not sending — fewer than 2 category names: $categoryNames")
            return // nothing to merge
        }

        val promptText = CategoryMergePromptBuilder.build(categoryNames, languageCode)
        val payload = VoxLlmRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.CATEGORY_DEDUPLICATION,
            promptText = promptText,
            data = categoryNames
        ).toJson()

        Log.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for ${categoryNames.size} categories: $categoryNames")
        context.sendBroadcast(
            Intent(VoxIpc.ACTION_LLM_PROCESS)
                .setPackage(COMMANDER_PACKAGE)
                .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, payload)
        )
    }
}
