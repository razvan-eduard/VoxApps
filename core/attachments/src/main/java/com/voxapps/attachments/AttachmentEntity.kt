package com.voxapps.attachments

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** [source] values — plain strings, not a Kotlin enum, so this shared entity stays decoupled from
 *  any per-app type. */
object AttachmentSource {
    const val SCANNED = "scanned"
    const val MANUAL = "manual"
    // A group of 2+ photos from a single stitch capture session (see
    // com.voxapps.ipc.VoxOcrRequest.CAPTURE_MODE_STITCH) — every member shares this value.
    // Conceptually one document split across shots, unlike a same-groupId gallery multi-select
    // (which stays MANUAL): the UI only offers whole-group delete for a STITCHED group, never
    // per-photo — see AttachmentUiItem.groupSource and AttachmentsSection's zoom view.
    const val STITCHED = "stitched"
}

/**
 * A photo/document attached to some other app's own record (a note, an expense, a calendar entry).
 * [recordType]/[recordId] scope rows within that app's own table — this entity carries no reference
 * to what the record actually is, only the caller-supplied type tag + numeric id from that app's own
 * Room database.
 *
 * [fileName] is just a filename, resolved by the caller against its own `filesDir/attachments/`
 * (see [AttachmentFileStore]) — this module never owns a FileProvider itself, since every app already
 * has its own authority and `file_paths.xml`.
 *
 * Lives in :core:attachments rather than each app's own data layer so the entity/DAO is defined once
 * — mirrors :core:ipc's PendingLlmRequestEntity/Dao. This module has no `@Database` of its own; each
 * consuming app's own `@Database` lists `AttachmentEntity::class` directly and gets its own physical
 * table — there is no cross-process shared storage here.
 */
@Entity(tableName = "attachments", indices = [Index(value = ["recordType", "recordId"])])
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recordType") val recordType: String,
    @ColumnInfo(name = "recordId") val recordId: Long,
    val fileName: String,
    val source: String,
    val createdAt: Long,
    // A UUID shared by every photo captured/picked in the same burst/selection — null (the default,
    // every pre-existing row) means "a group of one", same as any freshly-added single attachment.
    // [groupOrder] is only meaningful when this is non-null: stable capture/selection position within
    // the group, since createdAt alone can collide within the same millisecond for a fast burst.
    val groupId: String? = null,
    val groupOrder: Int = 0
)
