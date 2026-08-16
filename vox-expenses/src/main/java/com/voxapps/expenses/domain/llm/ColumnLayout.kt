package com.voxapps.expenses.domain.llm

import com.voxapps.textmatch.extract.VocabularyClassifier

/**
 * What a table's own heading row says about its columns: how many there are, and what each holds.
 *
 * This is the difference between reading a table and guessing at one. Without it, a row of four
 * numbers has to be interpreted by assumption — that the last three are quantity, unit price and
 * value, in that order — and a document printing them in any other order is misread *silently*,
 * because a wrong assignment can still add up to the right total. A heading row settles it by
 * saying so.
 *
 * Two things it gives that nothing else can. **How many columns carry figures**, which turns a run
 * of numbers into rows even when the lines that held them did not survive: cut the run into groups
 * of that size and each group is a row. And **which figure is which**, so quantity times price can
 * actually be checked rather than assumed.
 *
 * The order is proposed, never asserted. A heading row that wrapped onto two printed lines arrives
 * with its later columns read first — a real invoice put "Valoarea" before "Denumirea" for exactly
 * that reason — so what is detected here becomes another candidate for the arithmetic to accept or
 * discard, like every other reading.
 */
data class ColumnLayout(
    /** Column roles in the order the headings were found, first to last. */
    val roles: List<Role>,
    /** Which template recognised the headings. */
    val templateId: String,
    /** True when the form printed the relation between its own numbered columns, which fixes the
     *  roles outright instead of leaving them to the order the headings were read in. */
    val fromPrintedRelation: Boolean = false
) {
    enum class Role { NR, DESC, UM, QTY, UNIT, VALUE, VAT }

    /** The columns that hold figures, in order — what a run of numbers has to be cut into. */
    val numericRoles: List<Role> get() = roles.filter { it in NUMERIC }

    fun indexOf(role: Role): Int = numericRoles.indexOf(role)

    companion object {
        val NUMERIC = setOf(Role.QTY, Role.UNIT, Role.VALUE, Role.VAT)

        /** Vocabulary names as the schema spells them, and the older spellings alongside, so a
         *  repository ahead of an install and one behind it both read. */
        fun roleOf(name: String): Role? = when (name.lowercase()) {
            "index", "nr" -> Role.NR
            "product", "desc" -> Role.DESC
            "um" -> Role.UM
            "qty" -> Role.QTY
            "price", "unit" -> Role.UNIT
            "value" -> Role.VALUE
            "tax", "vat" -> Role.VAT
            else -> null
        }
    }
}

/**
 * Finds a table's column layout in the text above its rows.
 *
 * Two routes, and the stronger one first. Some pre-printed forms number their columns and print the
 * arithmetic between them — `5 (3x4)`, meaning the fifth column is the third times the fourth —
 * which states the roles outright and survives recognition well, being short. Failing that, the
 * headings are located by name and ordered by where they were found.
 */
object ColumnHeaderDetector {

    fun detect(headerText: String, templates: List<CompiledColumns>): List<ColumnLayout> =
        // The heading row is the last thing printed before the rows, and looking there first is what
        // keeps a word from elsewhere out of the reading: a rate quoted in a letterhead — "Cota TVA:
        // 21%" — carries the same word as a tax column heading, and taken as one it displaces every
        // column after it. The whole region is read too, for documents whose heading row did not
        // survive as its own lines; both are candidates and the arithmetic decides between them.
        listOf(headingRow(headerText), headerText)
            .filter { it.isNotBlank() }
            .flatMap { text ->
                templates.mapNotNull { template ->
                    fromRelation(text, template) ?: fromHeadings(text, template)
                }
            }
            .distinctBy { it.roles }

    /** The last printed lines of a region — enough for a heading row that wrapped, not enough to
     *  reach back into the letterhead above it. */
    private fun headingRow(headerText: String, lines: Int = 2): String =
        headerText.lines().filter { it.isNotBlank() }.takeLast(lines).joinToString("\n")

