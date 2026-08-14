package com.voxapps.fieldmemory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One word-level correction a human has taught by editing a record: a garbled spelling and the
 * true word it stands for, valid in any alphanumeric text field of the owning app.
 *
 * [garbageKey] is the garbled word's identity under `VocabularyClassifier.termKey` (see
 * `FieldCorrections` in :core:textmatch, which produces and consumes these rows' data).
 * [consecutiveCount] rises while every sighting of the garble is corrected to the same [fix];
 * the correction answers only at or above the caller's threshold. [quarantined] is permanent:
 * a garble the user has corrected to two different words is ambiguous by demonstration — no
 * later consistency can prove the next occurrence isn't the other word — so the row stays as
 * a tombstone that blocks relearning rather than being deleted and forgotten.
 *
 * Lives in :core:fieldmemory rather than each app's own data layer so the entity/DAO/learning
 * logic is defined once; each app's own Room `@Database` still owns its own physical table —
 * there is no cross-process shared storage here.
 */
@Entity(tableName = "learned_field_corrections")
data class LearnedFieldCorrection(
    @PrimaryKey val garbageKey: String,
    val fix: String,
    val consecutiveCount: Int,
    val quarantined: Boolean,
    val updatedAt: Long
)
