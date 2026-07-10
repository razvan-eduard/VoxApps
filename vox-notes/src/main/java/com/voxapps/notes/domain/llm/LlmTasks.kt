package com.voxapps.notes.domain.llm

/**
 * Task identifiers for Commander's generic LLM hook (see `:core:ipc`'s VoxLlmRequest/VoxLlmResult).
 * These strings are owned entirely by Notes — Commander never reads or validates them — so adding a
 * new LLM-backed feature later only requires a new constant here plus a new branch in
 * [com.voxapps.notes.receiver.LlmResultReceiver]'s dispatch, no Commander/`:core:ipc` changes.
 */
object LlmTasks {
    const val CATEGORY_DEDUPLICATION = "CATEGORY_DEDUPLICATION"
    const val NOTE_SCAN_CLEANUP = "NOTE_SCAN_CLEANUP"
    const val NOTE_DEDUPLICATION = "NOTE_DEDUPLICATION"
}
