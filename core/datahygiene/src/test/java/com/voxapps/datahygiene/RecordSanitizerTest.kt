package com.voxapps.datahygiene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private data class Dummy(val name: String?)

private object DummySanitizer : RecordSanitizer<Dummy> {
    override fun sanitize(record: Dummy): Dummy = record.copy(name = FieldCleaner.clean(record.name))
    override fun dirtyFields(record: Dummy): List<DirtyField> =
        listOfNotNull(FieldCleaner.dirtyValue(record.name)?.let { DirtyField("name", it) })
}

class RecordSanitizerTest {

    @Test
    fun `LLM source always auto-cleans and proceeds`() {
        val decision = DummySanitizer.decideForSave(Dummy(name = "null"), RecordSource.LLM)
        assertTrue(decision is SaveDecision.Proceed)
        assertEquals(null, (decision as SaveDecision.Proceed).record.name)
    }

    @Test
    fun `Hub import always proceeds untouched, even with garbage`() {
        val decision = DummySanitizer.decideForSave(Dummy(name = "null"), RecordSource.HUB_IMPORT)
        assertTrue(decision is SaveDecision.Proceed)
        assertEquals("null", (decision as SaveDecision.Proceed).record.name)
    }

    @Test
    fun `manual UI with a clean record proceeds without confirmation`() {
        val decision = DummySanitizer.decideForSave(Dummy(name = "Groceries"), RecordSource.MANUAL_UI)
        assertTrue(decision is SaveDecision.Proceed)
        assertEquals("Groceries", (decision as SaveDecision.Proceed).record.name)
    }

    @Test
    fun `manual UI with a dirty record asks for confirmation instead of silently rewriting`() {
        val decision = DummySanitizer.decideForSave(Dummy(name = "."), RecordSource.MANUAL_UI)
        assertTrue(decision is SaveDecision.ConfirmCleanup)
        val confirm = decision as SaveDecision.ConfirmCleanup
        assertEquals(".", confirm.original.name)
        assertEquals(listOf(DirtyField("name", ".")), confirm.dirtyFields)
    }

    @Test
    fun `manual UI with an empty field proceeds without confirmation (empty is normal, not dirty)`() {
        val decision = DummySanitizer.decideForSave(Dummy(name = null), RecordSource.MANUAL_UI)
        assertTrue(decision is SaveDecision.Proceed)
    }
}
