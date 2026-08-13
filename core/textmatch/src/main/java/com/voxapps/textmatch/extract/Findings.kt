package com.voxapps.textmatch.extract

import java.time.LocalDate
import java.time.LocalTime

/**
 * What the deterministic extractors report. Every finding carries the evidence that produced it —
 * the text it was read from and where — because the caller, not the extractor, decides which
 * finding to believe.
 *
 * That division is the point of this package. The same date on a receipt and on a calendar
 * invitation is the same extraction and the opposite validity rule: one cannot be in the future,
 * the other usually is. An extractor that ruled on validity could serve only one of them, so these
 * types describe what is present in the text and stop there. Selecting among findings, rejecting
 * them, and mapping them onto a record's fields all belong to the caller.
 */
sealed interface Finding {
    /** The exact substring this was read from, for display and for auditing a wrong result. */
    val raw: String

    /** Zero-based line the match started on, so a caller can weigh position — a document's header
     *  block and its footer carry different meaning for the same shape. */
    val lineIndex: Int
}

data class DateFinding(
    val value: LocalDate,
    override val raw: String,
    override val lineIndex: Int
) : Finding

data class TimeFinding(
    val value: LocalTime,
    override val raw: String,
    override val lineIndex: Int
) : Finding

/**
 * An amount that appeared under one of the labels the caller asked about. [label] is the label text
 * that admitted it, so a caller ranking by specificity can tell "total due" from "subtotal" without
 * re-scanning the document.
 */
data class AmountFinding(
    val value: Double,
    val label: String,
    override val raw: String,
    override val lineIndex: Int
) : Finding

/**
 * A field matched against a named vocabulary — see [VocabularyClassifier]. [vocabulary] is the
 * caller's own name for the list that matched ("bank", "legalForm", ...); this package attaches no
 * meaning to it beyond identity.
 */
data class VocabularyFinding(
    val vocabulary: String,
    val term: String,
    override val raw: String,
    override val lineIndex: Int
) : Finding
