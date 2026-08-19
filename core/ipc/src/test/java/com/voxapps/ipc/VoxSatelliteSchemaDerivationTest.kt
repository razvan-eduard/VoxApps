package com.voxapps.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract as it follows from a flow, rather than as anyone remembers to restate it.
 *
 * Both halves matter. A satellite that asks nothing must not hand over a prompt — Commander would
 * cache a question it will never be told to stop asking. And one that does ask must hand over a form
 * with somewhere to put the words, because a template without the placeholder is a prompt that
 * silently drops what the user said.
 */
class VoxSatelliteSchemaDerivationTest {

    @Test
    fun `a flow that asks nothing promises nothing`() {
        val schema = VoxSatelliteSchema.of(
            asksModel = false,
            promptTemplate = "this would be ignored",
            taskId = "SOMETHING",
            fieldSchemaVersion = 7
        )
        assertFalse(schema.needsExtractionPass)
        assertEquals("", schema.promptTemplate)
        assertEquals("", schema.taskId)
    }

    /** A flow that means to ask but has no question yet is the same case, not a broken one. */
    @Test
    fun `asking with no question left to ask promises nothing either`() {
        listOf(null, "", "   ").forEach { empty ->
            val schema = VoxSatelliteSchema.of(asksModel = true, promptTemplate = empty, taskId = "T")
            assertFalse("template <$empty> should not promise a pass", schema.needsExtractionPass)
        }
    }

    @Test
    fun `a flow that asks hands over its form and its task`() {
        val template = "Read this: ${VoxSatelliteSchema.INPUT_PLACEHOLDER}"
        val schema = VoxSatelliteSchema.of(
            asksModel = true,
            promptTemplate = template,
            taskId = "EXPENSE_PARSE",
            fieldSchemaVersion = 2
        )
        assertTrue(schema.needsExtractionPass)
        assertEquals(template, schema.promptTemplate)
        assertEquals("EXPENSE_PARSE", schema.taskId)
        assertEquals(2, schema.fieldSchemaVersion)
        assertEquals("Read this: bread 10 lei", schema.buildPrompt("bread 10 lei"))
    }

    /** What is derived survives the bus, which is the only reason to derive it there. */
    @Test
    fun `a derived contract round-trips`() {
        val schema = VoxSatelliteSchema.of(
            asksModel = true,
            promptTemplate = "ask ${VoxSatelliteSchema.INPUT_PLACEHOLDER}",
            taskId = "T",
            fieldSchemaVersion = 1
        )
        assertEquals(schema, VoxSatelliteSchema.fromJson(schema.toJson()))
    }
}
