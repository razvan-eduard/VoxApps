package com.voxapps.notes.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.voxapps.schema.VoxExtractionSchema
import java.util.UUID

/**
 * A single note. [categoryId] is null when the note is uncategorized. Deleting a category nulls its
 * notes' [categoryId] in code (NotesRepository.deleteCategory) rather than via a DB foreign key —
 * SQLite can't add an FK via ALTER TABLE, so a code-level rule keeps migrations simple.
 *
 * Annotated `@VoxExtractionSchema` for uniformity with Expenses/Calendar (see the collapsed
 * voice-command plan's section 6a) even though Notes declares `needsExtractionPass = false` and this
 * generated schema is never actually sent over the wire — Notes' voice flow has no extraction LLM
 * call to attach it to, only Expenses'/Calendar's `Parsed`-style satellites use it for real.
 *
 * [uid]/[updatedAt] back the peer-to-peer sync merge (see :core:datahygiene's merge helper), mirroring
 * Expense's identical pair — see that entity's doc comment for the full rationale. Rows from before
 * these fields existed backfill via NotesDatabase's MIGRATION_2_3.
 */
@Entity(
    tableName = "notes",
    indices = [Index("categoryId"), Index(value = ["uid"], unique = true)]
)
@VoxExtractionSchema(version = 1)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = UUID.randomUUID().toString(),
    val title: String? = null,
    val text: String,
    val createdAt: Long,
    @ColumnInfo(name = "categoryId") val categoryId: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
