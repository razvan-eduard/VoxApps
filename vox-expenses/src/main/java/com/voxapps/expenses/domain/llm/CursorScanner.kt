package com.voxapps.expenses.domain.llm

import com.voxapps.textmatch.extract.AmountText

/**
 * Reads a document's totals by walking two cursors through the whole text — one over the captions,
 * one over the figures — instead of relying on lines, columns or sections.
 *
 * Everything else here assumes the page survived recognition with some structure left: rows that are
 * rows, a footer that is a footer. That assumption fails outright on pre-printed forms, where the
 * reconstruction declines and recognition returns a stream in which a caption and the figure it
 * names can be hundreds of characters apart, in different reading order, with three values from
 * three different rows merged into one run of text. There is nothing there to parse line by line.
 *
 * What survives even then is *sequence*. Captions still appear in an order, figures still appear in
 * an order, and one of a small number of pairings between the two is the right one. So this proposes
 * several pairings and none of them is trusted: each becomes a candidate that must then be proved by
 * the same arithmetic as every other reading — the line items summing to one of its figures, or the
 * identity between the totals themselves. A pairing that invented an association loses there.
 *
 * This is deliberately not a parser. It cannot say what a document means; it can only enumerate the
 * few ways its words and numbers could correspond, cheaply enough that the arithmetic can sort them.
 */
object CursorScanner {

    /**
     * Every pairing worth testing, in the order they deserve to be tried.
     *
     * [footers] supplies the vocabulary rather than the layout: only the caption patterns are taken
     * from them, merged across all of them, because which words name a total is knowledge worth
     * sharing between templates while the layout is exactly what this does not assume.
     */
    fun candidates(text: String, footers: List<CompiledFooter>): List<FooterReader.Candidate> {
        val vocabulary = mutableMapOf<String, MutableList<Regex>>()
        for (footer in footers) {
            for ((role, caption) in footer.roles) {
                vocabulary.getOrPut(role) { mutableListOf() }.add(caption)
            }
        }
        if (vocabulary.isEmpty()) return emptyList()

        val captions = captionsIn(text, vocabulary)
        if (captions.isEmpty()) return emptyList()
        val figures = figuresIn(text)
        if (figures.isEmpty()) return emptyList()

        // A totals block is a run of captions printed together. Words that name a total also occur
        // far from it — a column header reading "Valoarea", a tax rate quoted in a letterhead — and
        // counting those as part of the block shifts every position in it. So captions are grouped
        // by how close they are, and each group is paired on its own.
        return (clusters(captions).flatMap { cluster ->
            listOfNotNull(nearestAfter(cluster, figures), positional(cluster, figures))
        } + listOfNotNull(nearestAnywhere(captions, figures)))
            .filter { !it.isEmpty() }
            .distinctBy { listOf(it.grandTotal, it.invoiceTotal, it.previousBalance, it.net, it.vat) }
    }

    /** A caption and where it sits in the text. Only the first mention of a role counts: a total is
     *  usually named once, and a second mention is a repeat in a summary rather than another total. */
    private data class Caption(val role: String, val at: Int, val endsAt: Int)

    private data class Figure(val value: Double, val at: Int)

    private fun captionsIn(text: String, vocabulary: Map<String, List<Regex>>): List<Caption> =
        vocabulary.mapNotNull { (role, patterns) ->
            patterns.mapNotNull { it.find(text) }
                .minByOrNull { it.range.first }
                ?.let { Caption(role, it.range.first, it.range.last) }
        }.sortedBy { it.at }

    private fun figuresIn(text: String): List<Figure> =
        com.voxapps.textmatch.extract.AmountText.printed.findAll(text).mapNotNull { match ->
            AmountText.normalize(match.value)?.takeIf { it > 0.0 }?.let { Figure(it, match.range.first) }
        }.toList()

    /**
     * Each caption takes the first figure printed after it.
     *
     * The ordinary case, and the one that reads a well-formed footer correctly whether or not the
     * caption and its figure ended up on the same line.
     */
    private fun nearestAfter(captions: List<Caption>, figures: List<Figure>): FooterReader.Candidate? {
        // Each figure is spent once. Two captions claiming the same number is not two totals that
        // happen to be equal, it is one number being read twice.
        var from = 0
        val found = mutableMapOf<String, Double>()
        for (caption in captions) {
            val index = figures.indexOfFirst { it.at > caption.endsAt && figures.indexOf(it) >= from }
            if (index < 0) continue
            found[caption.role] = figures[index].value
            from = index + 1
        }
        return candidate("cursor-after", found)
    }

    /** Captions printed within [MAX_CAPTION_GAP] characters of one another belong to one block. */
    private fun clusters(captions: List<Caption>): List<List<Caption>> {
        val out = mutableListOf<MutableList<Caption>>()
        for (caption in captions) {
            val last = out.lastOrNull()
            if (last != null && caption.at - last.last().endsAt <= MAX_CAPTION_GAP) {
                last += caption
            } else {
                out += mutableListOf(caption)
            }
        }
        return out
    }

    /**
     * The n-th caption takes the n-th figure printed after the last caption.
     *
     * This is the form a column of captions beside a column of figures arrives in: recognition
     * finishes the words before it starts the numbers, so no figure follows its own caption and
     * proximity means nothing — only rank does.
     */
    private fun positional(captions: List<Caption>, figures: List<Figure>): FooterReader.Candidate? {
        if (captions.size < 2) return null
        val lastCaptionEnd = captions.maxOf { it.endsAt }
        val after = figures.filter { it.at > lastCaptionEnd }
        if (after.size < captions.size) return null
        return candidate("cursor-positional", captions.mapIndexed { index, caption ->
            caption.role to after[index].value
        }.toMap())
    }

    /**
     * Each caption takes the figure closest to it in either direction.
     *
     * For pages recognised in an order that puts a figure just *before* the words naming it, which
     * happens when a totals block is read right to left.
     */
    private fun nearestAnywhere(captions: List<Caption>, figures: List<Figure>) =
        candidate("cursor-nearest", captions.mapNotNull { caption ->
            figures.minByOrNull { distance(it.at, caption) }?.let { caption.role to it.value }
        }.toMap())

    private fun distance(at: Int, caption: Caption): Int =
        if (at < caption.at) caption.at - at else at - caption.endsAt

    private fun candidate(id: String, found: Map<String, Double>) = FooterReader.Candidate(
        templateId = id,
        grandTotal = found[ReceiptTemplates.ROLE_GRAND_TOTAL],
        invoiceTotal = found[ReceiptTemplates.ROLE_INVOICE_TOTAL],
        previousBalance = found[ReceiptTemplates.ROLE_PREVIOUS_BALANCE],
        net = found[ReceiptTemplates.ROLE_NET],
        vat = found[ReceiptTemplates.ROLE_VAT]
    )

    /** Wide enough to hold a totals block read as one run of words, narrow enough to exclude a
     *  caption-like word printed elsewhere on the page. */
    private const val MAX_CAPTION_GAP = 160

    }
