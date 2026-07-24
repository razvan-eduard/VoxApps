package com.voxapps.notes.domain.llm

import android.content.Context
import com.voxapps.ipc.VoxLlmRequestQueue
import com.voxapps.logging.Logger

private const val COMMANDER_PACKAGE = "com.voxapps.commander"

/**
 * The single reusable "fire the note-deduplication request" call — used identically by the manual
 * "Find duplicate notes" button and the scheduled WorkManager job, so both trigger sources share
 * 100% of the same code path. Routed through [VoxLlmRequestQueue] for durable, retryable delivery;
 * Commander replies later via [LlmResultReceiver], which stores the suggestion for review rather
 * than applying it (see [NoteDeduplicationRepository]).
 */
object NoteDeduplicationRequestSender {

    private const val TAG = "NoteDeduplicationRequestSender"

    suspend fun send(context: Context, queue: VoxLlmRequestQueue, notes: List<NoteSummary>) {
        if (notes.size < 2) {
            Logger.w(TAG, "Not sending — fewer than 2 notes")
            return // nothing to dedup
        }

        val promptText = NoteDeduplicationPromptBuilder.build(notes)
        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for ${notes.size} notes")
        queue.enqueueAndSend(
            context = context,
            sourcePackage = context.packageName,
            task = LlmTasks.NOTE_DEDUPLICATION,
            promptText = promptText,
            targetPackage = COMMANDER_PACKAGE,
            data = notes.map { it.id.toString() }
        )
    }
}
