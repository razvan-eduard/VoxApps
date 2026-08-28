package com.voxapps.docread

import com.voxapps.textmatch.extract.AmountText

/**
 * A transaction slip's total, proven by the slip's own structure instead of a "TOTAL" label.
 *
 * A transfer confirmation prints its amount either as a bare figure under a Debit/Credit column or
 * beside a transfer-specific caption ("Suma debitata", "Amount paid") — and never with rows summing
 * to it, so the ordinary proofs are structurally absent from this document class.
 *
 * Every shape below runs behind ONE shared gate: the page must announce itself as a transfer
 * document (a word like "tranzactie"/"transfer"/"beneficiar" somewhere in the text). That gate is
 * what keeps these captions from ever hijacking an invoice or a receipt — a store bill that happens
 * to print "suma platita" is still read by the invoice machinery first, and by these shapes never,
 * because it does not speak the transfer vocabulary.
 *
 * Shapes are tried strictest-first; the first that yields exactly one figure wins. A page where a
 * shape matches several figures is a statement, not a confirmation, and that shape refuses —
 * collapsing a month of movements into one expense would be a guess, and this library rejects
 * guesses rather than ranking them.
 */
object BankSlipReader {

    const val TEMPLATE_ID_TABLE = "bank-slip"
    private const val TEMPLATE_ID_CAPTION = "bank-slip-caption"

    /** The words that make a page a transfer document at all — the shared gate. */
    private val transferContext = listOf(
        "tranzactie", "tranzacție", "transfer", "beneficiar", "ordonator",
        "transaction", "payment confirmation", "virament", "überweisung", "virement"
    )

    // The Debit/Credit table heading's own words, per language — same en/ro/de/fr set the column
    // vocabulary serves elsewhere. Ro/en share the debit/credit spellings.
    private val dateWords = setOf("data", "date", "datum")
    private val debitWords = setOf("debit", "soll", "débit")
    private val creditWords = setOf("credit", "haben", "crédit")

    /**
     * The captioned shapes, strictest wording first. Substring-matched case-insensitively on the
     * caption's line, amount read AFTER the caption so a figure printed before it can never be
     * taken (same discipline as [FooterReader]'s inline mode).
     */
    private val captionShapes: List<Pair<String, List<String>>> = listOf(
        "debited" to listOf("suma debitata", "suma debitată", "amount debited", "debited amount", "total debitat"),
        "transferred" to listOf(
            "suma transferata", "suma transferată", "suma trimisa", "suma trimisă",
            "amount transferred", "transferred amount", "you sent"
        ),
        "transaction-value" to listOf(
            "valoare tranzactie", "valoare tranzacție", "valoarea tranzactiei", "valoarea tranzacției",
            "transaction amount", "transaction value"
        ),
        "paid" to listOf("suma platita", "suma plătită", "amount paid", "total paid", "payment amount")
    )

    fun candidate(plainText: String): FooterReader.Candidate? {
        val lower = plainText.lowercase()
        if (transferContext.none { it in lower }) return null

        tableShape(plainText)?.let { return it }
        return captionShape(plainText)
    }

    /** Shape 1: the Debit/Credit table heading vouches for the single figure beneath it. */
    private fun tableShape(plainText: String): FooterReader.Candidate? {
        val lines = plainText.lines()
        val headerIndex = lines.indexOfFirst { line ->
            val words = line.lowercase().split(NON_LETTERS).filter { it.isNotBlank() }
            words.any { it in dateWords } && words.any { it in debitWords } && words.any { it in creditWords }
        }
        if (headerIndex < 0) return null
        val single = lines.drop(headerIndex + 1).flatMap { amountsIn(it) }.singleOrNull() ?: return null
        return totalOf(TEMPLATE_ID_TABLE, single)
    }

    /** Shapes 2–5: a transfer caption owns the one figure printed after it on its line. */
    private fun captionShape(plainText: String): FooterReader.Candidate? {
        val lines = plainText.lines()
        for ((shape, captions) in captionShapes) {
            val found = lines.mapNotNull { line ->
                val lower = line.lowercase()
                val caption = captions.firstOrNull { it in lower } ?: return@mapNotNull null
                val after = line.substring(lower.indexOf(caption) + caption.length)
                amountsIn(after).firstOrNull() ?: amountsIn(line).firstOrNull()
            }
            found.singleOrNull()?.let { return totalOf("$TEMPLATE_ID_CAPTION-$shape", it) }
        }
        return null
    }

    /** The slip's parties, read off its own captions — see [fields]. */
    data class SlipFields(
        val beneficiary: String?,
        val ownIban: String?,
        val counterpartyIban: String? = null,
        val counterpartyBank: String? = null
    )

