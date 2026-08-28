package com.voxapps.expenses.domain.llm

/**
 * The OCR task string for "create an expense from a picked local file (photo or PDF)":
 *
 *     EXPENSE_SCAN_CLEANUP:pending-create-file:<originalFileName>:<page1,page2,...>
 *
 * The original is the staged file exactly as the user picked it (`att_<uuid>.pdf` or an image); the
 * pages are the staged images actually sent for OCR, in request order — for an image pick they are
 * the original itself, for a PDF its rendered pages. Both rejoin as one attachment group on the
 * created record. Staged names are `att_<uuid>.<ext>`, so the `:` and `,` separators are safe.
 *
 * One object owns build and parse so the colon/comma discipline can't drift between the sender and
 * [com.voxapps.expenses.receiver.OcrResultReceiver].
 */
object PendingFileScanTask {

    const val SEGMENT = "pending-create-file"

    data class Parsed(val originalFileName: String, val pageFileNames: List<String>)

    fun build(originalFileName: String, pageFileNames: List<String>): String =
        "${LlmTasks.EXPENSE_SCAN_CLEANUP}:$SEGMENT:$originalFileName:${pageFileNames.joinToString(",")}"

    fun parse(taskParts: List<String>): Parsed? {
        if (taskParts.size != 4) return null
        if (taskParts[0] != LlmTasks.EXPENSE_SCAN_CLEANUP || taskParts[1] != SEGMENT) return null
        val original = taskParts[2].takeIf { it.isNotBlank() } ?: return null
        val pages = taskParts[3].split(",").filter { it.isNotBlank() }
        if (pages.isEmpty()) return null
        return Parsed(original, pages)
    }

    /** Every file the created expense should carry, original first — collapsed to one entry for an
     *  image pick, where the original IS the single OCR page. */
    fun linkNames(parsed: Parsed): List<String> =
        (listOf(parsed.originalFileName) + parsed.pageFileNames).distinct()
}
