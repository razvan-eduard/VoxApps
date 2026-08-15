package com.voxapps.expenses.domain.llm

import com.voxapps.datahygiene.optCleanString
import com.voxapps.expenses.data.TransactionDirection
import com.voxapps.logging.Logger
import com.voxapps.schema.VoxExtractionSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses Commander's structured reply to an [ExpenseParsePromptBuilder] request. [totalAmount] is
 * the only field required to succeed — an expense can't be saved without one (see `Expense.kt`) — so
 * [parse] returns null (discard, don't create a broken expense) if it's missing or unparseable.
 */
object ExpenseParseResultParser {
    private const val TAG = "ExpenseParseResultParser"

    data class ParsedItem(
        val name: String,
        val quantity: Double,
        val unitPrice: Double,
        val netAmount: Double? = null,
        val vatAmount: Double? = null,
        val grossAmount: Double? = null
    )

    @VoxExtractionSchema(version = 2)
    data class Parsed(
        val title: String?,
        val totalAmount: Double,
        val currency: String?,
        val vendor: String?,
        val bank: String?,
        // Only ever populated by the OCR scan-cleanup task (a receipt's printed store address, city
        // only) — the voice-parse prompt never asks the LLM for this, so it's always null there;
        // LlmResultReceiver falls back to resolveCurrentCityName's GPS-derived city in that case.
        val location: String?,
        val category: String?,
        val date: String?, // YYYY-MM-DD format
        val time: String?, // HH:mm format
        val items: List<ParsedItem>,
        val direction: TransactionDirection = TransactionDirection.OUTGOING
    ) {
        val itemsSumMismatch: Boolean =
            ExpenseAmountMismatch.isGrossMismatch(totalAmount, items.sumOf { it.quantity * it.unitPrice })
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (isNull(key) || !has(key)) return null

        // Try standard optDouble first (handles Int, Double, Long, and clean Strings)
        val d = optDouble(key)
        if (!d.isNaN()) return d

        // Handle noisy strings (e.g. "12,50 lei" or "100.00 RON")
        val value = opt(key)
        if (value is String) {
            // Replace decimal comma with period only if between digits
            val normalized = value.replace(Regex("(\\d),(\\d)"), "$1.$2")
            // Keep only digits, periods, and minus sign
            val digitsOnly = normalized.replace(Regex("[^0-9.-]"), "")
            return digitsOnly.toDoubleOrNull()
        }

        return null
    }

    /**
     * [requireTotalAmount] is only ever false for [LlmTasks.EXPENSE_LINEITEMS_RESCAN] (see
     * [com.voxapps.expenses.receiver.LlmResultReceiver]) — a photo with no clear printed total
     * shouldn't discard genuinely-found line items (or other fields) along with it. [Double.NaN] is
     * a distinguishable "not found" sentinel in that case (never a real amount) — callers that care
     * check `!totalAmount.isNaN()` rather than treating it as a real, suggestible value.
     */
    /** The first balanced top-level JSON object inside [raw], or null when none completes. Local
     *  models habitually wrap their reply in markdown fences or lead-in prose, and JSONObject()
     *  rejects the whole reply over it — so the object is cut out before parsing. Brace depth is
     *  tracked outside string literals only, so braces inside values can't end the object early. */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun parse(json: String, requireTotalAmount: Boolean = true): Parsed? = try {
        val extracted = extractJsonObject(json)
        if (extracted == null) {
            Logger.w(
                TAG,
                "No complete JSON object in LLM reply (${json.length} chars) — " +
                    "head: ${json.take(160)} | tail: ${json.takeLast(160)}"
            )
            return null
        }
        val o = JSONObject(extracted)
        val totalAmount = o.optNullableDouble("totalAmount")
            ?: (if (requireTotalAmount) {
                Logger.w(TAG, "LLM reply parsed but has no usable totalAmount — rejecting")
                return null
            } else Double.NaN)

        val itemsArray = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until itemsArray.length()).mapNotNull { i ->
            val item = itemsArray.optJSONObject(i)
            if (item == null) {
                Logger.w(TAG, "Dropping items[$i]: not a JSON object")
                return@mapNotNull null
            }
            val name = item.optCleanString("name")
            if (name == null) {
                Logger.w(TAG, "Dropping items[$i]: missing/blank \"name\" ($item)")
                return@mapNotNull null
            }
            val quantity = item.optNullableDouble("quantity") ?: 1.0
            val unitPrice = item.optNullableDouble("unitPrice")
            if (unitPrice == null) {
                Logger.w(TAG, "Dropping items[$i] \"$name\": missing/unparseable \"unitPrice\" ($item)")
                return@mapNotNull null
            }
            ParsedItem(
                name = name,
                quantity = quantity,
                unitPrice = unitPrice,
                netAmount = item.optNullableDouble("netAmount"),
                vatAmount = item.optNullableDouble("vatAmount"),
                grossAmount = item.optNullableDouble("grossAmount")
            )
        }

        Parsed(
            title = o.optCleanString("title"),
            totalAmount = totalAmount,
            currency = o.optCleanString("currency"),
            vendor = o.optCleanString("vendor"),
            bank = o.optCleanString("bank"),
            location = o.optCleanString("location"),
            category = o.optCleanString("category"),
            date = o.optCleanString("date"),
            time = o.optCleanString("time"),
            items = items,
            direction = o.optTransactionDirection()
        )
    } catch (e: Exception) {
        Logger.w(
            TAG,
            "Unparseable LLM reply (${json.length} chars, ${e.message}) — " +
                "head: ${json.take(160)} | tail: ${json.takeLast(160)}"
        )
        null
    }
}
