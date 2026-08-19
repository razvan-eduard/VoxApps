package com.voxapps.notes.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.ipc.VoxLlmResult
import com.voxapps.notes.NotesApplication
import com.voxapps.notes.data.preferences.NotesSettings
import com.voxapps.notes.domain.llm.CategoryMergeMappingParser
import com.voxapps.recordflow.RecordFlow
import com.voxapps.notes.domain.llm.NoteScanFlow
import com.voxapps.notes.domain.llm.LlmTasks
import com.voxapps.notes.domain.llm.NoteDeduplicationResultParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LlmResultReceiver"


/**
 * Notes' end of Commander's generic LLM hook: receives the async [VoxIpc.ACTION_LLM_RESULT] reply
 * and routes it by [VoxLlmResult.task] to the right local handler — a simple `when` dispatcher, kept
 * separate from the Room/business logic. No pending-request state: if this process was killed while
 * Commander was mid-call, the result simply arrives whenever the process is next running (or is lost
 * if Notes was uninstalled, which is fine — same fire-and-forget semantics as everywhere else in this
 * contract). Guarded by the shared `com.voxapps.vox.permission.LLM_RESULT` signature permission
 * (declared once in `:core:ipc`'s manifest).
 */
class LlmResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VoxIpc.ACTION_LLM_RESULT) return
        val result = VoxLlmResult.fromJson(intent.getStringExtra(VoxIpc.EXTRA_LLM_PAYLOAD)) ?: return
        val container = (context.applicationContext as NotesApplication).container

        // Strip the trailing requestId VoxLlmRequestQueue.enqueueAndSend appended (if this request
        // was routed through the queue at all — an un-tagged task is returned unchanged) before
        // matching against the plain LlmTasks constants below.
        val (task, requestId) = VoxLlmRequestQueue.splitRequestId(result.task)

        // Recover the base task and optional staged-image filename (format "TASK:IMAGE_NAME" for
        // NOTE_SCAN_CLEANUP; every other task's string has no colon, so baseTask == task for them —
        // no behavior change there).
        val taskParts = task.split(":")
        val baseTask = taskParts[0]
        val storedImageName = taskParts.getOrNull(1)

        when (baseTask) {
            LlmTasks.CATEGORY_DEDUPLICATION -> {
                val rawJson = result.rawJson
                val mapping = if (result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null) {
                    CategoryMergeMappingParser.parse(rawJson) ?: run {
                        Logger.w(TAG, "Category auto-merge: could not parse LLM mapping. rawJson=$rawJson")
                        null
                    }
                } else {
                    Logger.w(TAG, "Category auto-merge failed: ${result.error}")
                    null
                }
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        if (mapping != null) {
                            container.notesRepository.mergeCategories(mapping)
                            Logger.d(TAG, "Category auto-merge: mergeCategories() completed")
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            LlmTasks.NOTE_SCAN_CLEANUP -> {
                // The other half of the same flow that sent this. What arrives is only the answer —
                // the reading behind it is long gone, and does not need to be here: a reply that
                // parses carries the whole note, and one that does not is handed to a person, which
                // is what the flow's own review step already does.
                val reply = result.rawJson.takeIf { result.status == VoxLlmResult.STATUS_SUCCESS }
                if (result.status != VoxLlmResult.STATUS_SUCCESS) {
                    Logger.w(TAG, "Note scan cleanup failed: ${result.error}")
                }
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        val flow = NoteScanFlow(context, container, storedImageName)
                        val settings = container.settingsRepository.getSnapshot()
                        if (reply == null) {
                            flow.queueForReview(reading = null, parsed = null)
                        } else {
                            RecordFlow.deliver(
                                spec = flow,
                                reading = null,
                                level = NotesSettings.scanLevelOf(settings.scanLlmLevel),
                                reply = reply
                            )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            LlmTasks.NOTE_DEDUPLICATION -> {
                val rawJson = result.rawJson
                val groups = if (result.status == VoxLlmResult.STATUS_SUCCESS && rawJson != null) {
                    NoteDeduplicationResultParser.parse(rawJson) ?: run {
                        Logger.w(TAG, "Note deduplication: could not parse LLM result. rawJson=$rawJson")
                        null
                    }
                } else {
                    Logger.w(TAG, "Note deduplication failed: ${result.error}")
                    null
                }
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (requestId != null) container.pendingLlmRequestQueue.markFulfilled(requestId)
                        if (groups != null) {
                            // Deliberately NOT applied here, unlike category merge — real note
                            // content needs user confirmation, so the suggestion is stored for
                            // review in Settings instead.
                            Logger.d(TAG, "Note deduplication: storing ${groups.size} proposed group(s) for review")
                            container.noteDeduplicationRepository.setPendingGroups(groups)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            // Future LLM-backed features add a branch here — zero Commander/:core:ipc changes needed.
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
}
