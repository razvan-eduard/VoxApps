package com.voxapps.calendarapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.voxapps.calendarapp.CalendarApplication
import com.voxapps.calendarapp.data.preferences.CalendarSettings
import com.voxapps.calendarapp.domain.llm.CalendarVoiceFlow
import com.voxapps.calendarapp.di.CalendarContainer
import com.voxapps.calendarapp.domain.llm.CalendarEventParseResultParser
import com.voxapps.recordflow.RecordFlow
import com.voxapps.calendarapp.domain.llm.CalendarScanFlow
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
                val succeeded = result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null
                if (!succeeded) Logger.w(TAG, "Calendar event parse failed: ${result.error}")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // The utterance this reply is about, from whichever side still has it: the
                        // reply itself when Commander composed the request from a cached template,
                        // and this app's own queue when it composed the request. Read before
                        // markFulfilled, which deletes the row that holds it.
                        val spokenInput = result.input?.takeIf { it.isNotBlank() }
                            ?: container.pendingLlmRequestQueue.originalInput(requestId)
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        if (succeeded) {
                            // Through the flow, same as a scanned page: reading the answer and
                            // deciding what becomes of it is the flow's, and marking the request
                            // handled is this receiver's.
                            val spec = CalendarVoiceFlow(container)
                            RecordFlow.deliver(
                                spec = spec,
                                // Re-read rather than carried: what a rule settles from a sentence is
                                // the same on both sides of the round trip, so the sentence is what
                                // has to survive it. Here that settles nothing — see the flow.
                                reading = spokenInput?.let { spec.read(it) },
                                level = CalendarSettings.VOICE_FLOW_SUPPORT.default,
                                reply = rawJson!!
                            )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            task == LlmTasks.CALENDAR_SCAN_CLEANUP -> {
                // The other half of the same flow that sent this. The reading behind it is gone, and
                // is not needed: a reply that parses carries the whole entry, and one that does not
                // is handed back to the person, which is what the flow's own review step does.
                val reply = result.rawJson.takeIf { result.status == VoxLlmResult.STATUS_SUCCESS }
                if (result.status != VoxLlmResult.STATUS_SUCCESS) {
                    Logger.w(TAG, "Calendar scan cleanup failed: ${result.error}")
                }
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        val flow = CalendarScanFlow(context, container)
                        if (reply == null) {
                            flow.queueForReview(reading = null, parsed = null)
                        } else {
                            RecordFlow.deliver(
                                spec = flow,
                                reading = null,
                                level = CalendarSettings.scanLevelOf(
                                    container.settingsRepository.getSnapshot().scanLlmLevel
                                ),
                                reply = reply
                            )
                        }
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
    /** Also the write point for [com.voxapps.calendarapp.domain.llm.CalendarScanFlow], so a scanned
     *  entry is created the one way whether or not a model answered — same arrangement expenses uses
     *  for its own createExpenseFromParsed. */
    internal suspend fun routeParsed(container: CalendarContainer, parsed: CalendarEventParseResultParser.Parsed) {
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
