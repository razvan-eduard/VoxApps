package com.voxapps.notes.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.voxapps.logging.Logger
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmResult
import com.voxapps.notes.NotesApplication
import com.voxapps.notes.domain.llm.CategoryMergeMappingParser
import com.voxapps.notes.domain.llm.LlmTasks
import com.voxapps.notes.domain.llm.NoteDeduplicationResultParser
import com.voxapps.notes.domain.llm.NoteScanCleanupResultParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        when (result.task) {
            LlmTasks.CATEGORY_DEDUPLICATION -> {
                val rawJson = result.rawJson
                if (result.status != VoxLlmResult.STATUS_SUCCESS || rawJson == null) {
                    Logger.w(TAG, "Category auto-merge failed: ${result.error}")
                    return
                }
                val mapping = CategoryMergeMappingParser.parse(rawJson) ?: run {
                    Logger.w(TAG, "Category auto-merge: could not parse LLM mapping. rawJson=$rawJson")
                    return
                }
                Logger.d(TAG, "Category auto-merge: applying mapping $mapping")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        container.notesRepository.mergeCategories(mapping)
                        Logger.d(TAG, "Category auto-merge: mergeCategories() completed")
                    } finally {
                        pending.finish()
                    }
                }
            }
            LlmTasks.NOTE_SCAN_CLEANUP -> {
                val rawJson = result.rawJson
                if (result.status != VoxLlmResult.STATUS_SUCCESS || rawJson == null) {
                    Logger.w(TAG, "Note scan cleanup failed: ${result.error}")
                    // Unconditional (not gated behind voiceSaveToastEnabled) — the only signal the
                    // user has that the scan didn't produce a note.
                    Toast.makeText(context, container.languageManager.getString("scan_save_failed"), Toast.LENGTH_SHORT).show()
                    return
                }
                val cleaned = NoteScanCleanupResultParser.parse(rawJson) ?: run {
                    Logger.w(TAG, "Note scan cleanup: could not parse LLM result. rawJson=$rawJson")
                    Toast.makeText(context, container.languageManager.getString("scan_save_failed"), Toast.LENGTH_SHORT).show()
                    return
                }
                Logger.d(TAG, "Note scan cleanup: creating note title=${cleaned.title} category=${cleaned.category}")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settings = container.settingsRepository.getSnapshot()
                        // Same category resolution voice notes already use: match an existing
                        // category case-insensitively, else fall back to the default / auto-create.
                        container.notesRepository.addVoiceNote(
                            title = cleaned.title,
                            text = cleaned.text,
                            spokenCategory = cleaned.category,
                            defaultCategoryId = settings.defaultVoiceCategoryId,
                            autoCreate = settings.autoCreateVoiceCategory,
                            createdAt = System.currentTimeMillis()
                        )
                    } finally {
                        pending.finish()
                    }
                }
            }

            LlmTasks.NOTE_DEDUPLICATION -> {
                val rawJson = result.rawJson
                if (result.status != VoxLlmResult.STATUS_SUCCESS || rawJson == null) {
                    Logger.w(TAG, "Note deduplication failed: ${result.error}")
                    return
                }
                val groups = NoteDeduplicationResultParser.parse(rawJson) ?: run {
                    Logger.w(TAG, "Note deduplication: could not parse LLM result. rawJson=$rawJson")
                    return
                }
                // Deliberately NOT applied here, unlike category merge — real note content needs
                // user confirmation, so the suggestion is stored for review in Settings instead.
                Logger.d(TAG, "Note deduplication: storing ${groups.size} proposed group(s) for review")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        container.noteDeduplicationRepository.setPendingGroups(groups)
                    } finally {
                        pending.finish()
                    }
                }
            }

            // Future LLM-backed features add a branch here — zero Commander/:core:ipc changes needed.
            else -> Logger.d(TAG, "Ignoring unknown LLM task: ${result.task}")
        }
    }
}
