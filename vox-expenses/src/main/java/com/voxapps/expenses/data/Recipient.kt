package com.voxapps.expenses.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Who a transaction paid: the counterparty of a bank transfer, as this install knows them.
 *
 * The mirror image of [BankAccount] on the other side of the movement — the accounts are where the
 * money left FROM, a recipient is where it went TO — and the list works the same way: rows are
 * recognised by their number where one is known, by their name otherwise, and a slip naming a
 * recipient this list already holds resolves to the row rather than to whatever spelling OCR
 * produced that day. That is the point of the list: the name here is canonical, cleaned up once,
 * reused on every record that links to it.
 *
 * **The link IS the transaction flag.** An expense with a `recipientId` is a transaction; one
 * without is not. No second flag exists to disagree with the link — the same lesson as the dropped
 * `expenses.bank` column: store the pointer, derive everything else.
 */
@Entity(
    tableName = "recipients",
    indices = [Index(value = ["iban"], unique = true)]
)
data class Recipient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** The canonical name — required, unlike a [BankAccount.label]: a recipient nobody can name is
     *  not a recipient anybody recognises, so a nameless row has nothing to exist for. */
    val name: String,

    /** The recipient's own bank, where a slip's "Banca:" caption named one. Display only. */
    val bankName: String? = null,

    /**
     * The recipient's IBAN, checksum-verified where present — or null for a row made by hand
     * before any document carried the number. Nullable deliberately: the unique index treats NULLs
     * as distinct, so many number-less rows coexist, and the first slip that names the number
     * fills it in (see [com.voxapps.expenses.domain.accounts.Recipients.fillsIban]).
     */
    val iban: String? = null,

    val createdAt: Long,

    /** True for rows a scan created on its own; false for rows a person typed. */
    val autoCreated: Boolean = false,

    /**
     * Out of the pickers, still on its records. Because the link is the transaction flag, deleting
     * a recipient demotes every linked expense from being a transaction at all — archiving is the
     * way to stop offering one without rewriting history.
     */
    val archived: Boolean = false
) {
    /** One line for a picker row's second line: the number where known, the bank otherwise. */
    fun subtitle(): String? = iban ?: bankName
}
