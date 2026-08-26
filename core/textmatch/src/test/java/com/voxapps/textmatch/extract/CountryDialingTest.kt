package com.voxapps.textmatch.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryDialingTest {

    /** The label the feature met in the wild: a .ro site beside a national number. */
    @Test
    fun `the document's own domain completes its phone number`() {
        val tld = CountryDialing.tldOf("www.biovita.ro")
        assertEquals("ro", tld)
        val dial = CountryDialing.dialOf(tld)!!
        val completed = CountryDialing.internationalize("0748777222", dial)!!
        assertEquals("+40748777222", completed.full)
        assertEquals("+40", completed.prefix)
        assertEquals("748777222", completed.rest)
    }

    @Test
    fun `an email's domain answers the same question`() {
        assertEquals("ro", CountryDialing.tldOf("office@biovita.ro"))
        assertEquals("de", CountryDialing.tldOf("https://shop.example.de/kontakt"))
        assertEquals("br", CountryDialing.tldOf("vendas@empresa.com.br"))
    }

    /** `.com` belongs to no country, and a reader that pretended otherwise would prefix numbers
     *  with a guess. */
    @Test
    fun `a generic domain names no country`() {
        assertNull(CountryDialing.dialOf(CountryDialing.tldOf("www.example.com")))
        assertNull(CountryDialing.dialOf("org"))
        assertNull(CountryDialing.tldOf("no dots here"))
        assertNull(CountryDialing.tldOf(null))
    }

    /** What a document stated outright is never rewritten. */
    @Test
    fun `an international number is left alone`() {
        val dial = CountryDialing.dialOf("ro")!!
        assertNull(CountryDialing.internationalize("+40213456789", dial))
        assertNull(CountryDialing.internationalize("0040213456789", dial))
        assertNull(CountryDialing.internationalize(null, dial))
    }

    /** The general rule drops the national trunk zero — +40 748…, not +40 0748…. */
    @Test
    fun `the trunk zero is dropped where the plan drops it`() {
        val ro = CountryDialing.dialOf("ro")!!
        assertEquals("+40748777222", CountryDialing.internationalize("0748777222", ro)?.full)
    }

    /** Italy's plan keeps it: +39 06…, and stripping it would build a number that rings nowhere. */
    @Test
    fun `the trunk zero survives where the plan keeps it`() {
        val it = CountryDialing.dialOf("it")!!
        assertEquals("+390669812345", CountryDialing.internationalize("0669812345", it)?.full)
    }

    @Test
    fun `a number with no trunk zero is prefixed as it stands`() {
        val us = CountryDialing.dialOf("us")!!
        assertEquals("+12125551234", CountryDialing.internationalize("2125551234", us)?.full)
    }
}
