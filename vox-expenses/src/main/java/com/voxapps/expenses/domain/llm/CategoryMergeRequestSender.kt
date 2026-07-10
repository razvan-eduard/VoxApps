package com.voxapps.expenses.domain.llm

import android.content.Context
import android.content.Intent
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest
import com.voxapps.logging.Logger

/**
 * The single reusable "fire the category-merge request" call — used identically by the manual
 * "Auto-Merge Categories" button and the scheduled WorkManager job (mirrors vox-notes'
 * CategoryMergeRequestSender). Fire-and-forget: Commander replies later via [LlmResultReceiver], which
 * — unlike vox-notes — stores the suggestion for review rather than applying it (see
 * [PendingCategoryMergeRepository]).
 */
object CategoryMergeRequestSender {

    private const val TAG = "CategoryMergeRequestSender"
    private const val COMMANDER_PACKAGE = "com.voxapps.commander"

    fun send(context: Context, categoryNames: List<String>, languageCode: String) {
        if (categoryNames.size < 2) {
            Logger.w(TAG, "Not sending — fewer than 2 category names: $categoryNames")
            return
        }

        val promptText = CategoryMergePromptBuilder.build(categoryNames, languageCode)
        val payload = VoxLlmRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.CATEGORY_DEDUPLICATION,
            promptText = promptText,
            data = categoryNames
        ).toJson()

        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for ${categoryNames.size} categories: $categoryNames")
        context.sendBroadcast(
            Intent(VoxIpc.ACTION_LLM_PROCESS)
                .setPackage(COMMANDER_PACKAGE)
                .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, payload)
        )
    }
}
