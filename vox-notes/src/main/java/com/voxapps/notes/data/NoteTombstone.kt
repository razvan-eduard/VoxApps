package com.voxapps.notes.data

import androidx.room.Entity

/**
 * Records a locally-deleted note's [uid] + [deletedAt] so a peer-to-peer sync can propagate the
 * deletion instead of the peer's still-existing copy silently reviving it on the next merge (see
 * :core:datahygiene's merge helper) — mirrors vox-expenses' ExpenseTombstone. Kept as its own table
 * rather than a soft-delete column on [Note] so every existing query/UI stays untouched. Pruned past
 * a retention window (see [NoteDao.deleteStaleTombstones]) — no peer realistically needs a deletion
 * older than that to have already synced.
 */
@Entity(tableName = "note_tombstones", primaryKeys = ["uid"])
data class NoteTombstone(
    val uid: String,
    val deletedAt: Long
)
