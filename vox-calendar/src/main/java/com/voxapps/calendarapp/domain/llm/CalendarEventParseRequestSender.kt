package com.voxapps.calendarapp.domain.llm

import android.content.Context
import com.voxapps.ipc.VoxAppsDiscovery.COMMANDER_PACKAGE
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.logging.Logger

private const val TAG = "CalendarEventParseRequestSender"

/**
 * Gets a spoken-entry question to Commander and no further.
 *
 * What to ask is [CalendarVoiceFlow]'s and arrives already composed; this only knows how it travels
 * — durably and retryably through [VoxLlmRequestQueue], since the reply comes back much later via
 * [com.voxapps.calendarapp.receiver.LlmResultReceiver].
 */
object CalendarEventParseRequestSender {
    suspend fun send(
        context: Context,
        queue: VoxLlmRequestQueue,
        task: String,
        promptText: String,
        rawText: String
    ) {
        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for voice-calendar-entry parsing")
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
