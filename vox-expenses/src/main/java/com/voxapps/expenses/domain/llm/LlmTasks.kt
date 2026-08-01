package com.voxapps.expenses.domain.llm

/**
 * Task identifiers for Commander's generic LLM hook (see `:core:ipc`'s VoxLlmRequest/VoxLlmResult).
 * These strings are owned entirely by Expenses — Commander never reads or validates them — so adding
 * a new LLM-backed feature later only requires a new constant here plus a new branch in
 * [com.voxapps.expenses.receiver.LlmResultReceiver]'s dispatch, no Commander/`:core:ipc` changes.
 */
object LlmTasks {
    /** A raw spoken utterance ("am cumpărat 3 pâini de la magazin cu 10 lei") -> structured fields. */
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
     *  from a voice utterance or an auto-accepted bank notification with no receipt) -> line items
     *  only. Image-only: unlike [EXPENSE_SCAN_CLEANUP], there's no OCR text — the photo was never run
     *  through Vision's camera+OCR activity, so this relies entirely on a multimodal LLM reading the
     *  attached image. The reply updates ONLY the target expense's line items, nothing else. */
    const val EXPENSE_LINEITEMS_RESCAN = "EXPENSE_LINEITEMS_RESCAN"
}
