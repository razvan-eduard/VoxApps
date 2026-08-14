package com.voxapps.textmatch.extract

/**
 * Reduces a notification to the byte-shape of the template that generated it.
 *
 * Banks and payment apps emit notifications from fixed templates: the boilerplate is constant and
 * only the variables — amounts, names, card fragments — change between messages. Two messages
 * with the same skeleton are the same sentence said again, whatever language it is said in. That
 * identity is what lets a human's judgement about one message be transcribed onto the next one
 * shaped exactly like it, with no dictionary of any language anywhere.
 *
 * Everything here is normalization, never interpretation:
 *  - digit runs collapse to a number marker, but a sign character directly before one is kept —
 *    plenty of banks use one template for both directions and the sign is the single character
 *    that differs, so swallowing it would merge two templates whose meanings are opposite;
 *  - currency symbols collapse to one marker (currency codes like RON are letters — boilerplate —
 *    and stay);
 *  - spans the caller already resolved deterministically (the vendor, the bank term) collapse to
 *    markers, which is what makes the same template match across different merchants without any
 *    learned guessing about which words are names;
 *  - whitespace collapses, case is folded.
 *
 * A merchant name that was NOT deterministically resolved stays in the skeleton verbatim. That is
 * deliberate: the skeleton then simply never matches across merchants and the caller declines —
 * unknown structure degrades to no answer, not to a wrong one.
 */
object TemplateSkeleton {

    private const val NUM = ""
    private const val CUR = ""
    private const val SPAN = ""

    private val numberRun = Regex("""\p{N}+(?:[.,:]\p{N}+)*""")
    private val currencySymbol = Regex("""\p{Sc}""")
    private val whitespace = Regex("""\s+""")

    /**
     * [resolvedSpans] are substrings the caller extracted deterministically (vendor, bank term);
     * each occurrence is collapsed to one marker before the structural passes run.
     */
    fun of(title: String?, text: String?, resolvedSpans: List<String> = emptyList()): String {
        val joined = (title?.trim().orEmpty() + "\n" + text?.trim().orEmpty())
        var s = joined
        for (span in resolvedSpans.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            s = s.replace(span, SPAN, ignoreCase = true)
        }
        // The sign survives by being consumed INTO the number marker: "+" or "-" immediately
        // before digits becomes part of what distinguishes the template.
        s = Regex("""([+\-])\s?(${numberRun.pattern})""").replace(s) { m -> m.groupValues[1] + NUM }
        s = numberRun.replace(s, NUM)
        s = currencySymbol.replace(s, CUR)
        s = whitespace.replace(s, " ")
        return s.lowercase().trim()
    }

    /** A stable identity for storage. FNV-1a over the skeleton's UTF-8 — cheap, deterministic,
     *  and collision space is tiny at the scale of "templates one phone ever sees". */
    fun hash(skeleton: String): String {
        var h = -3750763034362895579L // FNV offset basis
        for (b in skeleton.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 1099511628211L
        }
        return java.lang.Long.toHexString(h)
    }
}
