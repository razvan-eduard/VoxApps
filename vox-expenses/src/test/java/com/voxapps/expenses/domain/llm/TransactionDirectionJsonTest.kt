package com.voxapps.expenses.domain.llm

import com.voxapps.expenses.data.TransactionDirection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionDirectionJsonTest {

    @Test
    fun `missing key defaults to outgoing`() {
        assertEquals(TransactionDirection.OUTGOING, JSONObject("{}").optTransactionDirection())
    }

    @Test
    fun `incoming parses case-insensitively`() {
        assertEquals(TransactionDirection.INCOMING, JSONObject("""{"direction":"INCOMING"}""").optTransactionDirection())
        assertEquals(TransactionDirection.INCOMING, JSONObject("""{"direction":"incoming"}""").optTransactionDirection())
    }

    @Test
    fun `any other value defaults to outgoing`() {
        assertEquals(TransactionDirection.OUTGOING, JSONObject("""{"direction":"outgoing"}""").optTransactionDirection())
        assertEquals(TransactionDirection.OUTGOING, JSONObject("""{"direction":"sideways"}""").optTransactionDirection())
    }

    @Test
    fun `a null value defaults to outgoing`() {
        assertEquals(TransactionDirection.OUTGOING, JSONObject("""{"direction":null}""").optTransactionDirection())
    }

    @Test
    fun `toJsonValue round-trips through optTransactionDirection`() {
        assertEquals(TransactionDirection.INCOMING, JSONObject().put("direction", TransactionDirection.INCOMING.toJsonValue()).optTransactionDirection())
        assertEquals(TransactionDirection.OUTGOING, JSONObject().put("direction", TransactionDirection.OUTGOING.toJsonValue()).optTransactionDirection())
    }
}
