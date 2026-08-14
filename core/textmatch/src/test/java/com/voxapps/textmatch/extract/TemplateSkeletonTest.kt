package com.voxapps.textmatch.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TemplateSkeletonTest {

    @Test
    fun `same template across different amounts and merchants matches`() {
        // The proposal's founding case: bank boilerplate constant, variables differ. The merchant
        // is a resolved span in both, so the skeletons are identical.
        val a = TemplateSkeleton.of("Tranzactie", "Tranzactie de 45.00 RON la Kaufland", listOf("Kaufland"))
        val b = TemplateSkeleton.of("Tranzactie", "Tranzactie de 120.50 RON la eMAG", listOf("eMAG"))
        assertEquals(a, b)
    }

    @Test
    fun `a sign is part of the template, not the number`() {
        // One template, both directions, a single differing character — the exact shape that
        // silently inverts transfers if the sign is swallowed into the number marker.
        val out = TemplateSkeleton.of("Banca", "Tranzactie: -45 RON")
        val inn = TemplateSkeleton.of("Banca", "Tranzactie: +45 RON")
        assertNotEquals(out, inn)
    }

    @Test
    fun `an unresolved merchant keeps the skeletons apart`() {
        // No resolved span for the merchant: the skeleton retains it verbatim, the templates never
        // match, and the caller declines — degradation is to no answer, never a wrong one.
        val a = TemplateSkeleton.of(null, "45.00 RON la Kaufland")
        val b = TemplateSkeleton.of(null, "45.00 RON la eMAG")
        assertNotEquals(a, b)
    }

    @Test
    fun `the wallet shape normalizes vendor bank and card fragment`() {
        val a = TemplateSkeleton.of("LAZAR IONUT PFA", "63,00 RON with ING Card •4535", listOf("LAZAR IONUT PFA", "ING"))
        val b = TemplateSkeleton.of("LIDL SRL", "12,50 RON with BCR Card •1111", listOf("LIDL SRL", "BCR"))
        assertEquals(a, b)
    }

    @Test
    fun `currency symbols collapse, currency codes stay`() {
        val a = TemplateSkeleton.of(null, "You received $500.00")
        val b = TemplateSkeleton.of(null, "You received €750.25")
        assertEquals(a, b)
        // RON is letters — boilerplate, not a symbol — so it distinguishes templates.
        val c = TemplateSkeleton.of(null, "You received 500.00 RON")
        assertNotEquals(a, c)
    }

    @Test
    fun `case and whitespace do not split templates`() {
        val a = TemplateSkeleton.of("Revolut", "You   SPENT 45 RON")
        val b = TemplateSkeleton.of("revolut", "you spent 99 ron")
        assertEquals(a, b)
    }

    @Test
    fun `timestamps collapse with their separators`() {
        val a = TemplateSkeleton.of(null, "Plata 45 RON la 14:32:01")
        val b = TemplateSkeleton.of(null, "Plata 90 RON la 09:05:59")
        assertEquals(a, b)
    }

    @Test
    fun `hash is stable and skeleton-distinct`() {
        val s1 = TemplateSkeleton.of("A", "x 1")
        val s2 = TemplateSkeleton.of("A", "y 1")
        assertEquals(TemplateSkeleton.hash(s1), TemplateSkeleton.hash(s1))
        assertNotEquals(TemplateSkeleton.hash(s1), TemplateSkeleton.hash(s2))
    }
}
