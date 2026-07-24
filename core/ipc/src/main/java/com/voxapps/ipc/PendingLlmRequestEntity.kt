package com.voxapps.ipc

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A durable record of an outbound [VoxLlmRequest] broadcast, kept until [VoxLlmRequestQueue] sees a
 * matching [VoxLlmResult] reply (see [VoxLlmRequestQueue.markFulfilled]) — the row surviving is what
 * lets a periodic worker re-send a request that a fire-and-forget broadcast silently dropped (target
 * app stopped/killed, briefly uninstalled, ...) instead of losing it outright. [payloadJson] is
 * [VoxLlmRequest.toJson], sufflicient to fully reconstruct and resend the request.
 *
 * Lives in :core:ipc rather than each app's own data layer so the entity/DAO/queue logic is defined
 * once; each app's own Room `@Database` still owns its own physical table — there is no cross-process
 * shared storage here, `entities = [..., PendingLlmRequestEntity::class]` just points every app's
 * database at the same entity/column definitions.
 */
@Entity(tableName = "pending_llm_requests")
data class PendingLlmRequestEntity(
    @PrimaryKey val requestId: String,
    val payloadJson: String,
    val targetPackage: String,
    val createdAt: Long,
    val attemptCount: Int,
    val lastAttemptAt: Long
)
