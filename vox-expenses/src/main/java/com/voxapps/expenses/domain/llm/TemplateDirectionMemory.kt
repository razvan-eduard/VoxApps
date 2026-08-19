package com.voxapps.expenses.domain.llm

import android.content.Context
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.DataStoreProvider
import com.voxapps.fieldmemory.TemplateVerdictMemory

/**
 * What a human has said this app's notification templates mean.
 *
 * The rules — two unanimous confirmations before a template answers, permanent quarantine on the
 * first disagreement, nothing ever learned from the model's own output — are not this app's and
 * live in [TemplateVerdictMemory]. What is this app's is the *meaning*: the thing a person is
 * confirming here is which way the money went, and no other satellite has that question.
 *
 * So this is the adaptation and nothing else. It points the shared memory at this app's own
 * DataStore, under the keys it has always used, so every template already taught keeps answering;
 * and it turns the memory's opaque verdict into [TransactionDirection] and back.
 */
class TemplateDirectionMemory(context: Context) {

    private val memory = TemplateVerdictMemory(DataStoreProvider.get(context))

    /** One learned template as the settings screen shows it. */
    data class TemplateView(
        val hash: String,
        val skeleton: String?,
        val direction: String,
        val confirmations: Int,
        val conflicted: Boolean,
        val answersDirection: Boolean
    )

    /** The inherited direction for [templateHash], or null when the memory declines. */
    suspend fun lookup(templateHash: String?): TransactionDirection? =
        memory.lookup(templateHash)?.let {
            if (it.equals("incoming", ignoreCase = true)) TransactionDirection.INCOMING
            else TransactionDirection.OUTGOING
        }

    /** Whether [templateHash] is known to produce real transactions — see the shared memory for why
     *  the same counter answers this and why nothing ever teaches the negative. */
    suspend fun lookupIsPayment(templateHash: String?): Boolean = memory.lookupIsPayment(templateHash)

    /** A human confirmed [direction] for [templateHash]. */
    suspend fun confirm(templateHash: String?, direction: TransactionDirection) =
        memory.confirm(templateHash, direction.toJsonValue())

    suspend fun noteSkeleton(templateHash: String?, skeleton: String) =
        memory.noteSkeleton(templateHash, skeleton)

    suspend fun snapshot(): List<TemplateView> = memory.snapshot().map {
        TemplateView(
            hash = it.hash,
            skeleton = it.skeleton,
            direction = it.verdict,
            confirmations = it.confirmations,
            conflicted = it.conflicted,
            answersDirection = it.answersVerdict
        )
    }

    suspend fun forget(templateHash: String) = memory.forget(templateHash)

    suspend fun reteach(templateHash: String) = memory.reteach(templateHash)

    suspend fun linkRecord(recordId: Long, templateHash: String) =
        memory.linkRecord(recordId, templateHash)

    suspend fun consumeLink(recordId: Long): String? = memory.consumeLink(recordId)
}
