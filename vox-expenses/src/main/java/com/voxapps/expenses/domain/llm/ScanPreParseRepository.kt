package com.voxapps.expenses.domain.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voxapps.expenses.data.preferences.DataStoreProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a scan's raw OCR text yielded deterministically before its text was sent for structuring —
 * see [DateTimeRegexParser] and [ReceiptTotalRegexParser].
 */
data class ScanPreParse(
    val date: String? = null,
    val time: String? = null,
    val total: Double? = null,
    /** Notification captures resolve a vendor deterministically too — see NotificationPreParse. */
    val vendor: String? = null,
    /** A direction inherited from the template memory ("outgoing"/"incoming"), suppressed from
     *  the prompt the same way — see TemplateDirectionMemory. */
    val direction: String? = null,
    /** The notification's template identity, carried so the reply side can seed the memory's
     *  record links — transport, not a resolved field. */
    val templateHash: String? = null,
    /** True when the template is confirmed to produce real transactions — the reply is then
     *  parsed without the isPayment gate, which was suppressed from the prompt. */
    val isPaymentKnown: Boolean = false,
    /** Deterministically-read line items (see [TableItemsPreParse]) as its compact JSON. */
    val itemsJson: String? = null,
    /** An invoice's carried balance and own-charges figures (see [ReceiptTotalRegexParser]). */
    val previousBalance: Double? = null,
    val invoiceOwnTotal: Double? = null,
    val storedAt: Long = 0L
) {
    val isEmpty: Boolean
        get() = date == null && time == null && total == null && vendor == null &&
            direction == null && templateHash == null && itemsJson == null &&
            previousBalance == null && invoiceOwnTotal == null
}

/**
 * Carries a scan's deterministically-extracted fields across the request/reply gap, keyed by the
 * request id [com.voxapps.ipc.VoxLlmRequestQueue] mints for the outbound broadcast.
 *
 * The gap is why this exists: the values are derived where the prompt is built and are needed where
 * the record is written, which is a different process wake-up with only the reply to go on. A field
 * the prompt suppressed — because it is already known — is absent from that reply by design, so
 * without somewhere to hold it in the meantime the value is asked for once, answered once, and then
 * lost, leaving the record to fall back as though nothing had been found.
 *
 * Entries are consumed on use. [prune] bounds what an unanswered request can leave behind, since a
 * reply that never arrives would otherwise keep its entry forever.
 */
class ScanPreParseRepository(context: Context) {

    private val dataStore = DataStoreProvider.get(context)

    private object Keys {
        val ENTRIES = stringPreferencesKey("scan_pre_parse_entries")
    }

    companion object {
        /** Past this age an entry is assumed orphaned by a reply that never came. */
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }

    suspend fun put(requestId: String, preParse: ScanPreParse) {
        if (preParse.isEmpty) return
        dataStore.edit { prefs ->
            val current = decode(prefs[Keys.ENTRIES]).toMutableMap()
            current[requestId] = preParse.copy(storedAt = System.currentTimeMillis())
            prefs[Keys.ENTRIES] = encode(prune(current))
        }
    }

    /** Reads an entry and drops it: a reply is answered once, so holding it past that only risks
     *  applying it to an unrelated later request that happens to reuse the id. */
    suspend fun take(requestId: String?): ScanPreParse? {
        if (requestId == null) return null
        val current = decode(dataStore.data.map { it[Keys.ENTRIES] }.first())
        val entry = current[requestId] ?: return null
        dataStore.edit { prefs ->
            val map = decode(prefs[Keys.ENTRIES]).toMutableMap()
            map.remove(requestId)
            prefs[Keys.ENTRIES] = encode(map)
        }
        return entry
    }

    private fun prune(entries: Map<String, ScanPreParse>): Map<String, ScanPreParse> {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        return entries.filterValues { it.storedAt >= cutoff }
    }

    private fun encode(entries: Map<String, ScanPreParse>): String {
        val array = JSONArray()
        for ((requestId, entry) in entries) {
            val o = JSONObject()
            o.put("requestId", requestId)
            entry.date?.let { o.put("date", it) }
            entry.time?.let { o.put("time", it) }
            entry.total?.let { o.put("total", it) }
            entry.vendor?.let { o.put("vendor", it) }
            entry.direction?.let { o.put("direction", it) }
            entry.templateHash?.let { o.put("templateHash", it) }
            if (entry.isPaymentKnown) o.put("isPaymentKnown", true)
            entry.itemsJson?.let { o.put("itemsJson", it) }
            entry.previousBalance?.let { o.put("previousBalance", it) }
            entry.invoiceOwnTotal?.let { o.put("invoiceOwnTotal", it) }
            o.put("storedAt", entry.storedAt)
            array.put(o)
        }
        return array.toString()
    }

    private fun decode(json: String?): Map<String, ScanPreParse> = try {
        if (json.isNullOrBlank()) emptyMap() else {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val requestId = o.optString("requestId").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                requestId to ScanPreParse(
                    date = if (o.has("date")) o.optString("date") else null,
                    time = if (o.has("time")) o.optString("time") else null,
                    total = if (o.has("total")) o.optDouble("total") else null,
                    vendor = if (o.has("vendor")) o.optString("vendor") else null,
                    direction = if (o.has("direction")) o.optString("direction") else null,
                    templateHash = if (o.has("templateHash")) o.optString("templateHash") else null,
                    isPaymentKnown = o.optBoolean("isPaymentKnown", false),
                    itemsJson = if (o.has("itemsJson")) o.optString("itemsJson") else null,
                    previousBalance = if (o.has("previousBalance")) o.optDouble("previousBalance") else null,
                    invoiceOwnTotal = if (o.has("invoiceOwnTotal")) o.optDouble("invoiceOwnTotal") else null,
                    storedAt = o.optLong("storedAt")
                )
            }.toMap()
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
