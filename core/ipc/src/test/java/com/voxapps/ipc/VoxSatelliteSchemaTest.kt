package com.voxapps.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxSatelliteSchemaTest {

    @Test
    fun `round-trips with needsExtractionPass true`() {
        val schema = VoxSatelliteSchema(
            needsExtractionPass = true,
            promptTemplate = "Rules...\n\nInput: ${VoxSatelliteSchema.INPUT_PLACEHOLDER}",
            fieldSchemaVersion = 3
        )
        assertEquals(schema, VoxSatelliteSchema.fromJson(schema.toJson()))
    }

    @Test
    fun `round-trips with needsExtractionPass false and default fields`() {
        val schema = VoxSatelliteSchema(needsExtractionPass = false)
        val parsed = VoxSatelliteSchema.fromJson(schema.toJson())!!
        assertFalse(parsed.needsExtractionPass)
        assertEquals("", parsed.promptTemplate)
        assertEquals(0, parsed.fieldSchemaVersion)
    }

    @Test
    fun `missing needsExtractionPass is treated as a malformed contract, not implicit false`() {
        assertNull(VoxSatelliteSchema.fromJson("""{"promptTemplate":"x"}"""))
    }

    @Test
    fun `null blank and malformed payloads parse to null`() {
        assertNull(VoxSatelliteSchema.fromJson(null))
        assertNull(VoxSatelliteSchema.fromJson(""))
        assertNull(VoxSatelliteSchema.fromJson("{ not json"))
    }

    @Test
    fun `buildPrompt substitutes the input placeholder`() {
        val schema = VoxSatelliteSchema(
            needsExtractionPass = true,
            promptTemplate = "Categories: groceries, rent\n\n${VoxSatelliteSchema.INPUT_PLACEHOLDER}"
        )
        val prompt = schema.buildPrompt("bought 3 apples for 6 lei")
        assertTrue(prompt.contains("bought 3 apples for 6 lei"))
        assertFalse(prompt.contains(VoxSatelliteSchema.INPUT_PLACEHOLDER))
    }
}
