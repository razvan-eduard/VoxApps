package com.voxapps.attachments

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE recordType = :recordType AND recordId = :recordId ORDER BY createdAt ASC")
    fun observeFor(recordType: String, recordId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE recordType = :recordType AND recordId = :recordId ORDER BY createdAt ASC")
    suspend fun getFor(recordType: String, recordId: Long): List<AttachmentEntity>

    @Query("SELECT COUNT(*) FROM attachments WHERE recordType = :recordType AND recordId = :recordId AND source = :source")
    suspend fun countFor(recordType: String, recordId: Long, source: String = AttachmentSource.MANUAL): Int

    /** Used before deleting a physical attachment file on record-delete cleanup, to check whether
     *  another row still points at the same on-disk file — e.g. an import that re-inserts an
     *  attachment under a new record id while reusing the original fileName, ahead of the
     *  "replace snapshot" delete of the record it was originally attached to. */
    @Query("SELECT COUNT(*) FROM attachments WHERE recordType = :recordType AND fileName = :fileName")
    suspend fun countByFileName(recordType: String, fileName: String): Int

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
}
