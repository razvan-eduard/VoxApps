package com.voxapps.vision.ocr

/**
 * Decides whether one stitch shot's OCR text plausibly continues the previous accepted shot's text,
 * and if so, exactly where the two should be joined — see [com.voxapps.ipc.VoxOcrRequest.CAPTURE_MODE_STITCH]'s
 * doc comment for the feature this backs. Pure word-overlap heuristic (no ML).
 *
 * Since stitch shots skip [com.voxapps.vision.ocr.DocumentCropper.crop] (see
 * `com.voxapps.vision.ui.captureAndRecognize`'s `skipCrop` doc comment), each raw frame's physical top/
 * bottom edge can bisect a text row, and OCR reads that partial row as a short garbled fragment right at
 * the very start or end of the shot's recognized text — exactly where a naive "compare the literal last
 * N words against the literal first N words" check would look. [findAlignment] tolerates this: it
 * searches outward from a zero-noise assumption (fast path, matches what a clean edge would need) for a
 * genuine matching word run a few words further in, treating anything strictly between the true seam and
 * either shot's physical edge as discardable OCR noise rather than real content.
 */
object ContinuityMatcher {

    enum class Strictness { STRICT, MEDIUM, LAZY }

    /** Minimum length of a matching word run before it's trusted as real overlap rather than
     *  coincidence — a short run (e.g. one common word) could match by chance, so LAZY still requires a
     *  handful of words in a row, just tolerates more misreads within that run than STRICT does. */
    private fun minMatchWords(strictness: Strictness): Int = when (strictness) {
        Strictness.STRICT -> 8
        Strictness.MEDIUM -> 5
        Strictness.LAZY -> 3
    }

    private fun requiredOverlapRatio(strictness: Strictness): Float = when (strictness) {
        Strictness.STRICT -> 0.8f
        Strictness.MEDIUM -> 0.5f
        Strictness.LAZY -> 0.2f
    }

    /** Upper bound on how many trailing/leading words of OCR noise (a bisected text row, plus a small
     *  margin) either side of the true seam is allowed to have before [findAlignment] gives up — kept
     *  small on purpose: a bisected row is normally a handful of words, and a large tolerance would risk
     *  discarding genuine content as "noise" instead of a real cut-off line. */
    private const val MAX_POISON_WORDS = 10

    /** Upper bound on how many words a matching run search ever considers — stitch shots overlap by a
     *  portion of a page, not the whole document, so this is a sanity cap on the search, not a realistic
     *  expected overlap size. */
    private const val MAX_OVERLAP_WORDS = 200

    /** Tokenizes on whitespace, lowercases, then strips punctuation per token (never drops a token for
     *  going empty) so OCR's inconsistent punctuation between two reads of the same physical text
     *  (`"S.C."` vs `"SC"` vs `"S,C,"`) doesn't silently break an otherwise-real match. */
    private fun normalizedWords(text: String): List<String> =
        text.lowercase().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .map { it.replace(Regex("[^\\p{L}\\p{Nd}]"), "") }

    /** How much trailing noise on [previousText]'s side and leading noise on [nextText]'s side had to be
     *  skipped before a genuine [matchWords]-long run was found joining them. Zero/zero is the common
     *  case (a clean edge, no bisected row). */
    data class Alignment(val previousPoisonWords: Int, val nextPoisonWords: Int, val matchWords: Int)

    /**
     * Finds the seam between [previousText]'s tail and [nextText]'s head, tolerating up to
     * [MAX_POISON_WORDS] of leading/trailing noise on either side (e.g. a text row bisected by the
     * photo's physical frame edge — see the class doc comment). Considers every noise-tolerance
     * combination up to that bound and every candidate run length within each, then picks the single
     * BEST candidate by match ratio first (an ordered, position-matched comparison, not a set overlap —
     * finding a cut point needs an actual repeated run), run length second, total noise skipped third.
     *
     * Ratio must dominate the choice, not just clear the pass/fail threshold: without this, a large
     * zero-noise window can "absorb" several genuinely mismatched trailing words and still clear a lax
     * threshold (e.g. 7 real matches + 3 real mismatches in a 10-word window is 70%, which clears MEDIUM's
     * 50% bar) — even though a smaller, noise-skipping window elsewhere scores a much more confident
     * near-100% match. Picking the first pass/fail success (as an earlier version of this search did)
     * would keep the former and never even look for the latter, quietly gluing 2-3 real trailing words
     * from the wrong shot onto the kept/trimmed overlap instead of correctly treating them as new content.
     *
     * Returns null if no candidate anywhere clears [requiredOverlapRatio] — the caller then treats the
     * pair as not continuous.
     */
    fun findAlignment(previousText: String, nextText: String, strictness: Strictness): Alignment? {
        val previousWords = normalizedWords(previousText)
        val nextWords = normalizedWords(nextText)
        if (previousWords.isEmpty() || nextWords.isEmpty()) return null

        val threshold = requiredOverlapRatio(strictness)
        val minK = minMatchWords(strictness)

        var best: Alignment? = null
        var bestRatio = -1f
        var bestK = 0
        var bestPoison = Int.MAX_VALUE

        for (poisonPrev in 0..MAX_POISON_WORDS) {
            if (poisonPrev >= previousWords.size) break
            val prevSlice = if (poisonPrev == 0) previousWords else previousWords.dropLast(poisonPrev)
            for (poisonNext in 0..MAX_POISON_WORDS) {
                if (poisonNext >= nextWords.size) break
                val nextSlice = if (poisonNext == 0) nextWords else nextWords.drop(poisonNext)
                val maxK = minOf(prevSlice.size, nextSlice.size, MAX_OVERLAP_WORDS)
                if (maxK == 0) continue
                // A close-up shot's whole text (or what's left of it after skipping noise) can itself be
                // shorter than minMatchWords — cap the floor to whatever's actually available rather than
                // refusing to search this combo at all, so e.g. a short previous shot's ENTIRE text being
                // exactly repeated still counts as a confirmed match instead of silently requiring more
                // words than exist.
                val effectiveMinK = minOf(minK, maxK)
                // Largest k first per poison combo — only the best-scoring k at each combo can ever win
                // overall, so smaller k there is never worth recording once one clears the threshold.
                for (k in maxK downTo effectiveMinK) {
                    val prevSuffix = prevSlice.takeLast(k)
                    val nextPrefix = nextSlice.take(k)
                    var matches = 0
                    for (i in 0 until k) if (prevSuffix[i] == nextPrefix[i]) matches++
                    val ratio = matches.toFloat() / k
                    if (ratio >= threshold) {
                        val totalPoison = poisonPrev + poisonNext
                        if (ratio > bestRatio || (ratio == bestRatio && (k > bestK || (k == bestK && totalPoison < bestPoison)))) {
                            bestRatio = ratio
                            bestK = k
                            bestPoison = totalPoison
                            best = Alignment(poisonPrev, poisonNext, k)
                        }
                        break
                    }
                }
            }
        }
        return best
    }

