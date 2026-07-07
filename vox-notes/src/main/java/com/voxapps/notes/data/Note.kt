package com.voxapps.notes.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single note. [categoryId] is null when the note is uncategorized. Deleting a category nulls its
 * notes' [categoryId] in code (NotesRepository.deleteCategory) rather than via a DB foreign key —
 * SQLite can't add an FK via ALTER TABLE, so a code-level rule keeps migrations simple.
 */
@Entity(
    tableName = "notes",
    indices = [Index("categoryId")]
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String? = null,
    val text: String,
    val createdAt: Long,
    @ColumnInfo(name = "categoryId") val categoryId: Long? = null
)
