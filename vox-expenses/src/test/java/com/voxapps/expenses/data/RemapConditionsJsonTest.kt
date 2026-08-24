package com.voxapps.expenses.data

import com.voxapps.datahygiene.RemapCondition
import com.voxapps.datahygiene.RemapOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What a stored trigger says, across the shapes rules have been written in. */
class RemapConditionsJsonTest {

    @Test
    fun `groups round-trip`() {
        val groups = listOf(
            listOf(RemapCondition("vendor", "lidl"), RemapCondition("location", "cluj")),
            listOf(RemapCondition("vendor", "carrefour"))
        )
        val decoded = RemapConditionsJson.decode(RemapConditionsJson.encode(groups))
        assertEquals(2, decoded.size)
        assertEquals(setOf("lidl", "carrefour", "cluj"), decoded.flatten().map { it.value }.toSet())
    }

    @Test
    fun `one group of one reads back as itself`() {
        val one = listOf(listOf(RemapCondition("vendor", "lazar ionut pfa")))
        val json = RemapConditionsJson.encode(one)
        assertEquals(listOf("vendor" to "lazar ionut pfa"),
            RemapConditionsJson.decode(json).flatten().map { it.fieldId to it.value })
    }

    /** Rules written before a field could repeat: a flat object, everything required. */
    @Test
    fun `the original flat shape reads as one group`() {
        val decoded = RemapConditionsJson.decode("""{"vendor":"lidl","location":"cluj"}""")
        assertEquals(1, decoded.size)
        assertEquals(setOf("vendor", "location"), decoded.first().map { it.fieldId }.toSet())
    }

    @Test
    fun `the separate fuzz column still answers for a legacy rule`() {
        val decoded = RemapConditionsJson.decode("""{"vendor":"shell"}""", mapOf("vendor" to 2))
        assertEquals(2, decoded.first().first().fuzz)
    }

    @Test
    fun `nothing stored is no conditions`() {
        assertEquals(emptyList<List<RemapCondition>>(), RemapConditionsJson.decode(null))
        assertEquals(emptyList<List<RemapCondition>>(), RemapConditionsJson.decode("not json"))
    }

    @Test
    fun `a comparison survives the round trip`() {
        val json = RemapConditionsJson.encode(
            listOf(listOf(RemapCondition("totalAmount", "10000", op = RemapOp.GT)))
        )
        val decoded = RemapConditionsJson.decode(json).first().first()
        assertEquals(RemapOp.GT, decoded.op)
        assertEquals("10000", decoded.value)
    }

    /** Every rule written before comparisons existed asked whether a field *is* something. */
    @Test
    fun `a stored condition with no operator is equality`() {
        assertEquals(RemapOp.EQ, RemapConditionsJson.decode("""[[{"field":"vendor","value":"lidl"}]]""").first().first().op)
        assertEquals(RemapOp.EQ, RemapConditionsJson.decode("""{"vendor":"lidl"}""").first().first().op)
    }

    @Test
    fun `two triggers differing only in their operator are different triggers`() {
        val over = RemapConditionsJson.encode(listOf(listOf(RemapCondition("totalAmount", "10000", op = RemapOp.GT))))
        val under = RemapConditionsJson.encode(listOf(listOf(RemapCondition("totalAmount", "10000", op = RemapOp.LT))))
        assertNotEquals(over, under)
    }

    @Test
    fun `the alert effect reaches the engine`() {
        val entity = RemapRuleEntity(
            id = 3, name = "big", matchJson = RemapConditionsJson.encode(
                listOf(listOf(RemapCondition("totalAmount", "10000", op = RemapOp.GTE)))
            ),
            setJson = "{}", origin = RemapRuleEntity.ORIGIN_USER, updatedAt = 0L, alertEnabled = true
        )
        val rule = entity.toRemapRule()
        assertEquals(true, rule.alert)
        assertEquals(RemapOp.GTE, rule.trigger.groups.first().first().op)
    }
}
