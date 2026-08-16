package com.voxapps.expenses.domain.llm

import com.voxapps.textmatch.extract.AmountText

/**
 * Reads a document's totals with one footer template, without deciding whether the answer is right.
 *
 * Deciding is not this object's job and cannot be: a set of totals is only shown to be correct when
 * something else adds up to it — the line items, or the identity between the totals themselves. So
 * this produces a *candidate* reading per template and lets the caller run the arithmetic over the
 * combination. A template that mis-assigns every figure is not an error here; it simply loses.
 *
 * That division is what removes the rule this replaces. Reading the largest labelled amount as the
 * total is a guess that cannot fail loudly: on a form whose captions print away from their figures
 * it returns a number from the letterhead and nothing objects.
 */
object FooterReader {

    data class Candidate(
        val templateId: String,
        val grandTotal: Double?,
        val invoiceTotal: Double?,
        val previousBalance: Double?,
        val net: Double?,
        val vat: Double?
    ) {
        /** The shape the rest of the scan pipeline already speaks. */
        fun asTotals() = ReceiptTotalRegexParser.Result(
            total = grandTotal,
            invoiceTotal = invoiceTotal,
            previousBalance = previousBalance
        )

        fun isEmpty() = listOfNotNull(grandTotal, invoiceTotal, previousBalance, net, vat).isEmpty()
    }

    fun read(footerText: String, template: CompiledFooter): Candidate? = when (template.mode) {
        FooterMode.INLINE -> inline(footerText, template)
        FooterMode.STACKED -> stacked(footerText, template)
        FooterMode.ARITHMETIC -> arithmetic(footerText, template)
    }?.takeIf { !it.isEmpty() }

    /** Caption and figure share a line: the amount is read from the line the caption matched. */
    private fun inline(footerText: String, template: CompiledFooter): Candidate {
        val lines = footerText.lines()
        val found = mutableMapOf<String, Double>()
        for ((role, caption) in template.roles) {
            for (line in lines) {
                val match = caption.find(line) ?: continue
                // After the caption, so "Total 22.21" cannot be read off a figure printed before it.
                val amount = amountsIn(line.substring(match.range.last + 1)).firstOrNull()
                    ?: amountsIn(line).firstOrNull()
                if (amount != null) {
                    found.putIfAbsent(role, amount)
                    break
                }
            }
        }
        return template.candidate(found)
    }

    /**
     * Captions in one column, figures in another: the n-th caption owns the n-th figure.
     *
     * Recognition reads such a footer column by column, so the captions arrive as one run and their
     * amounts as another. Pairing by position is the only thing left that carries the association —
     * and it is exactly what reading them line by line destroys, since every caption then collects
     * whichever figure happens to follow it.
     *
     * Ordering is by where each caption and each figure appear in the text, which is the reading
     * order recognition produced. Extra figures beyond the captions are ignored rather than guessed
     * at, and a run with fewer figures than captions leaves the surplus captions unfilled.
     */
    private fun stacked(footerText: String, template: CompiledFooter): Candidate {
        val captions = template.roles.mapNotNull { (role, caption) ->
            caption.find(footerText)?.let { role to it.range.first }
        }.sortedBy { it.second }
        if (captions.isEmpty()) return template.candidate(emptyMap())

        val lastCaptionEnd = template.roles.values
            .mapNotNull { it.find(footerText)?.range?.last }
            .maxOrNull() ?: return template.candidate(emptyMap())

        // Only figures printed after the captions run: those before belong to the table above.
        val figures = amountsIn(footerText.substring(minOf(lastCaptionEnd + 1, footerText.length)))
        val found = captions.mapIndexedNotNull { index, (role, _) ->
            figures.getOrNull(index)?.let { role to it }
        }.toMap()
        return template.candidate(found)
    }

    /**
     * No captions at all: the roles come from the identity `invoice + carried = due`.
     *
     * The largest figure is what is actually due, and the unique pair adding up to it is this
     * invoice's own charges and the balance carried into it. Which of the pair is which does not
     * follow from addition, so both are offered — as the net and the invoice total — and whichever
     * the line items reconcile against is the one that was right. Nothing is claimed when more than
     * one pair adds up.
     */
    private fun arithmetic(footerText: String, template: CompiledFooter): Candidate {
        val amounts = amountsIn(footerText).distinct()
        val grand = amounts.maxOrNull() ?: return template.candidate(emptyMap())
        val parts = amounts.filter { it < grand }
        val pairs = parts.flatMapIndexed { index, a ->
            parts.drop(index + 1).filter { b -> kotlin.math.abs(a + b - grand) <= TOLERANCE }.map { b -> a to b }
        }
        val pair = pairs.singleOrNull() ?: return template.candidate(emptyMap())
        return Candidate(
            templateId = template.id,
            grandTotal = grand,
            invoiceTotal = maxOf(pair.first, pair.second),
            previousBalance = minOf(pair.first, pair.second),
            net = null,
            vat = null
        )
    }

    private fun CompiledFooter.candidate(found: Map<String, Double>) = Candidate(
        templateId = id,
        grandTotal = found[ReceiptTemplates.ROLE_GRAND_TOTAL],
        invoiceTotal = found[ReceiptTemplates.ROLE_INVOICE_TOTAL],
        previousBalance = found[ReceiptTemplates.ROLE_PREVIOUS_BALANCE],
        net = found[ReceiptTemplates.ROLE_NET],
        vat = found[ReceiptTemplates.ROLE_VAT]
    )

    private fun amountsIn(text: String): List<Double> =
        com.voxapps.textmatch.extract.AmountText.printed.findAll(text).mapNotNull { AmountText.normalize(it.value) }.filter { it > 0.0 }.toList()

    
    private const val TOLERANCE = 0.02
}
