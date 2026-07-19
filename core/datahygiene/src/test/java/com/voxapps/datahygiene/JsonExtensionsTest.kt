package com.voxapps.datahygiene

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonExtensionsTest {

    @Test
    fun `a genuine JSON null is treated as null, not the literal string null`() {
        val o = JSONObject("""{"vendor":null}""")
        assertNull(o.optCleanString("vendor"))
    }

    @Test
    fun `the literal string null as a value is also treated as null`() {
        val o = JSONObject("""{"vendor":"null"}""")
        assertNull(o.optCleanString("vendor"))
    }

    @Test
    fun `an absent key is treated as null`() {
        val o = JSONObject("""{}""")
        assertNull(o.optCleanString("vendor"))
    }

    @Test
    fun `a punctuation-only value is treated as null`() {
        val o = JSONObject("""{"vendor":"."}""")
        assertNull(o.optCleanString("vendor"))
    }

    @Test
    fun `a real value is returned trimmed`() {
        val o = JSONObject("""{"vendor":"  eMAG  "}""")
        assertEquals("eMAG", o.optCleanString("vendor"))
    }
}
