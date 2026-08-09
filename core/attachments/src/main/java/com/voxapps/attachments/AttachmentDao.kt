package com.voxapps.attachments

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface AttachmentDao {
    /**
     * Room re-runs an observed query on *any* write to the `attachments` table, not just one that
     * changes this record's rows — so attaching a photo to record #5 re-emits an identical list to
     * every other record's observer. That matters here because the callers are per-item card
     * composables inside a `LazyColumn` ([observeRecordIdsWithAttachments]'s doc comment describes
     * the alternative), so each unfiltered re-emission recomposes every visible row. Deduping
     * structurally means only the record that actually changed recomposes.
     */
    fun observeFor(recordType: String, recordId: Long): Flow<List<AttachmentEntity>> =
        observeForInternal(recordType, recordId).distinctUntilChanged()

    @Query("SELECT * FROM attachments WHERE recordType = :recordType AND recordId = :recordId ORDER BY createdAt ASC")
    fun observeForInternal(recordType: String, recordId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE recordType = :recordType AND recordId = :recordId ORDER BY createdAt ASC")
    suspend fun getFor(recordType: String, recordId: Long): List<AttachmentEntity>

    /** Every record id (of [recordType]) that has at least one attachment — a single indexed query
     *  (see [AttachmentEntity]'s `(recordType, recordId)` index) instead of one [observeFor] per row,
     *  for list/widget screens that just need a "has attachments?" badge rather than the attachments
     *  themselves. */
    /**
     * Deliberately **not** deduped, unlike [observeFor].
     *
     * The satellites' containers put this flow in their widget-refresh `combine` precisely because
     * it is the only input that reacts to an attachment write — the record rows themselves don't
     * change when a photo is added to one. `SELECT DISTINCT` means adding a second photo to a
     * record that already has one re-emits an identical id list, and those are exactly the
     * emissions the widget wants: dedup here would silence them and reintroduce the stale-badge
     * problem the flow was added to fix. Bounding the resulting refresh rate is the consumer's job,
     * and each container does it with `conflate()`.
     */
    @Query("SELECT DISTINCT recordId FROM attachments WHERE recordType = :recordType")
    fun observeRecordIdsWithAttachments(recordType: String): Flow<List<Long>>

    /** One-shot form for widget rendering, which reads once and has no observer to keep. */
    @Query("SELECT DISTINCT recordId FROM attachments WHERE recordType = :recordType")
    suspend fun getRecordIdsWithAttachments(recordType: String): List<Long>

    @Query("SELECT COUNT(*) FROM attachments WHERE recordType = :recordType AND recordId = :recordId AND source = :source")
    suspend fun countFor(recordType: String, recordId: Long, source: String = AttachmentSource.MANUAL): Int

    /** Used before deleting a physical attachment file on record-delete cleanup, to check whether
     *  another row still points at the same on-disk file — e.g. an import that re-inserts an
     *  attachment under a new record id while reusing the original fileName, ahead of the
     *  "replace snapshot" delete of the record it was originally attached to. */
    @Query("SELECT COUNT(*) FROM attachments WHERE recordType = :recordType AND fileName = :fileName")
    suspend fun countByFileName(recordType: String, fileName: String): Int

    /** Moves one attachment to a different owning record — the other half of the scenario
     *  [countByFileName]'s doc comment anticipates: a duplicate-merge adopting a losing record's
     *  file onto the surviving one, so the row (and therefore [countByFileName]'s guard) follows
     *  the record that now actually owns the file, instead of being deleted out from under it when
     *  the losing record's own attachments are cleaned up. */
    @Query("UPDATE attachments SET recordId = :newRecordId WHERE recordType = :recordType AND recordId = :oldRecordId AND fileName = :fileName")
    suspend fun reassignRecordId(recordType: String, oldRecordId: Long, newRecordId: Long, fileName: String)

    @Insert
    suspend fun insert(entity: AttachmentEntity): Long

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getById(id: Long): AttachmentEntity?

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: Long)

    /** For cascade-safe record deletion — returns the deleted rows so the caller can also delete
     *  their backing files (this module never touches the filesystem itself, see
     *  [AttachmentFileStore]). */
    suspend fun deleteAllFor(recordType: String, recordId: Long): List<AttachmentEntity> {
        val rows = getFor(recordType, recordId)
        deleteAllForInternal(recordType, recordId)
        return rows
    }

    @Query("DELETE FROM attachments WHERE recordType = :recordType AND recordId = :recordId")
    suspend fun deleteAllForInternal(recordType: String, recordId: Long)

    /** For cancelling a burst mid-capture (see [com.voxapps.attachments.ui.rememberBurstCaptureLauncher])
     *  — same "return the deleted rows so the caller can also delete their backing files" shape as
     *  [deleteAllFor], just scoped to one group instead of the whole record. */
    suspend fun deleteGroup(recordType: String, recordId: Long, groupId: String): List<AttachmentEntity> {
        val rows = getFor(recordType, recordId).filter { it.groupId == groupId }
        deleteGroupInternal(recordType, recordId, groupId)
        return rows
    }

    @Query("DELETE FROM attachments WHERE recordType = :recordType AND recordId = :recordId AND groupId = :groupId")
    suspend fun deleteGroupInternal(recordType: String, recordId: Long, groupId: String)
}
