package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.preferences.ExpensesSettings

/**
 * Whether a record shows its tax breakdown, and whether it should offer to.
 *
 * The setting alone cannot answer this, which is why it stopped being a switch. Showing the columns
 * always leaves most receipts with three empty fields; never showing them hides a breakdown the scan
 * genuinely read off the page. So the middle setting asks the record instead of the user, and the
 * two questions here are the whole of it.
 *
 * The offer exists for the one combination that would otherwise lose something quietly: the columns
 * are switched off, and this particular document turned out to carry them. Saying so once, on the
 * record it applies to, is the difference between a preference and a silent discard.
 */
object VatDisplay {

    /** Whether the net/VAT/gross columns are shown for a record that [carriesVat] or not. */
    fun shows(mode: String, carriesVat: Boolean): Boolean = when (mode) {
        ExpensesSettings.VAT_ON -> true
        ExpensesSettings.VAT_OFF -> false
        // Unknown values read as the setting that decides per document, which is the one that is
        // right more often than either fixed answer.
        else -> carriesVat
    }

    /**
     * Whether to tell someone that this record has a breakdown they have chosen not to see.
     *
     * Only where the choice was explicit and the record contradicts it. Never on the setting that
     * already decides per document, since there is nothing there to offer.
     */
    fun offersToShow(mode: String, carriesVat: Boolean): Boolean =
        mode == ExpensesSettings.VAT_OFF && carriesVat

    /**
     * Whether a record has a breakdown at all: its own figures, or any line that carries tax.
     *
     * Presence is the signal, and it is only ever presence — nothing here derives tax from a rate.
     * A row's share of a printed tax total does not distribute exactly at the cent, so deriving it
     * would produce a breakdown the document never printed and could not be reconciled against.
     */
    fun carriesVat(netAmount: Double?, vatAmount: Double?, itemVatAmounts: List<Double?>): Boolean =
        vatAmount != null || netAmount != null || itemVatAmounts.any { it != null && it != 0.0 }
}
