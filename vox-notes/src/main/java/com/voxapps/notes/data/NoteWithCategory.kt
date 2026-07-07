package com.voxapps.notes.data

import androidx.room.Embedded
import androidx.room.Relation

/**
 * A note joined with its category (nullable) so cards can render the name/color without a manual
 * lookup. Room fills [category] by matching [Note.categoryId] → [Category.id].
 */
data class NoteWithCategory(
    @Embedded val note: Note,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: Category? = null
)
