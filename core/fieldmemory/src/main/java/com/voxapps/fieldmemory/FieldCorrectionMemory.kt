package com.voxapps.fieldmemory

import com.voxapps.datahygiene.FieldCleaner
import com.voxapps.textmatch.extract.FieldCorrections
import com.voxapps.textmatch.extract.VocabularyClassifier

/**
 * The learning policy over [LearnedFieldCorrection] rows: what a manual edit teaches, when a
 * correction becomes active, and what disagreement means.
 *
 * Learning consumes only pairs of "the field as it was" and "the field as the human saved it" —
 * the diff rules in [FieldCorrections.diff] decide whether such a pair teaches anything at all.
 * Two fixes that are the same word under `termKey` (case or punctuation variants of one spelling)
 * count as agreement and keep the first-seen spelling; a genuinely different word quarantines the
 * garble permanently (see [LearnedFieldCorrection]).
 */
class FieldCorrectionMemory(
    private val dao: LearnedFieldCorrectionDao,
    private val now: () -> Long = System::currentTimeMillis
) {

    /** Feeds every old/new pair through the diff; positions beyond the shorter list are ignored. */
    suspend fun learn(oldFields: List<String?>, newFields: List<String?>) {
        for (i in 0 until minOf(oldFields.size, newFields.size)) {
            val correction = FieldCorrections.diff(oldFields[i], newFields[i]) ?: continue
            val fix = FieldCleaner.clean(correction.fix) ?: continue
            record(correction.garbageKey, fix)
        }
    }

    private suspend fun record(garbageKey: String, fix: String) {
        val existing = dao.get(garbageKey)
        val updated = when {
            existing == null ->
                LearnedFieldCorrection(garbageKey, fix, consecutiveCount = 1, quarantined = false, updatedAt = now())
            existing.quarantined -> return
            VocabularyClassifier.termKey(existing.fix) == VocabularyClassifier.termKey(fix) ->
                existing.copy(consecutiveCount = existing.consecutiveCount + 1, updatedAt = now())
            else -> existing.copy(quarantined = true, updatedAt = now())
        }
        dao.upsert(updated)
    }

    /** The corrections currently allowed to answer, as the map [FieldCorrections] consumes. */
    suspend fun activeCorrections(threshold: Int): Map<String, String> =
        dao.getActive(threshold).associate { it.garbageKey to it.fix }

    /** Every field of [fields] with the exact tier applied. */
    fun applyAll(fields: List<String?>, corrections: Map<String, String>): List<String?> =
        fields.map { FieldCorrections.apply(it, corrections) }

    /** Every stored row, for backup export. */
    suspend fun snapshot(): List<LearnedFieldCorrection> = dao.getAll()

    /** Restores a backup row without ever weakening what is already known: the higher count wins
     *  and quarantine is permanent in both directions of the merge. */
    suspend fun restore(row: LearnedFieldCorrection) {
        val existing = dao.get(row.garbageKey)
        if (existing == null) {
            dao.upsert(row)
            return
        }
        dao.upsert(
            existing.copy(
                consecutiveCount = maxOf(existing.consecutiveCount, row.consecutiveCount),
                quarantined = existing.quarantined || row.quarantined,
                updatedAt = maxOf(existing.updatedAt, row.updatedAt)
            )
        )
    }
}
