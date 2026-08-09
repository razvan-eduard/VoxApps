package com.voxapps.expenses.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.voxapps.expenses.domain.llm.ExpenseParseResultParser
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A snapshot of what [com.voxapps.expenses.receiver.LlmResultReceiver]'s EXPENSE_LINEITEMS_RESCAN
 * reply found for an already-saved expense — NOT applied automatically to any field, including line
 * items (an earlier version wrote items directly; that made a currently-open ExpenseEditScreen look
 * "stale" after a rescan, since its line-items list is a one-time local snapshot that never observes
 * the database — see git history for the bug report). [com.voxapps.expenses.ui.ExpenseEditScreen]
 * observes this row live and, wherever a field differs, shows a tappable suggestion instead. One row
 * per expense (upserted on every rescan, replacing any prior suggestion), cleared once the expense is
 * saved so a stale scan never lingers past that point. [category] is the raw parsed category NAME,
 * not an id — resolved against the live category list at display time (existing categories only, no
 * auto-create, unlike the voice/scan-create paths). [itemsJson] holds the rescanned line items (see
 * [PendingLineItemsJson]) — a full list, not a diff, since there's no meaningful per-item comparison
 * against whatever's currently in the draft.
 */
@Entity(tableName = "pending_field_suggestions")
data class PendingFieldSuggestion(
    @PrimaryKey val expenseId: Long,
    val title: String? = null,
    val vendor: String? = null,
    val bank: String? = null,
    val totalAmount: Double? = null,
    val currencyCode: String? = null,
    val category: String? = null,
    val location: String? = null,
    val dateTime: Long? = null,
    val itemsJson: String? = null,
    // The attachment group (see AttachmentEntity.groupId) whose rescan produced this suggestion, if
    // any — null for a rescan of a single ungrouped attachment, or a suggestion from a non-scan
    // source. Lets dismissing the line-items suggestion also remove the scan that produced it (see
    // ExpenseEditScreen's items-suggestion dismiss) instead of leaving those photos permanently
    // attached with nothing left to apply their suggestion from.
    val sourceGroupId: String? = null
)

/** Hand-rolled org.json (de)serialization for [PendingFieldSuggestion.itemsJson] — matches this
 *  codebase's existing convention (no Gson/kotlinx.serialization anywhere), same shape
 *  [ExpenseParseResultParser.parse] already reads from Commander's reply. */
object PendingLineItemsJson {
    fun encode(items: List<ExpenseParseResultParser.ParsedItem>): String? {
        if (items.isEmpty()) return null
        val array = JSONArray()
        items.forEach { item ->
            val o = JSONObject()
            o.put("name", item.name)
            o.put("quantity", item.quantity)
            o.put("unitPrice", item.unitPrice)
            item.netAmount?.let { o.put("netAmount", it) }
            item.vatAmount?.let { o.put("vatAmount", it) }
            item.grossAmount?.let { o.put("grossAmount", it) }
            array.put(o)
        }
        return array.toString()
    }

    fun decode(json: String?): List<ExpenseParseResultParser.ParsedItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                ExpenseParseResultParser.ParsedItem(
                    name = o.getString("name"),
                    quantity = o.getDouble("quantity"),
                    unitPrice = o.getDouble("unitPrice"),
                    netAmount = if (o.has("netAmount")) o.getDouble("netAmount") else null,
                    vatAmount = if (o.has("vatAmount")) o.getDouble("vatAmount") else null,
                    grossAmount = if (o.has("grossAmount")) o.getDouble("grossAmount") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

@Dao
interface PendingFieldSuggestionDao {
    @Query("SELECT * FROM pending_field_suggestions WHERE expenseId = :expenseId")
    fun observe(expenseId: Long): Flow<PendingFieldSuggestion?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(suggestion: PendingFieldSuggestion)

    @Query("DELETE FROM pending_field_suggestions WHERE expenseId = :expenseId")
    suspend fun clear(expenseId: Long)
}
