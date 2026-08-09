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

    /** Live Vision capture for an "add an attachment" action (single/stitch/batch — see
     *  [com.voxapps.ipc.VoxOcrRequest.captureMode]) — see
     *  [com.voxapps.attachments.ui.rememberVisionCaptureLauncher]. Notes always requests
     *  produceOCR=false for this (camera quality only, no OCR capability here), so
     *  [com.voxapps.notes.receiver.OcrResultReceiver] just stages the photo(s) and commits
     *  AttachmentEntity row(s), nothing more. Task string shape: "$NOTE_ATTACHMENT_CAPTURE:$noteId". */
    const val NOTE_ATTACHMENT_CAPTURE = "NOTE_ATTACHMENT_CAPTURE"
}
