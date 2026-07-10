package com.voxapps.notes.domain.llm

import android.content.Context
import android.content.Intent
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmRequest

/**
 * The single reusable "fire the note-deduplication request" call — used identically by the manual
 * "Find duplicate notes" button and the scheduled WorkManager job, so both trigger sources share
 * 100% of the same code path. Fire-and-forget: Commander replies later via [LlmResultReceiver], which
 * stores the suggestion for review rather than applying it (see [NoteDeduplicationRepository]).
 */
object NoteDeduplicationRequestSender {

    private const val TAG = "NoteDeduplicationRequestSender"
    private const val COMMANDER_PACKAGE = "com.voxapps.commander"

    fun send(context: Context, notes: List<NoteSummary>) {
        if (notes.size < 2) {
            Logger.w(TAG, "Not sending — fewer than 2 notes")
            return // nothing to dedup
        }

        val promptText = NoteDeduplicationPromptBuilder.build(notes)
        val payload = VoxLlmRequest(
            sourcePackage = context.packageName,
            task = LlmTasks.NOTE_DEDUPLICATION,
            promptText = promptText,
            data = notes.map { it.id.toString() }
        ).toJson()

        Logger.d(TAG, "Sending ACTION_LLM_PROCESS to $COMMANDER_PACKAGE for ${notes.size} notes")
        context.sendBroadcast(
            Intent(VoxIpc.ACTION_LLM_PROCESS)
                .setPackage(COMMANDER_PACKAGE)
                .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, payload)
        )
    }
}
