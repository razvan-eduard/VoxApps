package com.voxapps.expenses.domain.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.expenses.data.preferences.DataStoreProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** One notification-derived expense awaiting review — never created until the user approves it. */
data class PendingNotificationExpense(
    val id: Long,
    val title: String?,
    /**
     * Null where the message announced a payment without saying how much — "Plata acceptata" and
     * its kind. The entry is still worth keeping: everything else about it is known, and the one
     * missing figure is the thing a person can supply in a second. An entry cannot be approved
     * until it has one.
     */
    val totalAmount: Double?,
    val currency: String,
    val vendor: String?,
    /**
     * The line this capture would have called the merchant if anything had identified one.
     *
     * A guess, and carried as one — it is the field no list claimed, which in a two-line message is
     * usually the shop and sometimes the boilerplate. It exists so the review screen has something
     * to point at: the screen shows it in the colour that means "asking", and nothing is written
     * from it until a person taps it.
     */
    val vendorCandidate: String? = null,
    /**
     * The accepted name this capture's vendor or bank turned out to be another spelling of.
     *
     * Present instead of an offer to list the candidate: a shop already named needs no second entry,
     * it needs this spelling pointed at the name already there. Accepting one writes an enabled
     * re-map rule, so the rename happens by itself from then on.
     */
    val vendorRenameTo: String? = null,
    val bankRenameTo: String? = null,
    val category: String?,
    val capturedAt: Long,
    /** Set deterministically when the notification came from a starred (banking) source app —
     *  see [com.voxapps.expenses.receiver.PaymentNotificationListenerService]. */
    val bank: String? = null,
    val direction: TransactionDirection = TransactionDirection.OUTGOING,
    /** The notification's template identity — approving this entry confirms the direction against
     *  it; see [TemplateDirectionMemory]. */
    val templateHash: String? = null,
    /**
     * The platform withheld this capture's body (its code-protection guard), so the figure was
     * never delivered — but the shade still renders the notification whole. An entry carrying this
     * is recoverable from the screen itself, which is why its source notification is deliberately
     * NOT dismissed: the shade copy is the last complete record of the payment.
     */
    val redactedStub: Boolean = false,
    /** The source notification's [android.service.notification.StatusBarNotification.getKey] —
     *  kept so a recovered stub can finally dismiss the notification it came from. */
    val sourceKey: String? = null
) {
    /** The spelling this entry carries for the merchant: the one that was resolved, or the one being
     *  asked about. What a rename renames. */
    fun vendorSpelling(): String? = vendor?.takeIf { it.isNotBlank() } ?: vendorCandidate
}

/**
 * Holds *pending* expenses captured from payment-app notifications, awaiting individual user review
 * (mirrors the pending-review pattern of [PendingCategoryMergeRepository]/
 * [ExpenseDeduplicationRepository], but each entry is reviewed/approved on its own — there's no
 * "group" concept here). This is the strictest-gated of the three input channels: nothing here was
 * ever a deliberate user action, so nothing is ever auto-created.
 */
class PendingNotificationExpenseRepository(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val PENDING_ENTRIES = stringPreferencesKey("pending_notification_expenses")
    }

    val pendingFlow: Flow<List<PendingNotificationExpense>> = dataStore.data.map { prefs ->
        prefs[Keys.PENDING_ENTRIES]?.let { decode(it) } ?: emptyList()
    }

    suspend fun addPending(entry: PendingNotificationExpense) {
        dataStore.edit {
            val current = it[Keys.PENDING_ENTRIES]?.let { json -> decode(json) } ?: emptyList()
            it[Keys.PENDING_ENTRIES] = encode(current + entry)
        }
    }

    /** Replaces the entry with the same id — how a recovered stub takes its figure. */
    suspend fun updatePending(entry: PendingNotificationExpense) {
        dataStore.edit {
            val current = it[Keys.PENDING_ENTRIES]?.let { json -> decode(json) } ?: emptyList()
            it[Keys.PENDING_ENTRIES] = encode(current.map { e -> if (e.id == entry.id) entry else e })
        }
    }

    /** Removes one or more entries by id — used for both "approve" (caller creates the Expense
     *  first) and "dismiss" (caller just discards), since the pending-list bookkeeping is identical. */
    suspend fun removePending(ids: Set<Long>) {
        dataStore.edit {
            val current = it[Keys.PENDING_ENTRIES]?.let { json -> decode(json) } ?: emptyList()
            it[Keys.PENDING_ENTRIES] = encode(current.filter { entry -> entry.id !in ids })
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.remove(Keys.PENDING_ENTRIES) }
    }

    suspend fun snapshot(): List<PendingNotificationExpense> = pendingFlow.first()

    private fun encode(entries: List<PendingNotificationExpense>): String {
        val array = JSONArray()
        for (e in entries) {
            val o = JSONObject()
            o.put("id", e.id)
            e.title?.let { o.put("title", it) }
            e.totalAmount?.let { o.put("totalAmount", it) }
            o.put("currency", e.currency)
            e.vendor?.let { o.put("vendor", it) }
            e.vendorCandidate?.let { o.put("vendorCandidate", it) }
            e.vendorRenameTo?.let { o.put("vendorRenameTo", it) }
            e.bankRenameTo?.let { o.put("bankRenameTo", it) }
            e.category?.let { o.put("category", it) }
            e.bank?.let { o.put("bank", it) }
            o.put("direction", e.direction.toJsonValue())
            o.put("capturedAt", e.capturedAt)
            e.templateHash?.let { o.put("templateHash", it) }
            if (e.redactedStub) o.put("redactedStub", true)
            e.sourceKey?.let { o.put("sourceKey", it) }
            array.put(o)
        }
        return array.toString()
    }

    private fun decode(json: String): List<PendingNotificationExpense> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            PendingNotificationExpense(
                id = o.optLong("id"),
                title = if (o.has("title")) o.optString("title") else null,
                // Absent for an amountless capture; NaN for an entry written before this was
                // nullable, since optDouble's own default is NaN rather than a missing key.
                totalAmount = o.optDouble("totalAmount").takeIf { !it.isNaN() },
                currency = o.optString("currency"),
                vendor = if (o.has("vendor")) o.optString("vendor") else null,
                vendorCandidate = if (o.has("vendorCandidate")) o.optString("vendorCandidate") else null,
                vendorRenameTo = if (o.has("vendorRenameTo")) o.optString("vendorRenameTo") else null,
                bankRenameTo = if (o.has("bankRenameTo")) o.optString("bankRenameTo") else null,
                category = if (o.has("category")) o.optString("category") else null,
                bank = if (o.has("bank")) o.optString("bank") else null,
                direction = o.optTransactionDirection(),
                capturedAt = o.optLong("capturedAt"),
                templateHash = if (o.has("templateHash")) o.optString("templateHash") else null,
                redactedStub = o.optBoolean("redactedStub", false),
                sourceKey = if (o.has("sourceKey")) o.optString("sourceKey") else null
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
