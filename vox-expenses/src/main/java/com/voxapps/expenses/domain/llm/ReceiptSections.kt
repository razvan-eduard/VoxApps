package com.voxapps.expenses.domain.llm

/**
 * Splits a scan's text into the three regions Vision names for it — header, items, footer.
 *
 * Which region a line came from is the difference between a product and a company address that
 * happens to sit near numbers. Vision knows it from geometry and says so in the text; this reads it
 * back, so a search for line items never has to look at a letterhead and a search for totals never
 * has to consider the share capital printed in one.
 *
 * Two properties matter more than the parsing:
 *
 *  - **Markers repeat.** A stitched capture recognises each photo separately and concatenates the
 *    results, so a two-shot scan carries two of every marker. Sections are therefore *collected*
 *    across the whole text rather than cut at the first occurrence — which also repairs a real bug
 *    in the old `substringBefore` reading, where everything from the second shot onward was
 *    silently dropped before anything downstream ever saw it.
 *  - **Absence is not failure.** A document Vision could not reconstruct carries no markers at all,
 *    and every caller must keep working: [Sections.items] and [Sections.footer] then hold the whole
 *    text, exactly what those callers were reading before sections existed.
 */
object ReceiptSections {

    // Mirrored literally from vox-vision's TableReconstructor rather than shared through a module —
    // the same convention the table-section and stitch-seam markers already follow.
    const val HEADER_MARKER = "--- [header] ---"
    const val ITEMS_MARKER = "--- [items] ---"
    const val FOOTER_MARKER = "--- [footer] ---"

    /** The reconstruction's own marker, which precedes the sectioned block. */
    const val TABLE_SECTION_MARKER = TableItemsPreParse.TABLE_SECTION_MARKER

    data class Sections(
        val header: String,
        val items: String,
        val footer: String,
        /** False when the text carried no markers and the fallbacks are in force. */
        val marked: Boolean
    )

    fun split(rawText: String): Sections {
        val lines = rawText.lines()
        if (lines.none { it.trim() in MARKERS }) {
            // Unsectioned: hand every caller the whole text, which is what they read before.
            return Sections(header = rawText, items = rawText, footer = rawText, marked = false)
        }

        val header = StringBuilder()
        val items = StringBuilder()
        val footer = StringBuilder()
        // Lines before any marker belong to no section — that is the plain reading-order text the
        // reconstruction is appended to, and it is deliberately left out of all three.
        var current: StringBuilder? = null

        for (line in lines) {
            when (line.trim()) {
                HEADER_MARKER -> current = header
                ITEMS_MARKER -> current = items
                FOOTER_MARKER -> current = footer
                TABLE_SECTION_MARKER -> current = null
                else -> current?.appendLine(line)
            }
        }

        return Sections(
            header = header.toString().trimEnd(),
            items = items.toString().trimEnd(),
            footer = footer.toString().trimEnd(),
            marked = true
        )
    }

    private val MARKERS = setOf(HEADER_MARKER, ITEMS_MARKER, FOOTER_MARKER)
}

/**
 * The footer to search for totals, or [fallback] when this document has no sections.
 *
 * Totals belong to the foot of a document, and looking only there removes a whole class of
 * competitor from the largest-wins rule: a letterhead's share capital, an IBAN's digits, a phone
 * number. Unsectioned documents keep reading the text they always read, so nothing that works
 * today can stop working because a reconstruction declined.
 */
fun ReceiptSections.Sections.footerOrAll(fallback: String): String =
    if (marked && footer.isNotBlank()) footer else fallback
