package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.TransactionDirection
import org.json.JSONObject

/** Shared by every parser/serializer reading or writing an [Expense][com.voxapps.expenses.data.Expense]'s
 *  [TransactionDirection] as JSON — defaults to [TransactionDirection.OUTGOING] whenever the field is
 *  missing, unparseable, or not "incoming" (matches [TransactionDirection]'s own default, and keeps
 *  every pre-existing payload without this field round-tripping unchanged). */
fun JSONObject.optTransactionDirection(key: String = "direction"): TransactionDirection =
    if (has(key) && !isNull(key) && optString(key).equals("incoming", ignoreCase = true)) {
        TransactionDirection.INCOMING
    } else {
        TransactionDirection.OUTGOING
    }

fun TransactionDirection.toJsonValue(): String = if (this == TransactionDirection.INCOMING) "incoming" else "outgoing"
