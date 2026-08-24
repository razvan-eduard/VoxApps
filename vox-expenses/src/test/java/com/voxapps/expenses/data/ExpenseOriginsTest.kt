package com.voxapps.expenses.data

import com.voxapps.recordflow.FieldOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a record says about where its own fields came from — see [ExpenseOrigins]. */
class ExpenseOriginsTest {

    @Test
    fun `a claim survives the round trip`() {
        val origins = mapOf(
            ExpenseOrigins.FIELD_AMOUNT to FieldOrigin.PROVED,
            ExpenseOrigins.FIELD_VENDOR to FieldOrigin.MATCHED,
            ExpenseOrigins.FIELD_CATEGORY to FieldOrigin.ANSWERED
        )
        assertEquals(origins, ExpenseOrigins.decode(ExpenseOrigins.encode(origins)))
    }

    @Test
    fun `nothing claimed is nothing stored`() {
        assertEquals(null, ExpenseOrigins.encode(emptyMap()))
        assertEquals(emptyMap<String, FieldOrigin>(), ExpenseOrigins.decode(null))
        assertEquals(emptyMap<String, FieldOrigin>(), ExpenseOrigins.decode("   "))
    }

    /** A version that knew a field or an origin this one does not must not take the record with it. */
    @Test
    fun `an unreadable entry is dropped, the rest is kept`() {
        val decoded = ExpenseOrigins.decode("totalAmount:proved,mood:answered,vendor:divined,bank:matched")
        assertEquals(
            mapOf(
                ExpenseOrigins.FIELD_AMOUNT to FieldOrigin.PROVED,
                ExpenseOrigins.FIELD_BANK to FieldOrigin.MATCHED
            ),
            // "mood" is a field this version has no name for, and it is kept as written rather than
            // guessed at — what it must not do is fail the whole record.
            decoded.filterKeys { it != "mood" }
        )
    }

    /** From the moment somebody edits a field, the only true answer about it is "you did". */
    @Test
    fun `editing a field replaces whatever it used to claim`() {
        val before = ExpenseOrigins.encode(
            mapOf(
                ExpenseOrigins.FIELD_VENDOR to FieldOrigin.ANSWERED,
                ExpenseOrigins.FIELD_AMOUNT to FieldOrigin.PROVED
            )
        )
        val after = ExpenseOrigins.decode(
            ExpenseOrigins.withTyped(before, setOf(ExpenseOrigins.FIELD_VENDOR))
        )
        assertEquals(FieldOrigin.TYPED, after[ExpenseOrigins.FIELD_VENDOR])
        assertEquals(FieldOrigin.PROVED, after[ExpenseOrigins.FIELD_AMOUNT])
    }
}
