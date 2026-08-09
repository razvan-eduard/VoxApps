package com.voxapps.calendarapp.domain.llm

object LlmTasks {
    const val CALENDAR_EVENT_PARSE = "CALENDAR_EVENT_PARSE"
    const val CALENDAR_SCAN_CLEANUP = "CALENDAR_SCAN_CLEANUP"

    /** Scan-a-to-do-item flow, launched directly from a specific [com.voxapps.calendarapp.data
     *  .ToDoList]'s edit face (see `ToDoListCard.kt`) — the target list is already known from UI
     *  context, so unlike [CALENDAR_SCAN_CLEANUP] this never needs list-name fuzzy-matching. Task
     *  string shape: "$TODO_SCAN_CLEANUP:$listId" (same colon-suffixed convention
     *  [CALENDAR_ATTACHMENT_CAPTURE] uses). One to-do item per scanned photo (v1). */
    const val TODO_SCAN_CLEANUP = "TODO_SCAN_CLEANUP"

    /** Live Vision capture for an "add an attachment" action (single shot, or one member of a
     *  burst) — see [com.voxapps.attachments.ui.rememberVisionCaptureLauncher]. Calendar always
     *  requests produceOCR=false for this (camera quality only, no OCR capability here), so
     *  [com.voxapps.calendarapp.receiver.OcrResultReceiver] just stages the photo and commits an
     *  AttachmentEntity row, nothing more. Task string shape:
     *  "$CALENDAR_ATTACHMENT_CAPTURE:$entryId:$groupId:$groupOrder". */
    const val CALENDAR_ATTACHMENT_CAPTURE = "CALENDAR_ATTACHMENT_CAPTURE"
}
