package com.voxapps.calendarapp.domain.llm

import android.content.Context
import android.content.Intent
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest
import com.voxapps.logging.Logger

private const val TAG = "CalendarEventParseRequestSender"
private const val COMMANDER_PACKAGE = "com.voxapps.commander"

/**
 * Fires the generic-LLM-hook request that turns a raw spoken/typed utterance into a structured
 * calendar entry (see [CalendarEventParsePromptBuilder]). Fire-and-forget; the async reply arrives
 * later via [com.voxapps.calendarapp.receiver.LlmResultReceiver]. Mirrors vox-expenses'
 * `ExpenseParseRequestSender`.
 */
object CalendarEventParseRequestSender {
    fun send(context: Context, rawText: String, existingLayers: List<String>, languageCode: String) {
        val promptText = CalendarEventParsePromptBuilder.build(rawText, existingLayers, languageCode)
        val payload = VoxLlmRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.CALENDAR_EVENT_PARSE,
            promptText = promptText,
            data = listOf(rawText)
        ).toJson()

        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for voice-calendar-entry parsing")
        context.sendBroadcast(
            Intent(VoxIpc.ACTION_LLM_PROCESS)
                .setPackage(COMMANDER_PACKAGE)
                .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, payload)
        )
    }
}
