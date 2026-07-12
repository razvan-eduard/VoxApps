package com.voxapps.calendarapp.domain.daylink

import android.content.Context
import com.voxapps.ipc.VoxDataTransferClient
import org.json.JSONObject

data class DaySummaryEntry(val title: String, val timeMillis: Long)
data class DaySummary(val count: Int, val items: List<DaySummaryEntry>)

/**
 * Fetches a day-scoped summary from another Vox app via the day-scoped [VoxDataTransferClient.requestDayRead]
 * (reuses the existing OP_READ channel — no new IPC surface). Returns null if the app isn't installed,
 * doesn't reply in time, or is locked (biometric-gated with an expired session) — callers should treat
 * null the same as "nothing to show", not surface a raw error.
 */
object DaySummaryClient {
    private const val NOTES_PACKAGE = "com.voxapps.notes"
    private const val EXPENSES_PACKAGE = "com.voxapps.expenses"

    suspend fun fetchNotes(context: Context, dayFromMillis: Long, dayToMillis: Long): DaySummary? =
        fetch(context, NOTES_PACKAGE, dayFromMillis, dayToMillis)

    suspend fun fetchExpenses(context: Context, dayFromMillis: Long, dayToMillis: Long): DaySummary? =
        fetch(context, EXPENSES_PACKAGE, dayFromMillis, dayToMillis)

    private suspend fun fetch(context: Context, packageName: String, from: Long, to: Long): DaySummary? {
        val result = VoxDataTransferClient.requestDayRead(context, packageName, from, to) ?: return null
        if (!result.ok) return null
        return try {
            val o = JSONObject(result.text)
            val itemsArray = o.optJSONArray("items")
            val items = if (itemsArray != null) {
                (0 until itemsArray.length()).map { i ->
                    val item = itemsArray.getJSONObject(i)
                    DaySummaryEntry(item.optString("title"), item.optLong("timeMillis"))
                }
            } else {
                emptyList()
            }
            DaySummary(o.optInt("count", items.size), items)
        } catch (e: Exception) {
            null
        }
    }
}
