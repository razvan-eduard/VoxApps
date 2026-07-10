package com.voxapps.notes.domain.llm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voxapps.notes.NotesApplication
import kotlinx.coroutines.flow.first

/**
 * Scheduled counterpart of the manual "Find duplicate notes" button — gathers the current notes and
 * fires the exact same [NoteDeduplicationRequestSender] call. The async LLM reply is handled
 * independently by [com.voxapps.notes.receiver.LlmResultReceiver] whenever it lands, storing the
 * suggestion for review (not applying it), so this worker's job is done as soon as the request is sent.
 */
class NoteDeduplicationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as NotesApplication).container
        val notes = container.notesRepository.notes.first().map { NoteSummary(it.id, it.title, it.text) }
        NoteDeduplicationRequestSender.send(applicationContext, notes)
        return Result.success()
    }
}
