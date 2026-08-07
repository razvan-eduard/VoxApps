package com.voxapps.calendarapp.domain.llm

import android.content.Context
import com.voxapps.ipc.VoxAppsDiscovery.COMMANDER_PACKAGE
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.logging.Logger

private const val TAG = "CalendarEventParseRequestSender"

/**
 * Fires the generic-LLM-hook request that turns a raw spoken/typed utterance into a structured
 * calendar entry (see [CalendarEventParsePromptBuilder]). Routed through [VoxLlmRequestQueue] for
 * durable, retryable delivery; the async reply arrives later via
 * [com.voxapps.calendarapp.receiver.LlmResultReceiver]. Mirrors vox-expenses' `ExpenseParseRequestSender`.
 */
object CalendarEventParseRequestSender {
    suspend fun send(
        context: Context,
        queue: VoxLlmRequestQueue,
        rawText: String,
        existingLayers: List<String>,
        existingTodoLists: List<String>,
        languageCode: String
    ) {
        val promptText = CalendarEventParsePromptBuilder.build(rawText, existingLayers, existingTodoLists, languageCode)
        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for voice-calendar-entry parsing")
        queue.enqueueAndSend(
            context = context,
            sourcePackage = context.packageName,
            task = LlmTasks.CALENDAR_EVENT_PARSE,
            promptText = promptText,
            targetPackage = COMMANDER_PACKAGE,
            data = listOf(rawText)
        )
    }
}
