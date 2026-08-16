package com.voxapps.docread

import com.voxapps.textmatch.extract.VocabularyClassifier

/**
 * Reads who issued a document, when, and under what number — the fields that have no arithmetic.
 *
 * Everything else here is accepted because it adds up. A vendor cannot be checked that way: no sum
 * confirms a company name, and none ever will. So this is deliberately the weakest thing in the
 * module, kept apart from the rest — a header reading can never veto a footer and rows that proved
 * each other, and a wrong one costs a word in a field a person can see and correct, not a wrong
 * amount in a record they cannot.
 *
 * Being unprovable is exactly why it cannot rely on one signal. Three routes run, strongest first:
 *
 *  - **A caption.** "Furnizor", "Supplier", "Lieferant" — read as words through the shared
 *    classifier rather than as patterns, so punctuation and case cost nothing.
 *  - **A legal form.** SRL, GmbH, Ltd, SAS: a line carrying one is a company's own line, whatever
 *    introduced it. This is what carries a real scan whose caption was mangled to "umnizor".
 *  - **A pattern**, for documents that label their fields in a shape worth matching exactly.
 *
 * **The buyer is ruled out before any of them.** An invoice names both parties, in the same shape,
 * often within a line of each other, and a reading that only knows what a seller is called takes
 * whichever came first — putting the person who paid in the field meant for who was paid, silently
 * and plausibly. Knowing the buyer's words is what turns that from a hope into a rule.
 *
 * Dates are told apart the same way: a document usually prints when it was issued and when it falls
 * due, and taking the wrong one dates the record weeks late.
 */
object HeaderReader {

    data class Fields(
        val vendor: String? = null,
        val invoiceNumber: String? = null,
        val date: String? = null,
        val taxId: String? = null,
        /** Which route answered — a caption, a legal form, or a pattern. */
        val vendorSource: String? = null,
        val templateId: String? = null
    ) {
        fun isEmpty() = vendor == null && invoiceNumber == null && date == null && taxId == null
    }

    const val FIELD_VENDOR = "vendor"
    const val FIELD_INVOICE_NUMBER = "invoiceNumber"
    const val FIELD_DATE = "date"
    const val FIELD_TAX_ID = "taxId"

    const val ROLE_SELLER = "seller"
    const val ROLE_BUYER = "buyer"
    const val ROLE_ISSUE_DATE = "issue_date"
    const val ROLE_DUE_DATE = "due_date"

    const val SOURCE_CAPTION = "caption"
    const val SOURCE_LEGAL_FORM = "legal-form"
    const val SOURCE_PATTERN = "pattern"

    /**
     * @param legalForms the designators that mark a line as a company's own — supplied by the
     *  caller, since which of them exist is a per-country list the app already maintains for
     *  classifying fields, and duplicating it here would be a copy that drifts.
     */
    fun read(
        headerText: String,
        templates: List<CompiledHeader> = emptyList(),
        captions: List<CompiledCaptions> = emptyList(),
        legalForms: List<String> = emptyList()
    ): Fields {
        if (headerText.isBlank()) return Fields()
        val lines = headerText.lines()
        val buyerLines = linesNaming(lines, captions, ROLE_BUYER)

        val fromCaption = vendorByCaption(lines, captions, buyerLines)
        val fromLegalForm = fromCaption ?: vendorByLegalForm(lines, buyerLines, legalForms, captions)
        val patterns = byPattern(headerText, templates)

        val vendor = fromCaption ?: fromLegalForm ?: patterns.second[FIELD_VENDOR]?.let(::tidyVendor)
        return Fields(
            vendor = vendor,
            invoiceNumber = patterns.second[FIELD_INVOICE_NUMBER],
            date = dateOf(lines, captions, buyerLines) ?: patterns.second[FIELD_DATE],
            taxId = patterns.second[FIELD_TAX_ID],
            vendorSource = when {
                fromCaption != null -> SOURCE_CAPTION
                fromLegalForm != null -> SOURCE_LEGAL_FORM
                vendor != null -> SOURCE_PATTERN
                else -> null
            },
            templateId = patterns.first
        )
    }

    /** Line indices introduced by one of [role]'s words, in any language offered. */
    private fun linesNaming(
        lines: List<String>,
        captions: List<CompiledCaptions>,
        role: String
    ): Set<Int> {
        val vocabularies = captions.mapNotNull { it.roles[role] }
        if (vocabularies.isEmpty()) return emptySet()
        return lines.indices.filter { index ->
            VocabularyClassifier.locate(lines[index], vocabularies).isNotEmpty()
        }.toSet()
    }

