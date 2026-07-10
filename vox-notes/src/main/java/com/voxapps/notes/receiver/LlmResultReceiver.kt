package com.voxapps.notes.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.voxapps.ipc.VoxIpc
import com.voxapps.ipc.VoxLlmResult
import com.voxapps.notes.NotesApplication
import com.voxapps.notes.domain.llm.CategoryMergeMappingParser
import com.voxapps.notes.domain.llm.LlmTasks
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
 * contract). Guarded by Notes' own `com.voxapps.notes.permission.LLM_RESULT` signature permission.
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
                    Log.w(TAG, "Category auto-merge failed: ${result.error}")
                    return
                }
                val mapping = CategoryMergeMappingParser.parse(rawJson) ?: run {
                    Log.w(TAG, "Category auto-merge: could not parse LLM mapping. rawJson=$rawJson")
                    return
                }
                Log.d(TAG, "Category auto-merge: applying mapping $mapping")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        container.notesRepository.mergeCategories(mapping)
                        Log.d(TAG, "Category auto-merge: mergeCategories() completed")
                    } finally {
                        pending.finish()
                    }
                }
            }
            // Future LLM-backed features add a branch here — zero Commander/:core:ipc changes needed.
            else -> Log.d(TAG, "Ignoring unknown LLM task: ${result.task}")
        }
    }
}
