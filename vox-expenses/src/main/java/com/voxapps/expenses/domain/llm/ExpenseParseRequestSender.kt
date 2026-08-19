package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.ipc.VoxAppsDiscovery.COMMANDER_PACKAGE
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.logging.Logger

private const val TAG = "ExpenseParseRequestSender"

/**
 * Gets a spoken-expense question to Commander and no further.
 *
 * What to ask is [com.voxapps.expenses.domain.llm.ExpenseVoiceFlow]'s, and arrives already composed;
 * this only knows how it travels — durably and retryably through [VoxLlmRequestQueue], since the
 * reply comes back much later via [com.voxapps.expenses.receiver.LlmResultReceiver] and a request
 * lost in between is a spoken expense nobody ever hears about again.
 */
object ExpenseParseRequestSender {
    suspend fun send(
        context: Context,
        queue: VoxLlmRequestQueue,
        task: String,
        promptText: String,
        rawText: String
    ) {
        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for voice-expense parsing")
        queue.enqueueAndSend(
            context = context,
            sourcePackage = context.packageName,
            task = task,
            promptText = promptText,
            targetPackage = COMMANDER_PACKAGE,
            data = listOf(rawText)
        )
    }
}
