package com.voxapps.expenses.data

import com.voxapps.expenses.domain.llm.ExpenseParseResultParser
import org.json.JSONArray
import org.json.JSONObject

/** Hand-rolled org.json (de)serialization for the items a rescan proposes — matches this
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
