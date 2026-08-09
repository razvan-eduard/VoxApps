package com.voxapps.attachments

import org.json.JSONArray
import org.json.JSONObject

/**
 * How an attachment row travels inside a Hub backup payload.
 *
 * Both halves were written out identically in all three satellite export/import handlers — the
 * serialiser character-for-character, the restore loop modulo its record type. Since the shape is a
 * property of [AttachmentEntity] rather than of any one app's backup format, it belongs next to the
 * entity: adding a column now has one JSON site to update instead of three that can silently drift
 * apart.
 *
 * [AttachmentEntity.recordType]/[AttachmentEntity.recordId] are deliberately *not* serialised —
 * they identify the owning record, which on restore is a freshly inserted row with a new local id,
 * so both are supplied by [restoreFromBackup]'s caller instead of being read back.
 */
fun AttachmentEntity.toBackupJson(): JSONObject = JSONObject().apply {
    put(KEY_FILE_NAME, fileName)
    put(KEY_SOURCE, source)
    put(KEY_CREATED_AT, createdAt)
    put(KEY_GROUP_ID, groupId)
    put(KEY_GROUP_ORDER, groupOrder)
}

/**
 * Inserts every attachment in [attachments] against a newly restored record.
 *
 * Entries with a blank [AttachmentEntity.fileName] are skipped rather than inserted: the file name
 * is the only link to the photo on disk, so a row without one can never resolve to anything and
 * would just render as a permanently broken thumbnail.
 *
 * @return how many rows were actually inserted.
 */
suspend fun AttachmentDao.restoreFromBackup(
    recordType: String,
    recordId: Long,
    attachments: JSONArray,
    now: Long = System.currentTimeMillis()
): Int {
    var inserted = 0
    for (i in 0 until attachments.length()) {
        val a = attachments.getJSONObject(i)
        val fileName = a.optString(KEY_FILE_NAME).takeIf { it.isNotBlank() } ?: continue
        insert(
            AttachmentEntity(
                recordType = recordType,
                recordId = recordId,
                fileName = fileName,
                source = a.optString(KEY_SOURCE, AttachmentSource.MANUAL),
                createdAt = a.optLong(KEY_CREATED_AT, now),
                groupId = if (a.has(KEY_GROUP_ID) && !a.isNull(KEY_GROUP_ID)) a.optString(KEY_GROUP_ID) else null,
                groupOrder = a.optInt(KEY_GROUP_ORDER, 0)
            )
        )
        inserted++
    }
    return inserted
}

private const val KEY_FILE_NAME = "fileName"
private const val KEY_SOURCE = "source"
private const val KEY_CREATED_AT = "createdAt"
private const val KEY_GROUP_ID = "groupId"
private const val KEY_GROUP_ORDER = "groupOrder"
