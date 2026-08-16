package com.voxapps.docread

/**
 * Reads who issued a document, when, and under what number — the fields that have no arithmetic.
 *
 * Everything else here is accepted because it adds up. A vendor cannot be checked that way: no sum
 * confirms a company name, and none ever will. So this is deliberately the weakest thing in the
 * module, and it is kept apart from the rest for that reason — a header reading can never veto a
 * footer and rows that proved each other, and a wrong one costs a word in a field a person can see
 * and correct, not a wrong amount in a record they cannot.
 *
 * It exists because of what asking a model costs. With the model dialled out entirely, an expense
 * still needs somebody's name on it, and a letterhead states one plainly enough that a pattern per
 * language reads it. Where nothing matches, the fields stay null and the caller falls back to
 * whatever it did before — an empty vendor is a blank a person fills, which is the same bargain the
 * rest of the module makes about items.
 */
object HeaderReader {

    data class Fields(
        val vendor: String? = null,
        val invoiceNumber: String? = null,
        val date: String? = null,
        val taxId: String? = null,
        /** Which template answered, for the same reason the others report it. */
        val templateId: String? = null
    ) {
        fun isEmpty() = vendor == null && invoiceNumber == null && date == null && taxId == null
    }

    const val FIELD_VENDOR = "vendor"
    const val FIELD_INVOICE_NUMBER = "invoiceNumber"
    const val FIELD_DATE = "date"
    const val FIELD_TAX_ID = "taxId"

    /**
     * The first template that finds anything wins, and templates are offered in file order.
     *
     * Unlike the items search there is nothing to arbitrate between two readings, so the ordering in
     * the schema is the whole of the priority — which is another way of saying this should stay
     * small and specific rather than growing the way the row patterns can.
     */
    fun read(headerText: String, templates: List<CompiledHeader>): Fields {
        if (headerText.isBlank()) return Fields()
        for (template in templates) {
            val found = template.fields.mapNotNull { (field, pattern) ->
                pattern.find(headerText)
                    ?.groups?.get("value")?.value
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_FIELD_LENGTH }
                    ?.let { field to it }
            }.toMap()
            if (found.isNotEmpty()) {
                return Fields(
                    vendor = found[FIELD_VENDOR]?.let(::tidyVendor),
                    invoiceNumber = found[FIELD_INVOICE_NUMBER],
                    date = found[FIELD_DATE],
                    taxId = found[FIELD_TAX_ID],
                    templateId = template.id
                )
            }
        }
        return Fields()
    }

    /**
     * Trims what a letterhead prints beside a name but does not mean as part of it.
     *
     * A supplier line runs on — the name, then an address, a tax number, a phone — and recognition
     * offers the lot as one line. Cutting at the first of those gives the name alone far more often
     * than not, and where it does not, the result is a longer name rather than a wrong record.
     */
    private fun tidyVendor(raw: String): String {
        val cut = TRAILING_DETAIL.find(raw)?.range?.first ?: raw.length
        return raw.substring(0, cut).trim().trim(',', ';', '-', ':').ifBlank { raw.trim() }
    }

    /** Where a company's own line stops being its name: an address, a registration, a contact. */
    private val TRAILING_DETAIL = Regex(
        """\s+(?:C\.?U\.?I\.?|C\.?I\.?F\.?|R\.?O\s?\d|VAT|Tel\.?|Str\.?|Strada|Sediul|Adresa|Nr\.?\s?ord)""",
        RegexOption.IGNORE_CASE
    )

    /** A field longer than this is a paragraph the pattern ran into, not a name or a number. */
    private const val MAX_FIELD_LENGTH = 120
}
