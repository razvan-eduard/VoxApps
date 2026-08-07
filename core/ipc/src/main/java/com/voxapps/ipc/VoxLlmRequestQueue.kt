package com.voxapps.ipc

import android.content.Context
import android.content.Intent
import java.util.UUID

/**
 * Wraps a raw `ACTION_LLM_PROCESS` broadcast with durable, retryable delivery: [enqueueAndSend]
 * persists the request before attempting to send it, so a broadcast Android silently drops is
 * recovered by a later [retryStale] pass instead of vanishing outright.
 *
 * `FLAG_INCLUDE_STOPPED_PACKAGES` (set below, and on [VoxAppsDiscovery.ping]) does handle the
 * stopped-app case — measured, see that function. What it cannot cover is the rest of why a
 * fire-and-forget broadcast goes unanswered: there is no delivery confirmation at all, the target
 * may be mid-reinstall, or its process may die after receiving the request but before replying.
 * Those are what the queue exists for; the flag narrows the window rather than closing it. A fresh
 * request id rides along as a new trailing segment of [VoxLlmRequest.task] — [VoxLlmResult.task]
 * already round-trips its input verbatim (Commander never interprets it), so this needs no changes
 * on Commander's side. Callers extract that trailing segment from a reply via [splitRequestId] before
 * feeding the (now un-tagged) task string into their existing parsing, then call [markFulfilled].
 *
 * [sender] defaults to a real broadcast but is an injectable seam — the queue's actual bookkeeping
 * (what gets persisted, which rows count as stale, when a row is removed) is unit-testable against a
 * mocked [PendingLlmRequestDao] and a no-op [sender] without needing Robolectric just to construct an
 * [Intent], matching this codebase's existing convention of not unit-testing thin Android-glue code.
 */
class VoxLlmRequestQueue(
    private val dao: PendingLlmRequestDao,
    private val sender: (Context, VoxLlmRequest, String) -> Unit = ::defaultSend
) {

    suspend fun enqueueAndSend(
        context: Context,
        sourcePackage: String,
        task: String,
        promptText: String,
        targetPackage: String,
        data: List<String> = emptyList(),
        attachmentUri: String? = null
    ): String {
        val requestId = UUID.randomUUID().toString()
        val request = VoxLlmRequest(
            sourcePackage = sourcePackage,
            task = "$task:$requestId",
            promptText = promptText,
            data = data,
            attachmentUri = attachmentUri
        )
        val now = System.currentTimeMillis()
        dao.insert(
            PendingLlmRequestEntity(
                requestId = requestId,
                payloadJson = request.toJson(),
                targetPackage = targetPackage,
                createdAt = now,
                attemptCount = 1,
                lastAttemptAt = now
            )
        )
        sender(context, request, targetPackage)
        return requestId
    }

    /** Deletes the pending row once a reply has been received for [requestId] — called regardless of
     *  whether Commander's reply was success or error, since either is a *definitive* answer, not a
     *  delivery failure; only a missing reply should ever be retried. Idempotent: a requestId with no
     *  matching row (already removed by a retry race, or never tracked) is a silent no-op. */
    suspend fun markFulfilled(requestId: String) {
        dao.deleteByRequestId(requestId)
    }

    /** Re-dispatches every row whose last attempt is older than [staleAfterMillis] and hasn't yet hit
     *  [maxAttempts] — called from each app's periodic retry worker. Rows that exhaust [maxAttempts]
     *  are left in place rather than deleted, so they stay inspectable instead of silently vanishing
     *  a second time. */
    suspend fun retryStale(
        context: Context,
        staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
    ) {
        val threshold = System.currentTimeMillis() - staleAfterMillis
        for (entry in dao.getStale(threshold, maxAttempts)) {
            val request = VoxLlmRequest.fromJson(entry.payloadJson) ?: continue
            dao.incrementAttempt(entry.requestId, System.currentTimeMillis())
            sender(context, request, entry.targetPackage)
        }
    }

    companion object {
        /** How long a row may sit unanswered before the periodic worker re-dispatches it. */
        val DEFAULT_STALE_AFTER_MILLIS: Long = java.util.concurrent.TimeUnit.MINUTES.toMillis(5)

        /** At the workers' 15-minute cadence this is roughly 12.5 hours of retrying before a row is
         *  left dormant — not deleted, so it stays inspectable — rather than retried forever.
         *
         *  Defaults live here rather than in each app's worker because they are policy of *this*
         *  queue: all three satellites had identical private copies, so a change to the retry budget
         *  had to be made in three places or silently diverge. */
        const val DEFAULT_MAX_ATTEMPTS = 50

        private fun defaultSend(context: Context, request: VoxLlmRequest, targetPackage: String) {
            context.sendBroadcast(
                Intent(VoxIpc.ACTION_LLM_PROCESS)
                    .setPackage(targetPackage)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(VoxIpc.EXTRA_LLM_PAYLOAD, request.toJson())
            )
        }

        private val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /** Splits the trailing requestId segment [enqueueAndSend] appended off of a replied
         *  [VoxLlmResult.task], returning the original (un-tagged) task string alongside it — pass
         *  the first component wherever existing code expects the task string it originally sent. A
         *  task that doesn't end in a UUID segment (predates this convention, or was never routed
         *  through the queue) is returned unchanged with a null requestId rather than mis-split. */
        fun splitRequestId(taggedTask: String): Pair<String, String?> {
            val idx = taggedTask.lastIndexOf(':')
            if (idx < 0) return taggedTask to null
            val candidate = taggedTask.substring(idx + 1)
            if (!UUID_REGEX.matches(candidate)) return taggedTask to null
            return taggedTask.substring(0, idx) to candidate
        }
    }
}
