package com.voxapps.docread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A published template file extends the compiled-in battery; it does not stand in for it.
 *
 * The distinction is invisible until a pattern is added here: with substitution, an install that has
 * ever fetched a file reads exactly what that file describes and nothing else, so a shape the library
 * itself learned to read reaches only the installs whose fetch never succeeded. Appending is free by
 * the battery's own rule — a template that fails to reconcile emits nothing — so the floor costs at
 * most a few attempts that lose.
 */
class PublishedTemplatesExtendTheBatteryTest {

    private fun template(id: String) = LineItemBattery.Template(id = id, row = Regex("""^(?<desc>.+?)\s+(?<value>[\d.,]+)$"""))

    @Test
    fun `with no published file the battery is the whole answer`() {
        assertEquals(LineItemBattery.BUILT_IN, ReceiptTemplates.itemsAfter(emptyList()))
    }

    @Test
    fun `a published file is tried first and the battery still follows`() {
        val published = listOf(template("vendor-a"), template("vendor-b"))
        val result = ReceiptTemplates.itemsAfter(published)

        assertEquals(published, result.take(2))
        assertEquals(LineItemBattery.BUILT_IN, result.drop(2))
    }

    /** Restating a built-in replaces it rather than adding a second copy underneath — otherwise a
     *  correction published for a pattern would be shadowed by the very version it corrects. */
    @Test
    fun `a published pattern that restates a built-in one wins outright`() {
        val corrected = template("name-amount")
        val result = ReceiptTemplates.itemsAfter(listOf(corrected))

        assertEquals(corrected, result.first())
        assertEquals(1, result.count { it.id == "name-amount" })
        assertEquals(LineItemBattery.BUILT_IN.size, result.size)
    }

    /** The shape this was found by: a two-line row pattern is of no use to anyone if a published
     *  file silently removes it. */
    @Test
    fun `the two-line row pattern survives a published file`() {
        val result = ReceiptTemplates.itemsAfter(listOf(template("vendor-a")))
        assertTrue(result.any { it.id == "figures-under-name" })
    }
}
