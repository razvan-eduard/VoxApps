package com.voxapps.expenses.data

import androidx.room.Entity

/**
 * Records a locally-deleted expense's [uid] + [deletedAt] so a peer-to-peer sync can propagate the
 * deletion instead of the peer's still-existing copy silently reviving it on the next merge (see
 * :core:datahygiene's merge helper). Kept as its own table rather than a soft-delete column on
 * [Expense] so every existing query/UI stays untouched — only sync code ever reads this table.
 * Pruned past a retention window (see [ExpenseDao.deleteStaleTombstones]) — no peer
 * realistically needs a deletion older than that to have already synced.
 */
@Entity(tableName = "expense_tombstones", primaryKeys = ["uid"])
data class ExpenseTombstone(
    val uid: String,
    val deletedAt: Long
)
