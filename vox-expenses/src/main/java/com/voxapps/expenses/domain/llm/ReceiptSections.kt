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
        /**
         * The reading-order text that precedes each reconstruction — every shot's, not just the
         * first. On a well-photographed page this is where the printed rows read most cleanly, and
         * on a stitched capture there is one of these per photo.
         */
        val plain: String,
        /** False when the text carried no markers and the fallbacks are in force. */
        val marked: Boolean,
        /**
         * The items section of each photo separately, in order.
         *
         * A stitched capture photographs the same table more than once, and the two reconstructions
         * are not continuations of one another — they are competing readings of overlapping content,
         * from different distances, with different columns surviving. Run together they are neither:
         * rows disagree about how many columns exist, and a column summed across both counts some
         * rows twice and others not at all. Anything reasoning about a table's shape or its
         * arithmetic wants one photo at a time; [items] remains the concatenation for callers that
         * only want the text.
         */
        val itemBlocks: List<String>
    )

    fun split(rawText: String): Sections {
        val lines = rawText.lines()
        if (lines.none { it.trim() in MARKERS }) {
            // Unsectioned: hand every caller the whole text, which is what they read before.
            return Sections(
                header = rawText, items = rawText, footer = rawText, plain = rawText,
                marked = false, itemBlocks = listOf(rawText)
            )
        }

        val header = StringBuilder()
        val items = StringBuilder()
        val footer = StringBuilder()
        val plain = StringBuilder()
        // One per photo: a fresh block each time an items section opens.
        val itemBlocks = mutableListOf<StringBuilder>()
        // Lines before any marker are the plain reading-order text the reconstruction is appended
        // to. They are kept apart from the three sections — counting the same content twice would
        // double every amount a caller sums — but they are kept: a stitched capture carries one such
        // run per photo, and reading only the first threw away every shot after it.
        var current: StringBuilder? = plain

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed == HEADER_MARKER -> current = header
                trimmed == ITEMS_MARKER -> {
                    itemBlocks += StringBuilder()
                    current = itemBlocks.last()
                }
                trimmed == FOOTER_MARKER -> current = footer
                trimmed == TABLE_SECTION_MARKER -> current = null
                // Prefixed rather than equal: the seam carries a long sentence for the model to
                // read, which may be reworded without this having to know.
                trimmed.startsWith(STITCH_SEAM_HINT) -> current = plain
                else -> current?.appendLine(line)
            }
        }

        // Every block's text is also the items section as a whole, for callers reading it as text.
        itemBlocks.forEach { items.append(it) }
        return Sections(
            header = header.toString().trimEnd(),
            items = items.toString().trimEnd(),
            footer = footer.toString().trimEnd(),
            plain = plain.toString().trimEnd(),
            marked = true,
            itemBlocks = itemBlocks.map { it.toString().trimEnd() }.filter { it.isNotBlank() }
        )
    }

    /** A stitched capture joins its shots with this; the text after it is the next photo's plain
     *  reading order, so collection resumes there. Matched on its opening words because the marker
     *  carries a long human-readable body that OCR never touches but which may be reworded. */
    private const val STITCH_SEAM_HINT = "--- [photo stitch seam"

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
