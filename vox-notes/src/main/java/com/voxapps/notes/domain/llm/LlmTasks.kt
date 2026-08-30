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

    /** A spoken note sent for the same cleanup a scan gets (title/category/tidied text) — its own
     *  task rather than [NOTE_SCAN_CLEANUP] because the two reply paths part ways on failure: an
     *  unreadable voice reply commits the raw transcript, a scan one manages its retained photo. */
    const val NOTE_PARSE = "NOTE_PARSE"
    const val NOTE_DEDUPLICATION = "NOTE_DEDUPLICATION"

    /** Live Vision capture for an "add an attachment" action (single/stitch/batch — see
     *  [com.voxapps.ipc.VoxOcrRequest.captureMode]) — see
     *  [com.voxapps.attachments.ui.rememberVisionCaptureLauncher]. Notes always requests
     *  produceOCR=false for this (camera quality only, no OCR capability here), so
     *  [com.voxapps.notes.receiver.OcrResultReceiver] just stages the photo(s) and commits
     *  AttachmentEntity row(s), nothing more. Task string shape: "$NOTE_ATTACHMENT_CAPTURE:$noteId". */
    const val NOTE_ATTACHMENT_CAPTURE = "NOTE_ATTACHMENT_CAPTURE"
}
