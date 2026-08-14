package com.voxapps.calendarapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.calendarapp.domain.llm.CalendarEventParseResultParser
import com.voxapps.calendarapp.domain.llm.LlmTasks
import com.voxapps.calendarapp.domain.llm.ParsedKind
import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.ipc.VoxLlmResult
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        // Strip the trailing requestId VoxLlmRequestQueue.enqueueAndSend appended (if this request
        // was routed through the queue at all — an un-tagged task is returned unchanged) before
        // matching against the plain LlmTasks constants below.
        val (task, requestId) = VoxLlmRequestQueue.splitRequestId(result.task)

        when {
            task.startsWith("${LlmTasks.TODO_SCAN_CLEANUP}:") -> {
                // The target list is already known (baked into the task string by the scan button in
                // ToDoListCard.kt), so this bypasses CalendarEventParseResultParser.Parsed.listName
                // fuzzy-matching entirely and calls ToDoRepository's primitives directly — same
                // precedent LlmTasks.CALENDAR_ATTACHMENT_CAPTURE already sets for direct-primitive
                // handling in this receiver rather than always going through a "parsed" wrapper.
                val listId = task.substringAfter(":").toLongOrNull()
                val rawJson = result.rawJson
                val parsed = if (listId != null && result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null) {
                    CalendarEventParseResultParser.parse(rawJson)
                } else null
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        if (listId != null && parsed != null) {
                            val itemId = container.toDoRepository.addItem(listId, parsed.title)
                            val dueMillis = parsed.startMillis
                            if (dueMillis != null) {
                                val list = container.toDoRepository.lists.first().firstOrNull { it.id == listId }
                                val item = container.toDoRepository.itemsForList(listId).first().firstOrNull { it.id == itemId }
                                if (list != null && item != null) container.toDoRepository.setItemDueDate(item, dueMillis, list)
                            }
                        } else {
                            Logger.w(TAG, "Todo scan cleanup: could not parse LLM result. rawJson=$rawJson")
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            task == LlmTasks.CALENDAR_EVENT_PARSE -> {
                val rawJson = result.rawJson
                val parsed = if (result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null) {
                    CalendarEventParseResultParser.parse(rawJson) ?: run {
                        Logger.w(TAG, "Calendar event parse: could not parse LLM result (missing title, or a date was required but missing). rawJson=$rawJson")
                        null
                    }
                } else {
                    Logger.w(TAG, "Calendar event parse failed: ${result.error}")
                    null
                }
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        if (parsed != null) {
                            Logger.d(TAG, "Calendar event parse: creating ${parsed.kind} '${parsed.title}' layer=${parsed.layer} list=${parsed.listName}")
                            routeParsed(container, parsed)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            task == LlmTasks.CALENDAR_SCAN_CLEANUP -> {
                val rawJson = result.rawJson
                val parsed = if (result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null) {
                    CalendarEventParseResultParser.parse(rawJson) ?: run {
                        // Missing title, or a date required for a non-TODO kind but not found — same
                        // mandatory-field rule voice-created records already enforce (a TODO-kind
                        // result never fails on a missing date).
                        Logger.w(TAG, "Calendar scan cleanup: could not parse LLM result (missing title, or a date was required but missing). rawJson=$rawJson")
                        null
                    }
                } else {
                    Logger.w(TAG, "Calendar scan cleanup failed: ${result.error}")
                    null
                }
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        if (parsed == null) {
                            // Unconditional — the only signal the user has that the scan didn't
                            // produce a record, mirrors vox-notes'/vox-expenses' OCR-failure toast.
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, container.languageManager.getString("scan_save_failed"), Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                        Logger.d(TAG, "Calendar scan cleanup: creating ${parsed.kind} '${parsed.title}' layer=${parsed.layer} list=${parsed.listName}")
                        routeParsed(container, parsed)
                    } finally {
                        pending.finish()
                    }
                }
            }

            else -> {
                Logger.d(TAG, "Ignoring unknown LLM task: ${result.task}")
                // Still a definitive reply even though this task type isn't recognized — clear its
                // queue row so it isn't retried forever for no reason.
                if (requestId != null) {
                    val pending = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            container.pendingLlmRequestQueue.markFulfilled(requestId)
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }
    }

    /** Dispatches on [CalendarEventParseResultParser.Parsed.kind]: EVENT/TASK go to the plain
     *  calendar-entry path (unchanged); TODO goes to the to-do item path instead — see
     *  [ParsedKind]'s doc comment for why this is a routing decision, not a stored field value. */
    private suspend fun routeParsed(container: CalendarContainer, parsed: CalendarEventParseResultParser.Parsed) {
        when (parsed.kind) {
            ParsedKind.TODO -> createTodoItemFromParsed(container, parsed)
            ParsedKind.EVENT, ParsedKind.TASK -> createEntryFromParsed(container, parsed)
        }
    }

    private suspend fun createEntryFromParsed(
        container: CalendarContainer,
        parsed: CalendarEventParseResultParser.Parsed
    ) {
        val settings = container.settingsRepository.getSnapshot()
        // Learned spelling corrections apply only on this LLM-capture path — subscribed-calendar
        // sync and manual entry are not garble sources. Exact tier only: this app has no
        // suggestion surface for the fuzzy tier to speak through.
        val corrections =
            if (settings.fieldCorrectionMemoryEnabled) container.fieldCorrectionMemory.activeCorrections(settings.fieldCorrectionThreshold)
            else emptyMap()
        // Belt-and-suspenders past the JSON-parse layer's own optCleanString guard.
        container.calendarRepository.addParsedEntry(
            type = parsed.calendarType,
            title = com.voxapps.textmatch.extract.FieldCorrections.apply(
                FieldCleaner.cleanRequired(parsed.title, parsed.title, "title", "CalendarEntry"), corrections
            ) ?: parsed.title,
            description = null,
            location = null,
            // Non-null enforced by CalendarEventParseResultParser.parse() for EVENT/TASK kinds.
            startMillis = parsed.startMillis!!,
            endMillis = parsed.endMillis,
            allDay = parsed.allDay,
            tags = parsed.tags,
            spokenLayer = parsed.layer,
            defaultLayerId = settings.defaultLayerId,
            autoCreateLayer = settings.autoCreateLayer
        )
    }

    private suspend fun createTodoItemFromParsed(
        container: CalendarContainer,
        parsed: CalendarEventParseResultParser.Parsed
    ) {
        val settings = container.settingsRepository.getSnapshot()
        val corrections =
            if (settings.fieldCorrectionMemoryEnabled) container.fieldCorrectionMemory.activeCorrections(settings.fieldCorrectionThreshold)
            else emptyMap()
        container.toDoRepository.addParsedItem(
            spokenListName = parsed.listName,
            text = com.voxapps.textmatch.extract.FieldCorrections.apply(
                FieldCleaner.cleanRequired(parsed.title, parsed.title, "text", "ToDoItem"), corrections
            ) ?: parsed.title,
            dueMillis = parsed.startMillis,
            defaultLayerId = settings.defaultLayerId
        )
    }
}