    /**
     * `5 (3x4)` names three columns by number: which one is the value, and which two multiply to it.
     * The columns between and around them follow from their numbering — a table numbering its value
     * column five has four columns before it — so the whole layout is recovered from three digits.
     */
    private fun fromRelation(headerText: String, template: CompiledColumns): ColumnLayout? {
        val relation = template.relation ?: return null
        val match = relation.find(headerText) ?: return null
        val value = match.groups["value"]?.value?.toIntOrNull() ?: return null
        val qty = match.groups["qty"]?.value?.toIntOrNull() ?: return null
        val unit = match.groups["unit"]?.value?.toIntOrNull() ?: return null
        if (value <= 0 || qty <= 0 || unit <= 0) return null
        // Numbered from one, and a value column is preceded by everything that produces it.
        val byIndex = sortedMapOf<Int, ColumnLayout.Role>()
        byIndex[qty] = ColumnLayout.Role.QTY
        byIndex[unit] = ColumnLayout.Role.UNIT
        byIndex[value] = ColumnLayout.Role.VALUE
        // A tax column, where the headings name one, sits after the value it is charged on.
        val namesTax = template.headings
            .filterKeys { ColumnLayout.roleOf(it) == ColumnLayout.Role.VAT }
            .values
            .any { VocabularyClassifier.locate(headerText, listOf(it)).isNotEmpty() }
        if (namesTax) byIndex[value + 1] = ColumnLayout.Role.VAT
        val roles = (1..byIndex.lastKey()).map { index ->
            byIndex[index] ?: if (index == 1) ColumnLayout.Role.NR else ColumnLayout.Role.DESC
        }
        return ColumnLayout(roles, template.id, fromPrintedRelation = true)
    }

    /**
     * Headings located by name, ordered by where each was found.
     *
     * The tax heading is matched before the value heading and its match removed, since one contains
     * the other — "Valoarea TVA" would otherwise be found twice, once as a value column.
     */
    private fun fromHeadings(headerText: String, template: CompiledColumns): ColumnLayout? {
        // One heading, one column. Where two roles match at the same place the fuller reading is
        // what is printed there — "Valoare TVA" is a tax column and not also a column of values,
        // "Unit Price" is a price and not also a unit of measure — so the longer term takes it.
        val found = VocabularyClassifier.locate(headerText, template.headings.values.toList())
            .groupBy { it.tokenIndex }
            .toSortedMap()
            .values
            .mapNotNull { atSamePlace -> atSamePlace.maxByOrNull { it.tokenCount } }
            .mapNotNull { ColumnLayout.roleOf(it.vocabulary) }
            .distinct()

        if (found.count { it in ColumnLayout.NUMERIC } < MIN_NUMERIC_COLUMNS) return null
        return ColumnLayout(unwrapped(found), template.id)
    }

    /**
     * Puts back the order a heading row loses when it is printed over two lines.
     *
     * A heading too wide for its column wraps, and the wrapped part is printed *above* the rest —
     * so recognition reads the last columns first and the detected order opens with them. The
     * signature is unmistakable and needs no knowledge of any particular document: no table puts an
     * amount column before the column naming what the amount is for. Where that is what was found,
     * the leading run of figure columns is the wrapped tail, and belongs at the end.
     *
     * A heading row that did not wrap has no such run and passes through untouched.
     */
    private fun unwrapped(roles: List<ColumnLayout.Role>): List<ColumnLayout.Role> {
        val description = roles.indexOf(ColumnLayout.Role.DESC)
        if (description <= 0) return roles
        val head = roles.take(description)
        // The wrapped part is what was read *first*, so it is the run at the very start — not
        // whatever happens to sit next to the description, since a column number can survive on the
        // unwrapped line and land between the two.
        val leadingFigures = head.takeWhile { it in ColumnLayout.NUMERIC }
        if (leadingFigures.isEmpty()) return roles
        return head.drop(leadingFigures.size) + roles.drop(description) + leadingFigures
    }

    /** Below this there is no table worth reading by columns. */
    private const val MIN_NUMERIC_COLUMNS = 2
}
