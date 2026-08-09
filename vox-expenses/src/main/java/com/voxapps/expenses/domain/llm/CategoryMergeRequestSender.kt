package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.ipc.VoxAppsDiscovery.COMMANDER_PACKAGE
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.logging.Logger


/**
 * The single reusable "fire the category-merge request" call — used identically by the manual
 * "Auto-Merge Categories" button and the scheduled WorkManager job (mirrors vox-notes'
 * CategoryMergeRequestSender). Routed through [VoxLlmRequestQueue] for durable, retryable delivery;
 * Commander replies later via [LlmResultReceiver], which — unlike vox-notes — stores the suggestion
 * for review rather than applying it (see [PendingCategoryMergeRepository]).
 */
object CategoryMergeRequestSender {

    private const val TAG = "CategoryMergeRequestSender"

    suspend fun send(context: Context, queue: VoxLlmRequestQueue, categoryNames: List<String>, languageCode: String) {
        if (categoryNames.size < 2) {
            Logger.w(TAG, "Not sending — fewer than 2 category names: $categoryNames")
            return
        }

        val promptText = CategoryMergePromptBuilder.build(categoryNames, languageCode)
        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for ${categoryNames.size} categories: $categoryNames")
        queue.enqueueAndSend(
            context = context,
            sourcePackage = context.packageName,
            task = LlmTasks.CATEGORY_DEDUPLICATION,
            promptText = promptText,
            targetPackage = COMMANDER_PACKAGE,
            data = categoryNames
        )
    }
}
