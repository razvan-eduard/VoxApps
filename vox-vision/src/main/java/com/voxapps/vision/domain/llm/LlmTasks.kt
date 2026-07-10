package com.voxapps.vision.domain.llm

/**
 * Task identifiers for Commander's generic LLM hook (see `:core:ipc`'s VoxLlmRequest/VoxLlmResult).
 * These strings are owned entirely by Vision — Commander never reads or validates them.
 */
object LlmTasks {
    const val OCR_CLEANUP = "OCR_CLEANUP"
}
