package com.voxapps.calendarapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.calendarapp.domain.llm.CalendarEventParseResultParser
import com.voxapps.calendarapp.domain.llm.LlmTasks
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmResult
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "LlmResultReceiver"

/**
 * Vox Calendar's end of Commander's generic LLM hook: receives the async [VoxIpc.ACTION_LLM_RESULT]
 * reply and routes it by [VoxLlmResult.task] (mirrors vox-expenses' `LlmResultReceiver`). Guarded by
 * the shared `com.voxapps.vox.permission.LLM_RESULT` signature permission (declared once in
 * `:core:ipc`'s manifest).
 */
class LlmResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_LLM_RESULT) return
        val result = VoxLlmResult.fromJson(intent.getStringExtra(VoxIpc.EXTRA_LLM_PAYLOAD)) ?: return
        val container = (context.applicationContext as CalendarApplication).container

        when (result.task) {
            LlmTasks.CALENDAR_EVENT_PARSE -> {
                val rawJson = result.rawJson
                if (result.status != VoxLlmResult.STATUS_SUCCESS || rawJson == null) {
                    Logger.w(TAG, "Calendar event parse failed: ${result.error}")
                    return
                }
                val parsed = CalendarEventParseResultParser.parse(rawJson) ?: run {
                    Logger.w(TAG, "Calendar event parse: could not parse LLM result (no title/date?). rawJson=$rawJson")
                    return
                }
                Logger.d(TAG, "Calendar event parse: creating ${parsed.type} '${parsed.title}' layer=${parsed.layer}")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        createEntryFromParsed(container, parsed)
                    } finally {
                        pending.finish()
                    }
                }
            }

            else -> Logger.d(TAG, "Ignoring unknown LLM task: ${result.task}")
        }
    }

    private suspend fun createEntryFromParsed(
        container: CalendarContainer,
        parsed: CalendarEventParseResultParser.Parsed
    ) {
        val settings = container.settingsRepository.getSnapshot()
        container.calendarRepository.addParsedEntry(
            type = parsed.type,
            title = parsed.title,
            description = null,
            location = null,
            startMillis = parsed.startMillis,
            endMillis = parsed.endMillis,
            allDay = parsed.allDay,
            tags = parsed.tags,
            spokenLayer = parsed.layer,
            defaultLayerId = settings.defaultLayerId,
            autoCreateLayer = settings.autoCreateLayer
        )
    }
}
