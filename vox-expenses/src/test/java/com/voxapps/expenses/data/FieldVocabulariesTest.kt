package com.voxapps.expenses.data

import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldVocabulariesTest {

    @Test
    fun `overlapping lists are refused`() {
        // "ING" in both lists would make the field-assignment rule ambiguous by construction.
        val bad = VocabulariesSchema(
            legalForms = listOf("SRL", "ING"),
            banks = listOf("ING", "BCR")
        )
        assertFalse(FieldVocabularies.areUsable(bad))
    }

    @Test
    fun `overlap is judged after normalization, not literally`() {
        // "S.R.L." and "srl" are the same token to the classifier, so they must be the same token
        // to the exclusivity check.
        val bad = VocabulariesSchema(
            legalForms = listOf("S.R.L."),
            banks = listOf("srl")
        )
        assertFalse(FieldVocabularies.areUsable(bad))
    }

    @Test
    fun `an empty list is refused`() {
        assertFalse(FieldVocabularies.areUsable(VocabulariesSchema(emptyList(), listOf("ING"))))
        assertFalse(FieldVocabularies.areUsable(VocabulariesSchema(listOf("SRL"), emptyList())))
    }

    @Test
    fun `the shipped schema parses and passes its own invariant`() {
        // The repository copy is the source of truth the signed manifest covers; this is the test
        // that catches an edit which breaks mutual exclusivity before signing ships it.
        val file = listOf(
            File("../remote-schemas/expenses/field_vocabularies.json"),
            File("remote-schemas/expenses/field_vocabularies.json")
        ).firstOrNull { it.exists() }
            ?: error("schema file not found from ${File(".").absolutePath}")
        val parsed = Gson().fromJson(file.readText(), VocabulariesSchema::class.java)
        assertTrue(parsed.legalForms.size > 10)
        assertTrue(parsed.banks.size > 20)
        assertTrue("shipped lists are not mutually exclusive", FieldVocabularies.areUsable(parsed))
    }
}