    /**
     * The text following a seller's word on its own line.
     *
     * A letterhead writes the caption and the name together far more often than not, and where it
     * does not, this yields nothing rather than reaching into a neighbouring line — which is where
     * the other party's name usually is.
     */
    private fun vendorByCaption(
        lines: List<String>,
        captions: List<CompiledCaptions>,
        buyerLines: Set<Int>
    ): String? {
        val vocabularies = captions.mapNotNull { it.roles[ROLE_SELLER] }
        if (vocabularies.isEmpty()) return null
        for ((index, line) in lines.withIndex()) {
            if (index in buyerLines) continue
            val hit = VocabularyClassifier.locate(line, vocabularies).firstOrNull() ?: continue
            val after = afterTerm(line, hit.term) ?: continue
            tidyVendor(after).takeIf { it.length >= MIN_VENDOR_LENGTH }?.let { return it }
        }
        return null
    }

    /**
     * The first line carrying a company designator, skipping the buyer's.
     *
     * First rather than best: a letterhead puts its issuer at the top, above the party being
     * billed, so position is the only ordering available and it is usually right.
     */
    private fun vendorByLegalForm(
        lines: List<String>,
        buyerLines: Set<Int>,
        legalForms: List<String>,
        captions: List<CompiledCaptions>
    ): String? {
        if (legalForms.isEmpty()) return null
        val vocabulary = listOf(VocabularyClassifier.Vocabulary("legalForm", legalForms))
        for ((index, line) in lines.withIndex()) {
            if (index in buyerLines) continue
            if (VocabularyClassifier.locate(line, vocabulary).isEmpty()) continue
            tidyVendor(withoutMangledCaption(line, captions))
                .takeIf { it.length >= MIN_VENDOR_LENGTH }
                ?.let { return it }
        }
        return null
    }

    /**
     * Drops a leading word that is a caption recognition got wrong.
     *
     * The caption route has already failed by the time this runs, which on a real scan means the
     * word introducing the supplier came through damaged — "umnizor" for "Furnizor" — and is then
     * sitting at the front of the company's own line. Matching it exactly is what already failed, so
     * it is matched by resemblance instead, with the same comparison the app resolves spoken names
     * with. Only the first word is considered, and only when something is left behind it.
     */
    private fun withoutMangledCaption(line: String, captions: List<CompiledCaptions>): String {
        val words = line.trim().split(WHITESPACE, limit = 2)
        if (words.size < 2 || words[1].isBlank()) return line
        val terms = captions.flatMap { it.roles[ROLE_SELLER]?.terms.orEmpty() }
        val looksLikeCaption = terms.any {
            com.voxapps.textmatch.FuzzyNameMatcher.namesMatch(words[0], it)
        }
        return if (looksLikeCaption) words[1] else line
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * When the document was issued, never when it falls due.
     *
     * A line introduced by a due-date word is skipped outright: taking it dates the record weeks
     * after the purchase, and does so plausibly enough that nobody notices.
     */
    private fun dateOf(
        lines: List<String>,
        captions: List<CompiledCaptions>,
        buyerLines: Set<Int>
    ): String? {
        val dueLines = linesNaming(lines, captions, ROLE_DUE_DATE)
        val issueLines = linesNaming(lines, captions, ROLE_ISSUE_DATE) - dueLines
        val ordered = issueLines.sorted() + lines.indices.filter { it !in dueLines && it !in issueLines }
        for (index in ordered) {
            if (index in buyerLines) continue
            DATE.find(lines[index])?.value?.let { return it }
        }
        return null
    }

    private fun byPattern(
        headerText: String,
        templates: List<CompiledHeader>
    ): Pair<String?, Map<String, String>> {
        for (template in templates) {
            val found = template.fields.mapNotNull { (field, pattern) ->
                pattern.find(headerText)
                    ?.groups?.get("value")?.value
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_FIELD_LENGTH }
                    ?.let { field to it }
            }.toMap()
            if (found.isNotEmpty()) return template.id to found
        }
        return null to emptyMap()
    }

    /** What follows a caption on its line, once the caption and its punctuation are removed. */
    private fun afterTerm(line: String, term: String): String? {
        val at = line.indexOf(term, ignoreCase = true)
        if (at < 0) return null
        return line.substring(at + term.length).trimStart(' ', ':', '-', '.', ',', '\t')
            .takeIf { it.isNotBlank() }
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
        return raw.substring(0, cut)
            .trim()
            .trim(',', ';', '-', ':', '.')
            .take(MAX_FIELD_LENGTH)
            .trim()
    }

    /** Where a company's own line stops being its name: an address, a registration, a contact. */
    private val TRAILING_DETAIL = Regex(
        """\s+(?:C\.?U\.?I\.?|C\.?I\.?F\.?|R\.?O\s?\d|VAT|Tel\.?|Str\.?|Strada|Sediul|Adresa|Nr\.?\s?ord|Capital|Email|E-mail|www\.)""",
        RegexOption.IGNORE_CASE
    )

    private val DATE = Regex("""\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4}|\d{4}-\d{2}-\d{2}""")

    /** Two characters is an initial, not a company. */
    private const val MIN_VENDOR_LENGTH = 3

    /** A field longer than this is a paragraph the reading ran into, not a name or a number. */
    private const val MAX_FIELD_LENGTH = 120
}