    // Lines that name the OTHER side of the transfer: an IBAN printed on one of these belongs to
    // the counterparty, never to the payer whose account the record should land on. Substring-
    // matched, and doubling as the ownIban exclusion filter — do NOT add plain bank words here
    // ("banca"/"bank"): prose mentioning a bank on the payer's own line would silently drop that
    // line out of the own-IBAN pool.
    private val counterpartyCaptions = listOf("in contul", "beneficiar", "catre", "către", "to account", "payee")

    private val beneficiaryCaptions = listOf("beneficiar", "payee", "recipient", "empfänger", "bénéficiaire")

    // The counterparty's BANK caption. Line-leading + colon-anchored, never substring: the standard
    // slip's letterhead ("BANCA EXEMPLU S.A. …") and movement line ("Transfer Home'Bank") both
    // contain bank words, and a substring match would collect them, see two values and erase the
    // answer through singleOrNull.
    private val counterpartyBankCaptions = listOf(
        "banca", "bank", "beneficiary bank", "payee bank", "empfängerbank", "banque", "banque du bénéficiaire"
    )

    /**
     * What the slip states about its parties, behind the same transfer gate as [candidate]:
     *
     * - [beneficiary]: the text after the one "Beneficiar:"-style caption — who was paid, which is
     *   what the record's vendor means. Exactly one such line, or nothing.
     * - [ownIban]: the payer's account. A slip prints two checksum-valid IBANs — the payer's beside
     *   "Numar cont:" and the counterparty's beside "In contul:" — and an ambiguity rule that sees
     *   both must refuse. The counterparty's is identified by its own caption and excluded; exactly
     *   one IBAN must survive, checksum-verified, or nothing is claimed.
     * - [counterpartyIban]: the mirror image — the one IBAN found ON the counterparty-captioned
     *   lines. The two pools are complementary line-sets over the same caption list, so one IBAN
     *   can never land on both sides.
     * - [counterpartyBank]: the value after a line-leading "Banca:"-style caption — the
     *   beneficiary's own bank, which on this document class is never the letterhead (the
     *   letterhead is the PAYER's bank).
     */
    fun fields(plainText: String): SlipFields? {
        val lower = plainText.lowercase()
        if (transferContext.none { it in lower }) return null
        val lines = plainText.lines()

        val beneficiary = lines.mapNotNull { line ->
            val lineLower = line.lowercase()
            val caption = beneficiaryCaptions.firstOrNull { it in lineLower } ?: return@mapNotNull null
            line.substring(lineLower.indexOf(caption) + caption.length)
                .trimStart(':', ' ', '\t')
                .trim()
                .takeIf { it.isNotBlank() }
        }.distinct().singleOrNull()

        val ownIban = ibansOn(lines.filterNot { line -> counterpartyCaptions.any { it in line.lowercase() } })
        val counterpartyIban = ibansOn(lines.filter { line -> counterpartyCaptions.any { it in line.lowercase() } })

        val counterpartyBank = lines.mapNotNull { line ->
            val trimmed = line.trim()
            val lineLower = trimmed.lowercase()
            val caption = counterpartyBankCaptions
                .filter { lineLower.startsWith("$it:") }
                .maxByOrNull { it.length } ?: return@mapNotNull null
            trimmed.substring(caption.length + 1).trim().takeIf { it.isNotBlank() }
        }.distinct().singleOrNull()

        if (beneficiary == null && ownIban == null && counterpartyIban == null && counterpartyBank == null) return null
        return SlipFields(
            beneficiary = beneficiary,
            ownIban = ownIban,
            counterpartyIban = counterpartyIban,
            counterpartyBank = counterpartyBank
        )
    }

    /** The single checksum-valid IBAN these lines carry, or nothing — shared by both party pools. */
    private fun ibansOn(lines: List<String>): String? = lines
        .flatMap { line ->
            com.voxapps.textmatch.extract.AccountIdentifiers.find(line)
                .filter { it.kind == com.voxapps.textmatch.extract.AccountIdentifiers.Kind.IBAN }
        }
        .map { it.digits }
        .distinct()
        .singleOrNull()

    private fun totalOf(id: String, amount: Double) = FooterReader.Candidate(
        templateId = id,
        grandTotal = amount,
        invoiceTotal = null,
        previousBalance = null,
        net = null,
        vat = null
    )

    private fun amountsIn(text: String): List<Double> =
        AmountText.printed.findAll(text)
            .mapNotNull { AmountText.normalize(it.value) }
            .filter { it > 0.0 }
            .toList()

    private val NON_LETTERS = Regex("[^\\p{L}]+")
}
