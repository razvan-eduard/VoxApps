package com.voxapps.vision.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuityMatcherTest {

    @Test
    fun `accepts an obviously continuous pair at medium strictness`() {
        val previous = "This is the start of a long receipt showing fifty two lei exactly total"
        val next = "fifty two lei exactly total is the grand sum for this receipt"
        assertTrue(ContinuityMatcher.isContinuous(previous, next, ContinuityMatcher.Strictness.MEDIUM))
    }

    @Test
    fun `rejects two unrelated texts at every strictness level`() {
        val previous = "Receipt from the pharmacy dated yesterday afternoon purchase"
        val next = "Weather forecast for next week shows heavy rain expected"
        for (strictness in ContinuityMatcher.Strictness.entries) {
            assertFalse(ContinuityMatcher.isContinuous(previous, next, strictness))
        }
    }

    @Test
    fun `lazy strictness tolerates weaker overlap than strict`() {
        // A genuine 3-word matching run, but with OCR noise on 2 of those 3 words ("total"/"toal",
        // "exactly"/"exactli") — only LAZY's low ratio bar (0.2) survives that much internal noise;
        // MEDIUM/STRICT's floor (5 words) also never even reaches this 3-word run at all. Vocabulary
        // otherwise fully disjoint between the two texts so no coincidental shared word elsewhere can
        // accidentally satisfy either check.
        val previous = "alpha bravo charlie delta echo total exactly lapte"
        val next = "toal exactli lapte foxtrot golf hotel india juliet"
        val lazyResult = ContinuityMatcher.isContinuous(previous, next, ContinuityMatcher.Strictness.LAZY)
        val strictResult = ContinuityMatcher.isContinuous(previous, next, ContinuityMatcher.Strictness.STRICT)
        val mediumResult = ContinuityMatcher.isContinuous(previous, next, ContinuityMatcher.Strictness.MEDIUM)
        assertTrue(lazyResult)
        assertFalse(strictResult)
        assertFalse(mediumResult)
    }

    @Test
    fun `blank text on either side is never continuous`() {
        assertFalse(ContinuityMatcher.isContinuous("", "some text here", ContinuityMatcher.Strictness.LAZY))
        assertFalse(ContinuityMatcher.isContinuous("some text here", "", ContinuityMatcher.Strictness.LAZY))
        assertFalse(ContinuityMatcher.isContinuous("   ", "   ", ContinuityMatcher.Strictness.LAZY))
    }

    @Test
    fun `strictnessFromSetting maps known keys and defaults to medium`() {
        assertTrue(ContinuityMatcher.strictnessFromSetting("strict") == ContinuityMatcher.Strictness.STRICT)
        assertTrue(ContinuityMatcher.strictnessFromSetting("lazy") == ContinuityMatcher.Strictness.LAZY)
        assertTrue(ContinuityMatcher.strictnessFromSetting("medium") == ContinuityMatcher.Strictness.MEDIUM)
        assertTrue(ContinuityMatcher.strictnessFromSetting("unknown") == ContinuityMatcher.Strictness.MEDIUM)
    }

    @Test
    fun `alignForStitch drops exactly the repeated run and keeps only the new content`() {
        // Simulates "rows 1-10" then "rows 6-13" — a deliberate 5-row overlap for continuity, clean
        // edges (no bisected row) on both sides.
        val previous = "row1 row2 row3 row4 row5 row6 row7 row8 row9 row10"
        val next = "row6 row7 row8 row9 row10 row11 row12 row13"
        val alignment = ContinuityMatcher.alignForStitch(previous, next, ContinuityMatcher.Strictness.MEDIUM)
        assertEquals(previous, alignment?.previousTrimmed)
        assertEquals("row11 row12 row13", alignment?.nextTrimmed)
    }

    @Test
    fun `alignForStitch handles a natural-language overlap the same way`() {
        val previous = "the quick brown fox jumps over the lazy dog today"
        val next = "over the lazy dog today and then ran away quickly"
        val alignment = ContinuityMatcher.alignForStitch(previous, next, ContinuityMatcher.Strictness.MEDIUM)
        assertEquals(previous, alignment?.previousTrimmed)
        assertEquals("and then ran away quickly", alignment?.nextTrimmed)
    }

    @Test
    fun `alignForStitch returns null when there is no reliable overlap`() {
        val previous = "Receipt from the pharmacy dated yesterday afternoon purchase"
        val next = "Weather forecast for next week shows heavy rain expected"
        assertNull(ContinuityMatcher.alignForStitch(previous, next, ContinuityMatcher.Strictness.MEDIUM))
    }

    @Test
    fun `stricter strictness requires a longer minimum overlap before aligning kicks in`() {
        // A genuine but short (3-word) contiguous overlap — clears LAZY's floor (3) but is shorter
        // than STRICT's (8), so STRICT can't confirm a seam at all.
        val previous = "some earlier context words here total exactly lapte"
        val next = "total exactly lapte and then completely different content follows"
        assertTrue(ContinuityMatcher.isContinuous(previous, next, ContinuityMatcher.Strictness.LAZY))
        assertFalse(ContinuityMatcher.isContinuous(previous, next, ContinuityMatcher.Strictness.STRICT))
    }

    @Test
    fun `punctuation differences between OCR reads of the same text don't break a match`() {
        // Same physical receipt header, read with different punctuation across two close-up shots —
        // a real, common case (S.C./SC, commas vs periods) that must still be recognized as continuous.
        val previous = "S.C. LIDL DISCOUNT S.R.L."
        val next = "S,C, LIDL DISCOUNT SRL and total 45 lei"
        assertTrue(ContinuityMatcher.isContinuous(previous, next, ContinuityMatcher.Strictness.MEDIUM))
        val alignment = ContinuityMatcher.alignForStitch(previous, next, ContinuityMatcher.Strictness.MEDIUM)
        assertEquals("and total 45 lei", alignment?.nextTrimmed)
    }

    @Test
    fun `a whole short receipt line repeated at the next shot's start is accepted at every strictness`() {
        // The realistic close-up case: previous shot's ENTIRE text (4 words) is what's repeated,
        // followed by genuinely new content.
        val previous = "S.C. LIDL DISCOUNT S.R.L."
        val next = "S.C. LIDL DISCOUNT S.R.L. and total 45 lei"
        for (strictness in ContinuityMatcher.Strictness.entries) {
            assertTrue(ContinuityMatcher.isContinuous(previous, next, strictness))
        }
    }

    @Test
    fun `tolerates a bisected row's garbled fragment trailing the previous shot`() {
        // previous's frame edge cut through the row after the real content, leaving noise; next's
        // frame started cleanly with a repeat of the real seam line.
        val previous = "Salata cu pui si legume 7.09 B garb1 garb2 garb3"
        val next = "Salata cu pui si legume 7.09 B Sunca Praga 5.59 B"
        val alignment = ContinuityMatcher.alignForStitch(previous, next, ContinuityMatcher.Strictness.MEDIUM)
        assertEquals(3, alignment?.let { previous.trim().split(Regex("\\s+")).size - it.previousTrimmed.trim().split(Regex("\\s+")).size })
        assertEquals("Salata cu pui si legume 7.09 B", alignment?.previousTrimmed)
        assertEquals("Sunca Praga 5.59 B", alignment?.nextTrimmed)
    }

    @Test
    fun `tolerates a bisected row's garbled fragment leading the next shot`() {
        val previous = "Sunca Praga 5.59 B Salata cu pui si legume 7.09 B"
        val next = "garb1 garb2 Salata cu pui si legume 7.09 B Ritter Sport 12.29 A"
        val alignment = ContinuityMatcher.alignForStitch(previous, next, ContinuityMatcher.Strictness.MEDIUM)
        assertEquals(previous, alignment?.previousTrimmed)
        assertEquals("Ritter Sport 12.29 A", alignment?.nextTrimmed)
    }

    @Test
    fun `tolerates garbled fragments on both sides of the seam simultaneously`() {
        val previous = "Sunca Praga 5.59 B Salata cu pui si legume 7.09 B poison1 poison2"
        val next = "noiseA noiseB Salata cu pui si legume 7.09 B Ritter Sport 12.29 A"
        val alignment = ContinuityMatcher.alignForStitch(previous, next, ContinuityMatcher.Strictness.MEDIUM)
        assertEquals("Sunca Praga 5.59 B Salata cu pui si legume 7.09 B", alignment?.previousTrimmed)
        assertEquals("Ritter Sport 12.29 A", alignment?.nextTrimmed)
    }

    // --- Stitched, row-ordered text ---
    // OCR now emits reading-order rows (see RowClusterer), so in stitch mode the newlines ARE the
    // row boundaries of one long receipt photographed as overlapping close-ups. The seam trims
    // must remove words at the seam without reformatting what survives — flattening the kept rows
    // to one line would undo the row assembly for every shot after the first.

    @Test
    fun `stitching row-ordered shots keeps every surviving newline`() {
        val previous = "Paine 2 3.50\nLapte 1 5.20\nOua 10 12.00"
        val next = "Lapte 1 5.20\nOua 10 12.00\nCafea 1 15.00\nTotal 35.70"

        val stitch = ContinuityMatcher.alignForStitch(previous, next, ContinuityMatcher.Strictness.MEDIUM)!!

        assertEquals("Paine 2 3.50\nLapte 1 5.20\nOua 10 12.00", stitch.previousTrimmed)
        // The six overlapping words are dropped from the next shot; its remaining rows keep their
        // own line structure.
        assertEquals("Cafea 1 15.00\nTotal 35.70", stitch.nextTrimmed)
    }

    @Test
    fun `a row bisected at the frame edge is trimmed away without touching other rows`() {
        // The next shot's camera edge cut a row in half, so its text begins with a garbled
        // fragment before the genuine overlap. That fragment is poison; the rows after the seam
        // keep their newlines.
        val previous = "Lapte 1 5.20\nOua 10 12.00\nCafea 1 15.00"
        val next = "aea 1 t5\nOua 10 12.00\nCafea 1 15.00\nZahar 2 4.40\nTotal 36.60"

        val stitch = ContinuityMatcher.alignForStitch(previous, next, ContinuityMatcher.Strictness.MEDIUM)!!

        assertEquals("Zahar 2 4.40\nTotal 36.60", stitch.nextTrimmed)
    }

    @Test
    fun `trailing noise on the previous shot is trimmed without flattening its kept rows`() {
        // The previous shot's bottom edge bisected a row ("Cafea 1 1..." read as garbage); the next
        // shot reveals the true seam. The previous shot's kept rows must stay rows.
        val previous = "Paine 2 3.50\nLapte 1 5.20\nOua 10 12.00\nCfa x 1e"
        val next = "Lapte 1 5.20\nOua 10 12.00\nCafea 1 15.00\nTotal 35.70"

        val stitch = ContinuityMatcher.alignForStitch(previous, next, ContinuityMatcher.Strictness.MEDIUM)!!

        assertEquals("Paine 2 3.50\nLapte 1 5.20\nOua 10 12.00", stitch.previousTrimmed)
        assertEquals("Cafea 1 15.00\nTotal 35.70", stitch.nextTrimmed)
    }

    @Test
    fun `single-column rows match across shots whether or not neighbours merged`() {
        // Row clustering is geometry-driven, and two shots of the same rows can disagree about
        // merging vertical neighbours when skew puts them right at the threshold. In a single
        // column that disagreement only moves a newline — the word sequence is identical — so the
        // word-based matcher still finds the seam.
        val previous = "SC EXEMPLU SRL\nBon fiscal\nPaine 3.50 Lapte 5.20 Oua 12.00"
        val next = "Paine 3.50\nLapte 5.20\nOua 12.00\nTotal 20.70"
        assertTrue(ContinuityMatcher.isContinuous(previous, next, ContinuityMatcher.Strictness.MEDIUM))
    }

}
