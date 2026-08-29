package com.voxapps.expenses.domain.llm

/**
 * Task identifiers for Commander's generic LLM hook (see `:core:ipc`'s VoxLlmRequest/VoxLlmResult).
 * These strings are owned entirely by Expenses — Commander never reads or validates them — so adding
 * a new LLM-backed feature later only requires a new constant here plus a new branch in
 * [com.voxapps.expenses.receiver.LlmResultReceiver]'s dispatch, no Commander/`:core:ipc` changes.
 */
object LlmTasks {
    /** A raw spoken utterance ("I bought 3 loaves of bread at the shop for 10 lei") -> structured fields. */
    const val EXPENSE_PARSE = "EXPENSE_PARSE"

    /** Raw OCR text from a scanned receipt (via Vox Vision) -> structured fields. */
    const val EXPENSE_SCAN_CLEANUP = "EXPENSE_SCAN_CLEANUP"

    /** Category-name list -> a duplicate/canonical mapping. Confirmation-gated for Expenses (unlike
     *  vox-notes, where the equivalent auto-applies) — see [PendingCategoryMergeRepository]. */
    const val CATEGORY_DEDUPLICATION = "CATEGORY_DEDUPLICATION"

    /** Expense list -> proposed duplicate groups. Confirmation-gated, mirrors vox-notes' dedup. */
    const val EXPENSE_DEDUPLICATION = "EXPENSE_DEDUPLICATION"

    /** A payment-app notification's title+text -> structured fields, or "not a payment" (discarded).
     *  Strictest confirmation gate of all three input channels — there's no explicit user action at
     *  all triggering this one. See [PendingNotificationExpenseRepository]. */
    const val NOTIFICATION_EXPENSE_PARSE = "NOTIFICATION_EXPENSE_PARSE"

    /** A photo attached to an ALREADY-SAVED expense (after the fact, e.g. the expense was created
     *  from a voice utterance or an auto-accepted bank notification with no receipt). Vision runs OCR
     *  against it headlessly (see [com.voxapps.ipc.VoxOcrRequest.imageUri]) exactly like a fresh scan
     *  or a stub retry, then the LLM cleanup step here is the same [EXPENSE_SCAN_CLEANUP] shape — the
     *  only difference is what happens to the reply: [com.voxapps.expenses.receiver.LlmResultReceiver]
     *  stages every field (including line items) as a [com.voxapps.expenses.data.PendingFieldSuggestion]
     *  for review-and-apply, instead of overwriting the record directly like retry does — this expense
     *  may already have been reviewed/edited by the user, unlike a never-reviewed stub. */
    const val EXPENSE_LINEITEMS_RESCAN = "EXPENSE_LINEITEMS_RESCAN"

    /** Live Vision capture for an "add an attachment" action — one launch, one reply, possibly
     *  carrying several photos at once (see [com.voxapps.ipc.VoxOcrRequest.multiShotEnabled] and
     *  [com.voxapps.attachments.ui.rememberVisionCaptureLauncher]). Never reaches Commander/
     *  LlmResultReceiver directly — [com.voxapps.expenses.receiver.OcrResultReceiver] stages every
     *  returned photo under one shared groupId (if there's more than one) and commits AttachmentEntity
     *  rows; whichever photos didn't already get OCR'd live (always true for a multi-shot reply — see
     *  that field's doc comment) get a follow-up headless OCR pass via
     *  [com.voxapps.expenses.domain.llm.ExpenseScanRequestSender.sendHeadlessRescan], reusing the same
     *  wait-and-combine mechanism a manual rescan already uses. Task string shape:
     *  "$EXPENSE_ATTACHMENT_CAPTURE:$expenseId" — always an already-saved expense; a not-yet-saved
     *  record's attachments still use the older system-camera capture path, out of scope here. */
    const val EXPENSE_ATTACHMENT_CAPTURE = "EXPENSE_ATTACHMENT_CAPTURE"

    /** A screenshot of the notification shade, OCR'd to recover the figures a redacted delivery
     *  withheld — the fallback for when the accessibility tree comes up empty (see
     *  [com.voxapps.expenses.receiver.RedactedStubRecovery]). Creates nothing: the recognised text
     *  is matched back onto the waiting stubs, which stay in review. */
    const val SHADE_OCR = "SHADE_OCR"
}
