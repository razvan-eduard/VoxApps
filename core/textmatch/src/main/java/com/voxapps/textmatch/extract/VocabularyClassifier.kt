package com.voxapps.textmatch.extract

/**
 * Reports which of the caller's named vocabularies a piece of text draws on.
 *
 * The vocabularies are supplied, never held here: "these tokens mean a company", "these name a
 * bank" is data that grows per country and per language, and belongs in something a caller can
 * update without a release. This object owns only the matching — how a term is recognised inside
 * arbitrary text — which is the part that must behave identically for every caller.
 *
 * Matching is case-insensitive and punctuation-insensitive, so a single term covers the ways a
 * designator is actually written: "PFA", "P.F.A.", "p.f.a" are one term, as are "SRL" and "S.R.L.".
 * Terms match on token boundaries rather than as bare substrings, so a short designator cannot fire
 * from inside an unrelated word.
 */
object VocabularyClassifier {

    /**
     * A named list of terms. [name] is the caller's own label for it and carries no meaning here
     * beyond identifying which list matched.
     */
    data class Vocabulary(val name: String, val terms: Collection<String>)

    /** Everything that is not a letter or a digit separates tokens — which is what makes "P.F.A."
     *  and "PFA" the same term without enumerating the variants. */
    private val separators = Regex("""[^\p{L}\p{N}]+""")

    /**
     * Every match found in [text], in vocabulary order then document order. A text that draws on
     * two vocabularies reports both — deciding what that means is the caller's, and for a caller
     * ranking one list above another the ordering of [vocabularies] is the only thing needed.
     */
    fun classify(text: String, vocabularies: List<Vocabulary>, lineIndex: Int = 0): List<VocabularyFinding> {
        if (text.isBlank()) return emptyList()
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()

        val out = mutableListOf<VocabularyFinding>()
        for (vocabulary in vocabularies) {
            for (term in vocabulary.terms) {
                val termTokens = tokenize(term)
                if (termTokens.isEmpty()) continue
                if (containsSequence(tokens, termTokens)) {
                    out += VocabularyFinding(
                        vocabulary = vocabulary.name,
                        term = term,
                        raw = text,
                        lineIndex = lineIndex
                    )
                }
            }
        }
        return out
    }

    /** Convenience for the common shape of asking about several separate fields at once — the
     *  index of each entry in [fields] is reported as the finding's line index. */
    fun classifyFields(
        fields: List<String?>,
        vocabularies: List<Vocabulary>
    ): List<VocabularyFinding> = fields.flatMapIndexed { index, field ->
        if (field == null) emptyList() else classify(field, vocabularies, lineIndex = index)
    }

    /**
     * Splits on separators, then rejoins runs of single-character tokens. That second step is what
     * makes a dotted designator equal its undotted spelling: "S.R.L." arrives as three one-letter
     * tokens and has to become the one token "srl" to match a list holding "SRL", and the same
     * applies to "P.F.A." against "PFA". A run is only collapsed when it is at least two characters
     * long, so an ordinary initial standing alone is left as it was.
     */
    private fun tokenize(value: String): List<String> {
        val raw = value.lowercase().split(separators).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var run = StringBuilder()
        for (token in raw) {
            if (token.length == 1) {
                run.append(token)
                continue
            }
            if (run.isNotEmpty()) {
                out += run.toString()
                run = StringBuilder()
            }
            out += token
        }
        if (run.isNotEmpty()) out += run.toString()
        return out
    }

    /**
     * A term's identity under THIS classifier's own tokenization — the single source callers must
     * use for questions like "do these two vocabularies collide". A validator that re-implements
     * the tokenization is a mirror, and a mirror drifts; two terms collide exactly when this
     * returns the same key for both ("S.R.L." and "srl" do).
     */
    fun termKey(term: String): String = tokenize(term).joinToString(" ")

    /**
     * Whether [term] is the fuller spelling of [name] — its tokens contain the name's, in order.
     *
     * The mirror of how a term is normally found. A list entry is matched *inside* a line, so a
     * shop listed as one word is recognised in every longer rendering of it; this answers the other
     * direction, where the entry is the longer one and the message says less. Someone who lists a
     * shop by its full registered name should not stop recognising it the day a message names only
     * the shop.
     *
     * Token sequences on both sides, never characters. Character containment would make a word
     * match inside an unrelated one — a four-letter shop hides in plenty of ordinary words — and a
     * false claim here does not merely mislabel a field, it makes the other field stop resolving too.
     */
    fun isFullerSpellingOf(term: String, name: String): Boolean {
        val termTokens = tokenize(term)
        val nameTokens = tokenize(name)
        if (termTokens.isEmpty() || nameTokens.isEmpty()) return false
        if (nameTokens.size >= termTokens.size) return false
        return containsSequence(termTokens, nameTokens)
    }


    /**
     * Where a term sits, for callers to whom order is the meaning.
     *
     * [tokenIndex] counts tokens under this object's own tokenization, not characters, because that
     * is the only counting consistent with how a term is matched: "U.M." is one token here and four
     * characters on the page. A caller ordering table columns by their headings needs exactly this —
     * which heading came before which — and getting it from here rather than from a second scan of
     * the text is what keeps one tokenizer authoritative.
     */
    data class LocatedTerm(
        val vocabulary: String,
        val term: String,
        val tokenIndex: Int,
        /** How many tokens the term spans, so a caller can prefer the fuller reading of a place
         *  where two vocabularies both match — "unit price" over "unit", "valoare tva" over
         *  "valoare". Both are genuinely there; only the longer is what is printed. */
        val tokenCount: Int
    )

    /**
     * The first occurrence of each vocabulary's terms, with position.
     *
     * Where several of a vocabulary's terms match, the longest wins: a list holding both "value" and
     * "value added tax" should report the longer reading of a heading that contains it, since the
     * shorter one is a fragment of the same words rather than a second heading.
     */
    fun locate(text: String, vocabularies: List<Vocabulary>): List<LocatedTerm> {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        return vocabularies.mapNotNull { vocabulary ->
            vocabulary.terms
                .mapNotNull { term ->
                    val termTokens = tokenize(term)
                    if (termTokens.isEmpty()) return@mapNotNull null
                    indexOfSequence(tokens, termTokens)?.let { at -> Triple(term, at, termTokens.size) }
                }
                .minWithOrNull(compareBy({ it.second }, { -it.third }))
                ?.let { LocatedTerm(vocabulary.name, it.first, it.second, it.third) }
        }.sortedBy { it.tokenIndex }
    }

    private fun indexOfSequence(haystack: List<String>, needle: List<String>): Int? {
        if (needle.size > haystack.size) return null
        for (start in 0..haystack.size - needle.size) {
            if ((needle.indices).all { haystack[start + it] == needle[it] }) return start
        }
        return null
    }

    private fun containsSequence(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }
}
