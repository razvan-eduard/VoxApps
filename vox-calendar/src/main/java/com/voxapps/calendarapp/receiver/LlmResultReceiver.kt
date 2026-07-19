package com.voxapps.calendarapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.calendarapp.domain.llm.CalendarEventParseResultParser
import com.voxapps.calendarapp.domain.llm.LlmTasks
import com.voxapps.datahygiene.FieldCleaner
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

            LlmTasks.CALENDAR_SCAN_CLEANUP -> {
                val rawJson = result.rawJson
                if (result.status != VoxLlmResult.STATUS_SUCCESS || rawJson == null) {
                    Logger.w(TAG, "Calendar scan cleanup failed: ${result.error}")
                    // Unconditional — the only signal the user has that the scan didn't produce an
                    // entry, mirrors vox-notes'/vox-expenses' OCR-failure toast.
                    Toast.makeText(context, container.languageManager.getString("scan_save_failed"), Toast.LENGTH_SHORT).show()
                    return
                }
                val parsed = CalendarEventParseResultParser.parse(rawJson) ?: run {
                    // No title/date (i.e. no date reference, direct or indirect) is the same
                    // mandatory-field rule voice-created entries already enforce — a scanned
                    // document with no date reference simply can't become a calendar entry.
                    Logger.w(TAG, "Calendar scan cleanup: could not parse LLM result (no title/date?). rawJson=$rawJson")
                    Toast.makeText(context, container.languageManager.getString("scan_save_failed"), Toast.LENGTH_SHORT).show()
                    return
                }
                Logger.d(TAG, "Calendar scan cleanup: creating ${parsed.type} '${parsed.title}' layer=${parsed.layer}")
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
        // Belt-and-suspenders past the JSON-parse layer's own optCleanString guard.
        container.calendarRepository.addParsedEntry(
            type = parsed.type,
            title = FieldCleaner.cleanRequired(parsed.title, parsed.title, "title", "CalendarEntry"),
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