    /** True if [nextText] plausibly continues [previousText] — i.e. [findAlignment] finds a genuine
     *  matching seam within tolerance. */
    fun isContinuous(previousText: String, nextText: String, strictness: Strictness): Boolean =
        findAlignment(previousText, nextText, strictness) != null

    fun strictnessFromSetting(setting: String): Strictness = when (setting) {
        "strict" -> Strictness.STRICT
        "lazy" -> Strictness.LAZY
        else -> Strictness.MEDIUM
    }

    // Both trims cut the ORIGINAL text at word boundaries and keep every character between the
    // surviving words verbatim. Rebuilding from split words joined with spaces — the previous
    // form — flattened newlines out of whatever it touched, and the matched run is always
    // dropped from a fresh shot, so every stitched shot after the first lost its line structure.
    // Once OCR emits reading-order rows, those newlines are the row boundaries every consumer
    // depends on; a trim's job is to remove words at an edge, not to reformat the document.
    private val wordRun = Regex("\\S+")

    private fun dropLastWords(text: String, count: Int): String {
        if (count <= 0) return text
        val spans = wordRun.findAll(text).toList()
        if (count >= spans.size) return ""
        return text.substring(0, spans[spans.size - count].range.first).trimEnd()
    }

    private fun dropFirstWords(text: String, count: Int): String {
        if (count <= 0) return text
        val spans = wordRun.findAll(text).toList()
        if (count >= spans.size) return ""
        return text.substring(spans[count].range.first)
    }

    /** [previousTrimmed] is [previousKeptText] with its trailing noise (if any) dropped — this can
     *  shrink an already-accepted shot's stored text, since noise at its tail was only ever discoverable
     *  once the NEXT shot's alignment revealed where the true seam was. [nextTrimmed] is [nextText] with
     *  its leading noise and the duplicated matching run both dropped, so joining `previousTrimmed +
     *  nextTrimmed` reproduces the underlying document exactly once through the seam, without either
     *  shot's edge noise. */
    data class StitchAlignment(val previousTrimmed: String, val nextTrimmed: String)

    /**
     * Computes how to join [previousKeptText] (the previous shot's already-accepted, already-seam-
     * trimmed text — its tail is still raw, since only [alignForStitch] ever trims a tail, and only once
     * a following shot's alignment against it is known) with [nextText] (a fresh, untrimmed shot). Word
     * boundaries are found on the ORIGINAL (not lowercased/stripped) text so casing and punctuation in
     * the kept portions survive untouched; only whitespace between words is normalized to a single space.
     * Returns null if [findAlignment] finds no genuine seam — the caller should then keep both texts
     * whole rather than risk cutting real content on an unconfirmed guess.
     */
    fun alignForStitch(previousKeptText: String, nextText: String, strictness: Strictness): StitchAlignment? {
        val alignment = findAlignment(previousKeptText, nextText, strictness) ?: return null
        val previousTrimmed = dropLastWords(previousKeptText, alignment.previousPoisonWords)
        val nextTrimmed = dropFirstWords(nextText, alignment.nextPoisonWords + alignment.matchWords)
        return StitchAlignment(previousTrimmed, nextTrimmed)
    }

    /** Inserted between two joined stitch shots' text in the final combined document (see
     *  `com.voxapps.vision.ui.combineStitchText`) — flags the join point for whatever downstream LLM
     *  cleanup consumes the combined text (see `ExpenseScanCleanupPromptBuilder`'s framing paragraph),
     *  since even a correctly-found seam can leave a word or two of residual noise/duplication right at
     *  the boundary that only semantic understanding (not this purely lexical matcher) can catch. */
    const val STITCH_SEAM_MARKER = "\n\n--- [photo stitch seam — two overlapping close-up shots were joined here; minor OCR noise or duplication may remain right at this point] ---\n\n"
}
